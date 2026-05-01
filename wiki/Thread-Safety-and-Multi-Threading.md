## Thread Safety

The native Ghostscript API allows only one interpreter instance per system process. All operations on the interpreter must be synchronous.

Always wrap Ghostscript calls like this:

```java
Ghostscript gs = Ghostscript.getInstance();

try {
    synchronized (gs) {
        gs.initialize(gsArgs);
        gs.exit();
    }
} catch (GhostscriptException e) {
    // handle exception
} finally {
    try {
        Ghostscript.deleteInstance();
    } catch (GhostscriptException e) {
        // ignore
    }
}
```

## Multi-Threading

For multi-user environments (e.g. web applications), a single interpreter becomes a bottleneck since requests must queue up.

Since version 0.4.0, high-level API components support multi-process execution. Processing runs in separate JVMs controlled from the main JVM via the embedded [cajo](https://cajo.dev.java.net) library.

**Prerequisite:** `java` must be launchable from the command line.

Control multi-threading with the `maxProcessCount` property on any component:

| Value | Behavior |
|---|---|
| `0` (default) | Multi-threading disabled. Requests run sequentially in the main JVM. |
| `> 0` | Multi-threading enabled. Processing runs in separate JVMs, up to `maxProcessCount` concurrently. Additional requests wait for a slot. |

### Example — PDFConverter with 2 concurrent JVMs

```java
PDFConverter converter = new PDFConverter();
converter.setMaxProcessCount(2);
```
