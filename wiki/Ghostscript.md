Ghost4J is a wrapper around [Ghostscript](https://www.ghostscript.com/) — this page documents the Ghostscript tool itself to help you understand what is happening under the hood.

## What Is Ghostscript?

Ghostscript is an open source interpreter for the **PostScript** language and the **PDF** file format. It is used to:

- Convert PostScript (`.ps`) files to PDF
- Convert PDF to PostScript or other formats (PNG, JPEG, TIFF, etc.)
- Render and rasterize PDF/PS pages at arbitrary resolution
- Inspect and manipulate PDF content

It is available on Linux, macOS, and Windows. On Linux, install it via your package manager:

```bash
sudo apt-get install ghostscript          # Debian/Ubuntu
sudo dnf install ghostscript              # Fedora/RHEL
brew install ghostscript                  # macOS (Homebrew)
```

On Windows, download the installer from https://www.ghostscript.com/releases/. Ghost4J expects `gsdll64.dll` (or `gsdll32.dll`) on the system library path.

## Supported Versions

Ghost4J works with Ghostscript **9.x and later**. The native library is loaded by name:

| OS | Library name |
|---|---|
| Linux | `libgs.so` |
| macOS | `libgs.dylib` |
| Windows (64-bit) | `gsdll64.dll` |
| Windows (32-bit) | `gsdll32.dll` |

If the library is not found on startup, you will receive a `java.lang.UnsatisfiedLinkError`. Make sure Ghostscript is installed and the library is on the path:

```bash
# Linux — verify the library is locatable
ldconfig -p | grep libgs
```

## Key Concepts

### The Ghostscript Interpreter

Ghostscript is fundamentally a PostScript interpreter. PDF support is layered on top — internally, PDFs are converted to PostScript before being processed.

The C API (`gsapi_*` functions in `iapi.h`) exposes:
- Creating and destroying an interpreter instance
- Initializing it with command-line arguments (same flags as the `gs` CLI)
- Running PostScript strings or files
- Receiving raster output via display callbacks

Ghost4J wraps all of this via JNA — you never call the C API directly.

### Devices

Ghostscript's output format is determined by the **device** (`-sDEVICE=`). Key devices:

| Device | Output |
|---|---|
| `pdfwrite` | PDF file |
| `ps2write` | PostScript file |
| `png16m` | PNG image (24-bit RGB) |
| `jpeg` | JPEG image |
| `tiff24nc` | TIFF image |
| `display` | In-memory raster (used by Ghost4J display callbacks) |

### Common Ghostscript Flags

| Flag | Meaning |
|---|---|
| `-dNOPAUSE` | Do not pause between pages |
| `-dBATCH` | Exit after last file (required for scripting) |
| `-dSAFER` | Restrict file system access. **On by default since Ghostscript 9.50** — explicit use is only needed on older versions or when overriding `-dNOSAFER`. |
| `-dNOSAFER` | Disable the SAFER file access restrictions (use with caution). |
| `-dQUIET` | Suppress startup messages |
| `-r300` | Set resolution to 300 DPI |
| `-sDEVICE=...` | Set output device |
| `-sOutputFile=...` | Set output file path; use `%d` for per-page files |
| `-dPDFSETTINGS=/prepress` | PDF quality preset (screen, ebook, printer, prepress) |
| `-dCompatibilityLevel=1.7` | Set PDF version |

### PDF Settings Presets

| Preset | Use case |
|---|---|
| `/screen` | Low resolution (72 DPI), smallest file size |
| `/ebook` | Medium resolution (150 DPI) |
| `/printer` | High resolution (300 DPI) |
| `/prepress` | Maximum quality, color-managed |
| `/default` | Balanced (same as no preset) |

These map to `PDFConverter.OPTION_PDFSETTINGS_*` constants in Ghost4J.

## Thread Safety

The native Ghostscript library allows only **one interpreter instance per process**. This is a fundamental constraint of the C API, not a Ghost4J limitation.

On **Windows**, `gsdll64.dll` is also restricted to one instance per process, whereas on **Linux**, `libgs.so` can be loaded by multiple processes simultaneously.

Ghost4J's high-level API works around the single-instance constraint with multi-process execution. See [[Thread-Safety-and-Multi-Threading]].

## Known Gotchas

These are real issues discovered while building and testing Ghost4J on Ghostscript 9.50+. See [docs/GHOSTSCRIPT.md](../blob/master/docs/GHOSTSCRIPT.md) for full technical detail.

### `-dSAFER` blocks post-init file access (GS 9.50+)

**`-dSAFER` is on by default since GS 9.50.** When Ghost4J calls `gs.runFile(...)` after `gs.initialize(...)` has returned, SAFER mode blocks reading the file and Ghostscript returns a fatal error (`-100`).

This affects `Ghostscript.runFile()` and any component (FontAnalyzer, SimpleRenderer, etc.) that feeds files to GS after initialization.

**Fix**: include `-dNOSAFER` in the args passed to `gs.initialize()` when you intend to call `gs.runFile()` afterward:

```java
gs.initialize(new String[]{
    "-dQUIET", "-dNOPAUSE", "-dBATCH", "-dNOSAFER", "-sDEVICE=pdfwrite",
    "-sOutputFile=out.pdf"
});
gs.runFile("input.ps");
```

Note: passing an input file directly inside `gs.initialize()` (via `-f input.ps` as part of the args array) works fine even with SAFER enabled — the restriction applies only to **post-init** `runFile()` calls.

### Headless Linux / WSL: always specify a device

When no `-sDEVICE=...` is given and no `-dNODISPLAY` is present, Ghostscript tries to open an X11 display. On headless servers or WSL this causes an `abort()` → `SIGABRT` → JVM crash. Maven/Gradle test runners report "forked VM terminated without properly saying goodbye" (exit code 134).

**Fix**: always pass `-dNODISPLAY` or a headless device such as `-sDEVICE=nullpage` when running without a display.

### `gs_error_Quit (-101)` from `-dBATCH` is not a failure

When `-dBATCH` is used without input files, `gsapi_init_with_args` returns `-101` (`gs_error_Quit`). The interpreter is fully initialized and ready — this is expected behaviour. Ghost4J's `Ghostscript` class handles this internally; you do not need to do anything special.

### stdout corruption on headless Linux test runners

If Ghostscript writes its startup banner to native stdout (for example, because `-dQUIET` was accidentally placed as the first arg and was silently skipped — see `docs/GHOSTSCRIPT.md`), it corrupts test runners like Maven Surefire that use stdout for binary result framing.

**Fix**: call `gs.setStdOut(...)` and `gs.setStdErr(...)` before `gs.initialize()` to redirect GS output. Ghost4J defaults to routing GS output through SLF4J, but setting these explicitly in tests is safer.

## Useful Resources

- [Ghostscript usage guide](https://ghostscript.readthedocs.io/en/latest/Use.html)
- [Ghostscript output devices](https://ghostscript.readthedocs.io/en/latest/Devices.html)
- [High-level devices (PDF/PS settings)](https://ghostscript.readthedocs.io/en/latest/VectorDevices.html)
- [Ghostscript C API](https://ghostscript.readthedocs.io/en/latest/API.html)
