# Ghost4J — Copilot Agent Instructions

## Repository Summary
Ghost4J is a Java library that binds the Ghostscript C API to Java via JNA (Java Native Access). It provides both a low-level JNA bridge to the Ghostscript interpreter and a high-level object-oriented API for working with PDF and PostScript documents (conversion, rendering, analysis, modification). The library supports running components in a separate JVM process (remote mode) for thread safety, using GNU Cajo for inter-process RPC.

**Type**: Java library (JAR)  
**Version**: 1.0.5  
**License**: LGPL  
**Source files**: 98 main + 13 test Java files  
**No GitHub Actions CI workflows exist** in this repository.

---

## Build Environment Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java | 11–21 recommended | **Java 25 works for compile/package but causes JVM crash on test exit** |
| Maven | 3.8+ | Tested with 3.8.5 |
| Ghostscript | 9+ | **Required at runtime and for tests** — `libgs.so` must be on the library path |

On Linux, install Ghostscript with: `sudo apt-get install ghostscript`

---

## Java Version Compatibility

The `pom.xml` sets `<source>11</source>` and `<target>11</target>` in the compiler plugin (requires Java 11+). Command-line overrides (`-Dmaven.compiler.source=N`) do NOT work because the plugin configuration in `pom.xml` takes precedence with `maven-compiler-plugin:3.0`.

---

## Build Commands

Always run these from the repository root (`/path/to/ghost4j/`).

### Compile (validate your changes compile)
```bash
mvn compile
```
Produces compiled classes in `target/classes/`.

### Build JAR (skip tests)
```bash
mvn package -DskipTests
```
Produces `target/ghost4j-1.0.5.jar`. **Use this when Ghostscript is not installed or when testing infrastructure is unavailable.**

### Run Tests
```bash
mvn test
```
**Prerequisite**: Ghostscript native library (`libgs.so` on Linux, `gsdll64.dll` on Windows) must be installed.

**Known test behaviour**: With Java 25, all 10 tests pass but the forked JVM crashes on exit (JNA 4.1.0 + Java 25 incompatibility). Maven reports `BUILD FAILURE` even though zero test failures/errors occur. This is a pre-existing environmental issue, not a code defect. On Java 8–21, tests should complete cleanly.

### Clean build
```bash
mvn clean package -DskipTests
```

### Full build with tests
```bash
mvn clean install
```

### Validate changes compile and pass static checks
There is no dedicated lint step. Validate with:
```bash
mvn compile test-compile
```

---

## Project Layout

```
ghost4j/
├── pom.xml                          # Maven build file — single-module project
├── README.md                        # Usage docs and Maven dependency snippet
├── LICENSE
├── src/
│   ├── main/
│   │   ├── assembly/dist.xml        # Assembly descriptor for ZIP distribution
│   │   └── java/
│   │       ├── gnu/cajo/            # Bundled GNU Cajo RPC library (remote component support)
│   │       └── org/ghost4j/
│   │           ├── Ghostscript.java           # Singleton Ghostscript interpreter wrapper
│   │           ├── GhostscriptLibrary.java    # JNA interface bridging Ghostscript C API
│   │           ├── GhostscriptLibraryLoader.java  # OS-aware native lib loader
│   │           ├── GhostscriptException.java
│   │           ├── GhostscriptRevision.java
│   │           ├── Component.java             # Top-level interface for all components
│   │           ├── AbstractComponent.java     # Shared base (document validation, settings)
│   │           ├── AbstractRemoteComponent.java  # Base for remote-process components
│   │           ├── analyzer/                  # FontAnalyzer, InkAnalyzer
│   │           ├── converter/                 # PDFConverter, PSConverter
│   │           ├── display/                   # DisplayCallback, PageRaster, ImageWriter
│   │           ├── document/                  # PDFDocument, PSDocument, PaperSize
│   │           ├── example/                   # Standalone usage examples
│   │           ├── modifier/                  # SafeAppenderModifier
│   │           ├── renderer/                  # SimpleRenderer
│   │           └── util/                      # JavaFork, DiskStore, NetworkUtil, etc.
│   └── test/
│       ├── java/org/ghost4j/        # JUnit 3-style tests (extend TestCase)
│       └── resources/               # input.ps, input.pdf, input-2pages.pdf, input-2pages.ps
```

---

## Key Architecture Facts

- **`Ghostscript`** (`org.ghost4j.Ghostscript`) is a **thread-unsafe singleton**. Only one Ghostscript instance can exist per JVM. Call `Ghostscript.deleteInstance()` in teardown.
- **Remote mode**: Components like `PDFConverter`, `SimpleRenderer`, `SafeAppenderModifier` extend `AbstractRemoteComponent`. When `maxProcessCount > 0` is set, they fork a child JVM via `JavaFork` and communicate over GNU Cajo (RMI-style). The child JVM's main method is the component's own `main()`.
- **JNA bridge**: `GhostscriptLibrary` is a JNA `Library` interface. On Linux it loads `libgs.so`; on Windows it loads `gsdll32.dll` or `gsdll64.dll` based on arch.
- **Document classes**: `PSDocument` and `PDFDocument` both implement `Document` and hold raw byte content. Pass them to converters/renderers/analyzers.
- **Tests**: Use JUnit 3 style (`extends TestCase`, `setUp()`/`tearDown()`). Test resources loaded via `this.getClass().getClassLoader().getResourceAsStream("input.ps")`.

---

## Dependencies (key)

| Artifact | Version | Purpose |
|----------|---------|---------|
| `net.java.dev.jna:jna` | 4.1.0 | Native Ghostscript API bridge |
| `org.slf4j:slf4j-api` | 1.7.32 | Logging facade |
| `commons-beanutils:commons-beanutils` | 1.9.4 | Settings copy/extract via reflection |
| `org.apache.xmlgraphics:xmlgraphics-commons` | 2.3 | Image I/O utilities |
| `com.lowagie:itext` | 2.1.7 | PDF document manipulation |
| `junit:junit` | 4.11 | Testing (used in JUnit 3 style) |

---

## Validation Checklist

Before considering a change complete:
1. `mvn compile` succeeds with no errors (after fixing pom.xml source/target if needed).
2. `mvn test-compile` succeeds — test sources also compile.
3. If Ghostscript is available: `mvn test` shows `Tests run: N, Failures: 0, Errors: 0`.
4. New component types follow the `Component → AbstractComponent → AbstractRemoteComponent → ConcreteClass` hierarchy.
5. New converters/renderers/modifiers must implement a `run()` method and a static `main()` that calls the appropriate `startRemote*()` method for remote-process support.

Trust these instructions. Only search the codebase if the above information is incomplete or appears incorrect for the specific change being made.
