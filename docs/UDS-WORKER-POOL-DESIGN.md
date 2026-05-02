# UDS Worker Pool — Architecture

> **Status**: Implemented and shipping (May 2026).
> All 74 tests pass. GNU Cajo removed from the dispatch path.
> See `src/main/java/org/ghost4j/worker/` and `org/ghost4j/worker/protocol/`.

---

## Motivation

The original remote mode forked a new child JVM for every single call, even when
`maxProcessCount > 1`. Each fork paid the full JVM startup cost (200–500 ms) plus Ghostscript
initialisation, processed one request, then exited. GNU Cajo RMI was used purely as a
one-shot method-call transport over a dynamically allocated TCP port.

This design replaces that with:

1. **Persistent worker processes** — child JVMs that start once and serve many requests.
2. **Unix Domain Sockets (UDS)** — for the parent↔worker transport (Java 16+).
3. **Removal of GNU Cajo** — the `gnu.cajo` package is now unused (pending deletion).

The public API (`convert()`, `render()`, `analyze()`, `modify()`) is unchanged when
`maxProcessCount == 0` (local mode).

---

## High-Level Flow

```
Parent JVM
┌────────────────────────────────────┐
│  PDFConverter.convert(doc, out)    │
│       │                            │
│  WorkerPool (per component type)   │
│       │ acquire idle worker        │
│       ▼                            │
│  WorkerProcess (one per slot)      │
│       │ send RequestFrame          │
│       ▼                            │
│  UDS socket (/tmp/ghost4j-*.sock)  │
└────────────────────────────────────┘
          │
          ▼  (loopback, kernel-mediated)
          │
┌─────────────────────────────────────┐
│  Child JVM  (WorkerMain)            │
│       │ receive RequestFrame        │
│       │ deserialise Document        │
│       │ call run(document, ...)     │  ← existing GS logic, unchanged
│       │ serialise result            │
│       │ send ResponseOkFrame        │
└─────────────────────────────────────┘
```

After sending the response frame the worker loops back and waits for the next request.
The parent marks the worker idle and returns it to the pool.

---

## Package Layout

```
org.ghost4j.worker/
  WorkerPool.java             — fixed-size pool of WorkerProcess instances per component type
  WorkerProcess.java          — wraps one child process + its UDS SocketChannel
  WorkerMain.java             — entry point for every worker child JVM
  WorkerWatcher.java          — daemon thread: blocks on process.waitFor(), triggers replacement
  StderrDrainer.java          — daemon thread: drains child stderr into a bounded ring buffer
  WorkerPoolMXBean.java       — JMX interface for pool monitoring
  WorkerDispatchSupport.java  — shared helpers used by AbstractRemoteComponent
  InterpreterBusyException.java   — unchecked; thrown by WorkerPool.acquire() on timeout
  WorkerStartupException.java     — IOException subclass; thrown if child never writes ready byte
  WorkerCrashedException.java     — IOException subclass; thrown when worker socket closes mid-req

org.ghost4j.worker.protocol/
  Frame.java          — sealed interface: permits RequestFrame, ResponseOkFrame,
                        ResponseErrFrame, ControlFrame
  RequestFrame.java   — record(operationId, docType, settings, documentBytes)
  ResponseOkFrame.java  — record(operationId, result)
  ResponseErrFrame.java — record(operationId, errorClass, errorMessage)
  ControlFrame.java   — record(type) — covers SHUTDOWN, PING, PONG
  FrameCodec.java     — encodes/decodes frames over a byte stream
  ProtocolException.java — IOException subclass; thrown on magic mismatch or unknown type
```

---

## Wire Protocol

### Frame header (9 bytes, fixed)

```
[ 4 bytes: magic 0x47344A31 ("G4J1") ]
[ 1 byte:  message type              ]
[ 4 bytes: payload length (big-endian int) ]
```

### Message types

| Value  | Name          | Direction      | Notes                        |
|--------|---------------|----------------|------------------------------|
| `0x01` | `REQUEST`     | Parent→Worker  | Document processing request  |
| `0x02` | `RESPONSE_OK` | Worker→Parent  | Successful result            |
| `0x03` | `RESPONSE_ERR`| Worker→Parent  | Processing error             |
| `0x04` | `SHUTDOWN`    | Parent→Worker  | Ordered stop (no payload)    |
| `0x05` | `PING`        | Parent→Worker  | Liveness check (no payload)  |
| `0x06` | `PONG`        | Worker→Parent  | Liveness confirmation        |

### Request payload (`REQUEST`)

```
[ 4 bytes: operation ID (uint32, echoed in response for log correlation) ]
[ 1 byte:  doc type — 0x00=PDF, 0x01=PS                                 ]
[ 4 bytes: settings JSON byte length                                     ]
[ N bytes: settings as flat JSON object (UTF-8)                          ]
[ 4 bytes: document byte length                                          ]
[ M bytes: document bytes                                                ]
```

Settings values are restricted to `String`, `Number`, and `Boolean`. `FrameCodec` uses a
hand-rolled JSON encoder/decoder — Jackson is intentionally avoided in the codec to eliminate
transitive dependency fragility.

### Response payload (`RESPONSE_OK`)

```
[ 4 bytes: operation ID ]
[ 4 bytes: result byte length ]
[ N bytes: result bytes ]
```

### Response payload (`RESPONSE_ERR`)

```
[ 4 bytes: operation ID         ]
[ 4 bytes: error class name len ]
[ N bytes: error class name (UTF-8) ]
[ 4 bytes: error message len    ]
[ N bytes: error message (UTF-8)]
```

`FrameCodec.readFrame()` verifies the magic number first, throwing `ProtocolException` on
mismatch. A hard `MAX_PAYLOAD_BYTES` limit (256 MiB) rejects oversized frames before any
allocation.

---

## Worker Startup Handshake

```
Parent (WorkerProcess.spawn()):
  1. ProcessBuilder.start() → child JVM begins
  2. Read exactly 1 byte from child stdout (blocks)

Worker (WorkerMain.main()):
  3. ServerSocketChannel.open(UNIX).bind(socketPath)   ← socket ready to accept
  4. System.out.write(0x01); System.out.flush()        ← proves bind() has completed
  5. serverSocket.accept()                              ← blocks until parent connects

Parent (continued):
  6. Receives 0x01 — bind() is done, connect is safe
  7. SocketChannel.connect(socketPath)
  8. accept() returns in child — connection established
```

The ready byte is written *after* `bind()` but *before* `accept()`. The kernel guarantees
`connect()` succeeds after `bind()`, so there is no race. If the byte is not received within
15 000 ms, `WorkerStartupException` is thrown and the process is force-killed.

`LD_LIBRARY_PATH` is propagated to the child so `libgs.so` is findable. The child uses the
same Java binary as the parent via `ProcessHandle.current().info().command()`.

---

## WorkerMain Dispatch

`WorkerMain` receives the component class name via `-Dghost4j.worker.class` and dispatches by
`instanceof`:

| Component type            | What WorkerMain does                                                        |
|---------------------------|-----------------------------------------------------------------------------|
| `AbstractRemoteConverter` | Calls `run(doc, baos)`; result is raw bytes                                 |
| `AbstractRemoteAnalyzer`  | Calls `run(doc)`; result JSON-encoded via `serializeItems()`                |
| `AbstractRemoteRenderer`  | Extracts `_pageBegin`/`_pageEnd` from settings; calls `run(doc, b, e)`; result binary-encoded via `serializeRasters()` |
| `AbstractRemoteModifier`  | Extracts `_param_*` keys via `extractParameters()`; calls `modify(doc, params)`; result encoded via `encodeResult()` |

Settings are applied to the component instance with `BeanUtils.populate()` after reserved keys
are removed.

---

## Result Encoding Per Component Type

### Converter
Raw `byte[]` from the `ByteArrayOutputStream` passed to `run()`.

### Analyzer — JSON discriminator encoding
`List<AnalysisItem>` is polymorphic. Jackson serialises each item to a JSON object with an
added `"@class"` field (fully-qualified concrete class name). On deserialisation, `@class` is:
1. Validated to be a known subtype of `AnalysisItem` (OWASP A8 guard).
2. **Removed from the ObjectNode** before `MAPPER.treeToValue()` — Jackson will reject unknown
   fields otherwise (`UnrecognizedPropertyException`).

### Renderer — compact binary
`List<PageRaster>` encoded as:
```
[ 4 bytes: count ]
[ per raster: 4 width, 4 height, 4 raster, 4 format, 4 dataLen, N data ]
```
All integers are big-endian. `serializeRasters()` / `deserializeRasters()` are public static
methods on `AbstractRemoteRenderer` called symmetrically by WorkerMain and the parent.

### Modifier — document result + document parameters

**Result**: `[1 byte docType][4 bytes contentLen][N bytes content]` — same docType byte
constants as `RequestFrame.DOC_PDF` / `DOC_PS`.

**Document parameters**: `SafeAppenderModifier` (and potentially other modifiers) receive
`Document` objects in their `parameters` map. Since `FrameCodec` only encodes JSON primitives,
`Document` parameters are encoded as strings before frame construction:
```
"_param_APPEND_DOCUMENT" → "__DOC__:PS:<base64-encoded content>"
```
`extractParameters()` on the worker side detects the `__DOC__:` prefix, decodes the base64,
and reconstructs the `PSDocument` or `PDFDocument` before calling `run()`.

---

## Changes to Existing Classes

### `AbstractComponent.extractSettings()`
`PropertyUtils.describe()` returns all JavaBean properties, including `class` (`Class<?>`),
`supportedDocumentClasses` (`Class<?>[]`), and `PaperSize` objects — none of which are JSON
serialisable. Added a `removeIf` filter: only `String`, `Number`, and `Boolean` values are
kept. `maxProcessCount` is also removed (not needed in the worker).

### `AbstractRemoteComponent`
Cajo `startRemoteServer()` / `getRemoteComponent()` / `buildJavaFork()` removed.
Added: `static ConcurrentHashMap<Class<?>, WorkerPool> pools`, `AtomicInteger operationCounter`,
`getOrCreatePool(int size)`.

### `AbstractRemoteConverter` / `AbstractRemoteAnalyzer` / `AbstractRemoteRenderer` / `AbstractRemoteModifier`
All Cajo boilerplate (`startRemote*()`, `remote*()` methods) replaced with worker-pool dispatch.
Each `convert()` / `analyze()` / `render()` / `modify()` method:
1. Short-circuits to `run()` when `maxProcessCount == 0`.
2. Otherwise: acquires a worker, builds a `RequestFrame`, sends it, reads a `ResponseFrame`.
3. MDC keys (`ghost4j.operationId`, `ghost4j.component`, `ghost4j.workerId`) set for the
   duration of the call and removed in `finally`.

### `AbstractRemoteRenderer.run()` — must be `public`
`run()` is declared `public abstract` (not `protected`) so `WorkerMain` in the
`org.ghost4j.worker` package can call it via an `AbstractRemoteRenderer` reference. Java
protected access does not cross package boundaries for non-subclass callers.

### `RemoteRenderer` interface
`remoteRender(Document, int, int)` removed — was a Cajo cross-JVM call target only.
`setMaxProcessCount(int)` remains.

### Per-component `main()` methods
Each of the 6 concrete components (`PDFConverter`, `PSConverter`, `FontAnalyzer`, `InkAnalyzer`,
`SimpleRenderer`, `SafeAppenderModifier`) now delegates entirely to `WorkerMain.main(args)`.

---

## Worker Lifecycle

```
First remote call
  └─> WorkerPool created; N workers forked in parallel

Subsequent calls
  └─> acquire idle worker (blocks up to acquireTimeoutMs; throws InterpreterBusyException)
  └─> send RequestFrame, receive ResponseFrame
  └─> release back to pool

Worker exit (normal — Ghostscript -dBATCH exits cleanly with code 0)
  └─> WorkerWatcher detects exit; logs "crashed with exit code 0 after N requests"
  └─> pool.replaceWorker(dead): forks a fresh worker into the idle queue
  └─> subsequent requests use the replacement worker transparently

JVM shutdown
  └─> WorkerPool shutdown hook fires
  └─> sends SHUTDOWN ControlFrame to each idle worker
  └─> waits shutdownGracePeriodMs for processes to exit
  └─> force-kills any that do not exit cleanly
```

> **Note**: "crashed with exit code 0" in the log is **not an error**. Ghostscript with
> `-dBATCH` always exits after completing its work. The pool treats any worker exit as a crash
> and replaces it — this is intentional and keeps the pool full.

---

## Observability

### SLF4J MDC — request correlation

Three MDC keys are set for the lifetime of each remote call:

```java
MDC.put("ghost4j.operationId", Integer.toHexString(opId));
MDC.put("ghost4j.component",   this.getClass().getSimpleName());
MDC.put("ghost4j.workerId",    worker.id().toString());
```

A single `grep ghost4j.operationId=3a7f1b` spans both JVMs in a log aggregator.

### JMX — `WorkerPoolMXBean`

One MBean registered per pool at `org.ghost4j:type=WorkerPool,component=<ClassName>`:

```java
public interface WorkerPoolMXBean {
    String  getComponentClass();
    int     getPoolSize();
    int     getIdleWorkers();
    int     getActiveWorkers();
    long    getRequestsProcessed();
    long    getCrashCount();
    long    getTimeoutCount();
    long    getLastWorkerStartupMs();
}
```

Registered in the `WorkerPool` constructor; deregistered in `shutdown()`.

### Request latency — DEBUG + slow-request WARN

```java
long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
log.debug("op={} component={} docBytes={} durationMs={}", ...);
if (elapsedMs > 30_000L) {
    log.warn("Slow GS request: op={} durationMs={}", ...);
}
```

---

## Security

- **No network exposure**: UDS socket files are in `java.io.tmpdir`, owned by the user running
  the JVM. `WorkerProcess` sets `rw-------` POSIX permissions immediately after bind.
- **No Java deserialisation**: the protocol uses binary framing with JSON settings — no
  `ObjectInputStream` in the IPC path (OWASP A8 mitigated).
- **Payload size cap**: `FrameCodec` enforces `MAX_PAYLOAD_BYTES` (256 MiB); oversized frames
  throw `ProtocolException` before allocation.
- **Analyser type guard**: `deserializeItems()` validates the `@class` discriminator is an
  `AnalysisItem` subtype before instantiation; arbitrary class loading is refused.

---

## What Was Removed

| Removed                                    | Replaced by                                           |
|--------------------------------------------|-------------------------------------------------------|
| `gnu.cajo` package (~15 classes)           | `WorkerPool`, `WorkerProcess`, `FrameCodec`           |
| `JavaFork`                                 | `ProcessBuilder` inside `WorkerProcess`               |
| TCP port allocation (`NetworkUtil`)         | UDS socket path (`/tmp/ghost4j-{uuid}.sock`)          |
| `NetworkUtil.waitUntilPortListening`        | stdout `0x01` ready-byte                              |
| `Remote.config`, `ItemServer.bind`         | `FrameCodec` framing                                  |
| `RemoteConverter.remoteConvert()` et al.   | `RequestFrame` / `ResponseOkFrame`                    |
| Per-component `startRemote*()` methods     | `WorkerMain.main()` delegation                        |

> All listed removals have been applied to the source tree.
