# Ghost4J — Copilot Agent Instructions

## Repository Summary
Ghost4J is a Java library that binds the Ghostscript C API to Java via JNA (Java Native Access). It provides both a low-level JNA bridge to the Ghostscript interpreter and a high-level object-oriented API for working with PDF and PostScript documents (conversion, rendering, analysis, modification). The library supports running components in a persistent separate JVM process (remote mode) for thread safety, using a UDS worker pool for inter-process communication.

**Type**: Java library (JAR)
**Group / Artifact**: `io.github.michaelkimball:ghost4j`
**Version**: 1.2.0
**License**: LGPL
**Source files**: ~75 main + 11 test Java files
**No GitHub Actions CI workflows exist** in this repository.

---

## Build Environment Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java | 17–25 | Source/target compiled at Java 17; runtime tested on Java 25 (Amazon Corretto) |
| Gradle | 9.5.0 | Use **`./gradlew`** — never a system Gradle |
| Ghostscript | 9.50+ | **Required at runtime and for tests** — `libgs.so` must be on the library path |

On Linux, install Ghostscript with: `sudo apt-get install ghostscript`

---

## Build Commands

Always run from the repository root using the Gradle wrapper.

```bash
./gradlew compileJava          # compile main sources only
./gradlew test                 # run all tests (requires Ghostscript installed)
./gradlew spotlessApply        # auto-format all Java sources (do this before committing)
./gradlew spotlessCheck        # verify formatting without modifying files
./gradlew jar -x test          # build JAR, skip tests
./gradlew clean build          # full clean build including tests
./gradlew compileJava compileTestJava   # validate compile without running tests
```

**Produced artifact**: `build/libs/ghost4j-1.2.0.jar`

---

## Code Formatting

All Java sources are formatted by **Spotless** using **Google Java Format 1.27.0 AOSP style** (4-space indentation). Run `./gradlew spotlessApply` before committing. `./gradlew spotlessCheck` fails CI if sources are not formatted. Do not configure IDE formatters manually.

Every source file must begin with the LGPL file header comment block (see any existing source file for the exact text).

---

## Project Layout

```
ghost4j/
├── build.gradle.kts                 # Kotlin DSL build file
├── gradle/
│   ├── libs.versions.toml           # Version catalog
│   └── wrapper/                     # Gradle wrapper scripts
├── docs/
│   ├── GHOSTSCRIPT.md               # Internal reference — GS C API gotchas, must read before touching GS code
│   └── UDS-WORKER-POOL-DESIGN.md    # Architecture reference for the UDS worker pool
├── wiki/                            # GitHub wiki source (mirrored to github.com/michaelkimball/ghost4j.wiki.git)
├── README.md
├── LICENSE
└── src/
    ├── main/
    │   └── java/
    │       └── org/ghost4j/
    │           ├── Ghostscript.java           # Singleton Ghostscript interpreter wrapper
    │           ├── GhostscriptLibrary.java    # JNA interface bridging Ghostscript C API
    │           ├── GhostscriptLibraryLoader.java  # OS-aware native lib loader
    │           ├── GhostscriptException.java
    │           ├── GhostscriptRevision.java
    │           ├── Component.java             # Top-level interface for all components
    │           ├── AbstractComponent.java     # Shared base (document validation, settings)
    │           ├── AbstractRemoteComponent.java  # Base for remote-process components
    │           ├── analyzer/                  # FontAnalyzer, InkAnalyzer
    │           ├── converter/                 # PDFConverter, PSConverter
    │           ├── display/                   # DisplayCallback, PageRaster, ImageWriter
    │           ├── document/                  # PDFDocument, PSDocument, PaperSize
    │           ├── example/                   # Standalone usage examples
    │           ├── modifier/                  # SafeAppenderModifier
    │           ├── renderer/                  # SimpleRenderer
    │           ├── util/                      # DiskStore, ImageUtil, etc.
    │           └── worker/                    # UDS worker pool (WorkerPool, WorkerProcess, WorkerMain, …)
    │               └── protocol/              # Binary frame protocol (Frame, FrameCodec, …)
    └── test/
        ├── java/org/ghost4j/        # JUnit Jupiter tests
        └── resources/               # input.ps, input.pdf, input-2pages.pdf, input-2pages.ps
```

---

## Key Architecture Facts

- **`Ghostscript`** (`org.ghost4j.Ghostscript`) is a **thread-unsafe singleton**. Only one Ghostscript instance can exist per JVM. Call `Ghostscript.deleteInstance()` in `@AfterEach`.
- **Remote mode**: Components like `PDFConverter`, `SimpleRenderer`, `SafeAppenderModifier` extend `AbstractRemoteComponent`. When `maxProcessCount > 0` is set, they acquire a worker from a `WorkerPool`, send a `RequestFrame` over a Unix Domain Socket, and receive a `ResponseOkFrame` or `ResponseErrFrame`. Workers are persistent child JVMs launched by `WorkerProcess` via `ProcessBuilder`; `WorkerMain` is the entry point in each child JVM. GNU Cajo has been removed. See `docs/UDS-WORKER-POOL-DESIGN.md` for the full architecture.
- **JNA bridge**: `GhostscriptLibrary` is a JNA `Library` interface. On Linux it loads `libgs.so`; on Windows it loads `gsdll32.dll` or `gsdll64.dll` based on arch.
- **Document classes**: `PSDocument` and `PDFDocument` both implement `Document` and hold raw byte content. Pass them to converters/renderers/analyzers.
- **Tests**: JUnit 5 Jupiter style (`@BeforeEach`, `@AfterEach`, `@Test`). Test resources loaded via `getClass().getClassLoader().getResourceAsStream("input.ps")`.

---

## Dependencies (key)

| Artifact | Version | Purpose |
|----------|---------|---------|
| `net.java.dev.jna:jna` | 5.18.1 | Native Ghostscript API bridge |
| `org.slf4j:slf4j-api` | 2.0.17 | Logging facade |
| `commons-beanutils:commons-beanutils` | 1.11.0 | Settings copy/extract via reflection |
| `org.apache.xmlgraphics:xmlgraphics-commons` | 2.11 | Image I/O utilities |
| `com.itextpdf:kernel` + `layout` + `io` | 9.6.0 | PDF document manipulation |
| `org.junit.jupiter:junit-jupiter` | 6.0.3 | Testing |

---

## Critical Ghostscript API Gotchas

**Always read `docs/GHOSTSCRIPT.md` before touching Ghostscript.java, GhostscriptLibrary.java, or any test that calls `gs.initialize()`.** Key points:

### argv[0] is skipped by GS — `initialize()` prepends "gs" automatically
`gsapi_init_with_args` follows C `main()` convention: `argv[0]` is the program name and is **completely ignored** by GS. `Ghostscript.initialize()` already prepends `"gs"` — do NOT add a fake placeholder to the args you pass in.

```java
// CORRECT — initialize() adds "gs" as argv[0] automatically
gs.initialize(new String[]{"-dQUIET", "-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=nullpage"});

// WRONG — if "gs" prefix were removed, "-dQUIET" would be argv[0] (skipped); banner prints
```

### `-dSAFER` (default ON in GS 9.50+) blocks `gsapi_run_file` called after init
Passing a file inside `initialize()` args works fine. Calling `gs.runFile(...)` **after** `initialize()` returns fails with error `-100` unless SAFER is disabled or the path is whitelisted.

```java
// Preferred — keep SAFER, whitelist the directory (call BEFORE initialize):
// Pattern rules: "dir/*" permits direct children; "dir/" does NOT; exact path also works.
gs.addControlPath(Ghostscript.PERMIT_FILE_READING, file.getParent() + "/*");
gs.initialize(new String[]{"-dQUIET", "-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=pdfwrite", ...});
gs.runFile(file.getAbsolutePath());

// Alternative — disable SAFER entirely (unsandboxed):
gs.initialize(new String[]{"-dQUIET", "-dNOPAUSE", "-dBATCH", "-dNOSAFER", "-sDEVICE=pdfwrite", ...});
gs.runFile("input.ps");
```

### `-dBATCH` causes `gsapi_init_with_args` to return `-101` — this is normal
`gs_error_Quit (-101)` means the interpreter is fully initialized and ready. `Ghostscript.initialize()` handles this internally; do not treat it as an error.

### Headless Linux: always specify a device
Without `-sDEVICE=...` or `-dNODISPLAY`, GS tries to open an X11 display. On headless servers this causes `SIGABRT` → JVM crash (exit code 134).

### JNA 5.x callbacks must be stored in static fields
JNA 5.x uses a `WeakHashMap` for native callbacks. Store all `gsapi_set_stdio` callbacks in `static` fields to prevent GC while GS is active.

### `gsapi_set_arg_encoding` must be called before `gsapi_set_stdio` and `gsapi_init_with_args`

---

## Test Pattern

```java
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

class MyComponentTest {

    @BeforeEach
    void setUp() throws Exception {
        // setup
    }

    @AfterEach
    void tearDown() throws Exception {
        Ghostscript.deleteInstance();
    }

    @Test
    void testSomeBehavior() throws Exception {
        // arrange, act, assert
    }
}
```

Test resources live in `src/test/resources/` and are loaded via the classloader. Never use hardcoded file paths in tests.

---

## Validation Checklist

Before considering a change complete:
1. `./gradlew spotlessApply` — format all sources.
2. `./gradlew compileJava compileTestJava` — both compile cleanly.
3. `./gradlew test` — all tests pass (74 tests as of May 2026). Requires Ghostscript installed.
4. New component types follow the `Component → AbstractComponent → AbstractRemoteComponent → ConcreteClass` hierarchy.
5. New converters/renderers/modifiers must implement `run()` and a static `main()` that delegates to `org.ghost4j.worker.WorkerMain.main(args)`.

Trust these instructions. Only search the codebase if the above information is incomplete or appears incorrect for the specific change being made.
