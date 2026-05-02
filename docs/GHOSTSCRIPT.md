# Ghostscript — Need to Know

> Sources: [API](https://ghostscript.readthedocs.io/en/latest/API.html) · [Core Library](https://ghostscript.readthedocs.io/en/latest/Lib.html)  
> © 1988–2026 Artifex Software, Inc. — AGPL / commercial dual-license.

---

## What is Ghostscript?

Ghostscript is two things packaged together:

1. **A PostScript/PDF interpreter** — reads and executes `.ps` / `.pdf` files.
2. **A graphics library** — a set of C procedures implementing the PostScript graphics model (paths, painting, fonts, images, colour spaces, halftoning, etc.).

It is delivered as a **shared library** (`libgs.so` on Linux, `gsdll32/64.dll` on Windows) that embedders call via a published C API (`iapi.h`).

---

## The Single-Instance Constraint

> **Critical**: Ghostscript does **not** support multiple interpreter instances within a single process.

- On Linux, `libgs.so` can be loaded by multiple processes simultaneously.
- On Windows, `gsdll32.dll` / `gsdll64.dll` can only be used **once per process**.
- Any attempt to create a second instance in the same process returns an error.
- The `gsapi_*()` exported functions **must be called from one thread only**. Thread synchronisation is entirely the caller's responsibility.

---

## Lifecycle — Correct Call Order

Every embedding must follow this exact sequence:

```
gsapi_new_instance()       ← create interpreter (once per process)
  gsapi_set_arg_encoding() ← set UTF-8 before init (recommended)
  gsapi_set_stdio*()       ← optional: redirect stdin/stdout/stderr
  gsapi_set_display_callback() / gsapi_register_callout()  ← if using display device
  gsapi_init_with_args()   ← initialise and run
  gsapi_run_*()            ← optional: feed additional input
gsapi_exit()               ← always call if init_with_args was called
gsapi_delete_instance()    ← destroy; the void* must have been NULL before new_instance
```

Skipping `gsapi_exit()` before `gsapi_delete_instance()` is undefined behaviour.

---

## Exported C API — Quick Reference

All functions live in `iapi.h`. Return value `>= 0` means success; `< 0` means error. Values `<= gs_error_Fatal (-100)` are fatal — call `gsapi_exit()` immediately.

### Instance management

| Function | Purpose |
|---|---|
| `gsapi_revision(rev, len)` | Get library version/product strings. Call **before** anything else. |
| `gsapi_new_instance(&inst, caller_handle)` | Create interpreter. `inst` must be `NULL` on entry. |
| `gsapi_delete_instance(inst)` | Destroy interpreter. Must follow `gsapi_exit()`. |

### Initialisation

| Function | Purpose |
|---|---|
| `gsapi_set_arg_encoding(inst, encoding)` | Set argument encoding. Use `GS_ARG_ENCODING_UTF8` (1). Must be called before `init_with_args`. |
| `gsapi_set_default_device_list(inst, list, len)` | Override device search order (e.g. `"display x11 bbox"`). Before `init_with_args`. |
| `gsapi_get_default_device_list(inst, &list, &len)` | Query current device list. Before `init_with_args`. |
| `gsapi_init_with_args(inst, argc, argv)` | Start the interpreter. `argv[0]` is ignored (traditionally the program name). |

### Running PostScript/PDF

| Function | Purpose |
|---|---|
| `gsapi_run_file(inst, path, user_errors, &exit_code)` | Run a file. |
| `gsapi_run_string(inst, str, user_errors, &exit_code)` | Run a NUL-terminated string. Max 64 KB. |
| `gsapi_run_string_with_length(inst, str, len, ...)` | Run a counted byte buffer. Max 64 KB. |
| `gsapi_run_string_begin/continue/end(...)` | Stream input in chunks. `continue` returns `gs_error_NeedInput` when more data is expected — this is **not** an error. |
| `gsapi_exit(inst)` | Shut down. Must be called before `delete_instance`. |

> **64 KB limit**: Any single `gsapi_run_string*` call is limited to 65,535 bytes. Split larger inputs across multiple `gsapi_run_string_continue()` calls.

### stdio / Polling

| Function | Purpose |
|---|---|
| `gsapi_set_stdio(inst, stdin_fn, stdout_fn, stderr_fn)` | Redirect all stdio to callbacks. |
| `gsapi_set_stdio_with_handle(...)` | Same, but pass an explicit handle to callbacks. |
| `gsapi_set_poll(inst, poll_fn)` | Install a polling callback (called frequently during rendering). Return `< 0` to abort. |

> stdio callbacks do **not** affect `%stdout` device output — only interpreter output.

### Parameters (runtime configuration)

| Function | Purpose |
|---|---|
| `gsapi_set_param(inst, key, &value, type)` | Set a device parameter (equivalent to `-d` / `-s` on the command line). Triggers `initgraphics` internally — use only at page start. |
| `gsapi_get_param(inst, key, &value, type)` | Get a device parameter. Call with `value=NULL` first to obtain required buffer size. |
| `gsapi_enumerate_params(inst, &iter, &key, &type)` | Iterate all current device parameters. One enumeration at a time. |

**Parameter types** (`gs_set_param_type`):

| Value | Type |
|---|---|
| `gs_spt_bool` | `int*` (0 = false) |
| `gs_spt_int` | `int*` |
| `gs_spt_float` | `float*` |
| `gs_spt_string` / `gs_spt_name` | `char*` |
| `gs_spt_long` | `long*` |
| `gs_spt_i64` | `int64_t*` |
| `gs_spt_parsed` | `char*` — parsed as PostScript, e.g. `"<</HWResolution [300 300]>>"` |
| `gs_spt_more_to_come` | OR-flag — queues the param without flushing to device |

### File access / sandboxing (dSAFER)

| Function | Purpose |
|---|---|
| `gsapi_add_control_path(inst, type, path)` | Whitelist a path for file access. |
| `gsapi_remove_control_path(inst, type, path)` | Remove a whitelisted path. |
| `gsapi_purge_control_paths(inst, type)` | Clear all whitelisted paths. |
| `gsapi_activate_path_control(inst, enable)` | Enable/disable path checking. |
| `gsapi_is_path_control_active(inst)` | Query path control status. |

### Display device / callouts

```c
// Modern (preferred): register a callout handler
gsapi_register_callout(inst, my_callout_handler, state);

// Legacy (deprecated): set display callback directly
gsapi_set_display_callback(inst, &display_callback);
```

The callout handler signature:
```c
int my_callout(void *instance, void *callout_handle,
               const char *device_name, int id, int size, void *data);
```
Return `-1` to pass the callout to the next handler; `0` or positive to consume it.

---

## Return Codes

| Value | Meaning |
|---|---|
| `0` | Success |
| `gs_error_Quit` | `quit` operator executed — not an error; call `gsapi_exit()` next |
| `gs_error_NeedInput` | `gsapi_run_string_continue` needs more data — not an error |
| `gs_error_Info` | `gs -h` executed — not an error; call `gsapi_exit()` next |
| `gs_error_interrupt` | Poll callback returned negative — interpreter aborted |
| `< 0` | Error |
| `<= gs_error_Fatal` (`<= -100`) | Fatal error — **must** call `gsapi_exit()` immediately |

---

## Display Device Callbacks

Used to capture rendered raster output. The callback structure (`gdevdsp.h`) includes:

| Callback | Triggered when |
|---|---|
| `display_open(handle, device)` | Display device opened |
| `display_preclose` | Device about to close |
| `display_close` | Device closed |
| `display_presize(w, h, raster, format)` | Resize about to happen |
| `display_size(w, h, raster, format, *image)` | Resize completed; `image` points to raster buffer |
| `display_page(copies, flush)` | `showpage` executed (end of page) |
| `display_update(x, y, w, h)` | Partial render update |
| `display_sync` | Flush display |
| `display_memalloc(size)` | Allocate raster memory (return `NULL` to trigger rectangle-request mode) |
| `display_memfree(ptr)` | Free raster memory |
| `display_separation(component, name, c, m, y, k)` | Spot colour info (separation mode) |
| `display_rectangle_request(...)` | Request next rectangle to render (banded/streaming mode) |

**Display format** is set via `-dDisplayFormat=N` using constants from `gdevdsp.h`:
- Colour: native, gray, RGB, CMYK, or separation
- Depth: 1–16 bits/component
- Byte order: big-endian (RGB) or little-endian (BGR)
- Scan order: top-first or bottom-first
- Layout: chunky, planar, or planar-interleaved

---

## The Core Graphics Library (`Lib.h`)

The library exposes two API layers:

### High-level (`gs_*` prefix, `gsXXX.h`)
Direct C equivalents of PostScript operators. All take a `gs_state*` as the first argument. Return `>= 0` on success, `< 0` (PostScript error code from `gserrors.h`) on failure.

Categories covered:
- Graphics state (device-independent and device-dependent)
- Coordinate system and matrices
- Path construction (`gs_moveto`, `gs_lineto`, `gs_curveto`, `gs_arc`, etc.)
- Painting (`gs_fill`, `gs_stroke`, `gs_image_*`, etc.)
- Patterns (`gs_makepattern`, `gs_setpattern`)
- Device setup and output
- Character and font operations

Operations with callbacks (e.g. `pathforall`, `image`) use an **enumeration style** — call to set up an enumerator, then iterate.

### Low-level (`gx_*` prefix, `gxXXX.h`)
Less stable; uses device coordinates in fixed-point representation. Internal files (`gz*.c`, `gz*.h`) are not intended for external callers.

### Initialisation sequence (core library)
```c
gp_init();
gs_lib_init(stdout);
imem = ialloc_alloc_state(&gs_memory_default, 20000);
pgs = gs_state_alloc(mem);
gs_setdevice_no_erase(pgs, dev);
gs_gsave(pgs);   // at least 2 gstates must be on the stack
// ... use library ...
gs_lib_finit(0, 0);
```

---

## Common Command-Line Arguments (passed to `gsapi_init_with_args`)

| Argument | Effect |
|---|---|
| `-dNOPAUSE` | No pause between pages |
| `-dBATCH` | Exit after processing; don't enter interactive mode |
| `-dSAFER` | Sandbox: restrict file access. **Default ON in GS 9.50+.** |
| `-dNOSAFER` | Disable SAFER sandbox. Required when `gsapi_run_file` must read files post-init (see below). |
| `-dNODISPLAY` | Don't open any display device. Required on headless systems when no `-sDEVICE` is specified. |
| `-sDEVICE=<name>` | Select output device (`pdfwrite`, `png16m`, `jpeg`, `nullpage`, `display`, etc.) |
| `-sOutputFile=<path>` | Output path. Use `%d` for page number, `%stdout` for stdout. |
| `-r<dpi>` | Resolution in DPI (e.g. `-r300`) |
| `-dFirstPage=N` | Start at page N |
| `-dLastPage=N` | Stop at page N |
| `-dDisplayFormat=N` | Raster format for display device |
| `-sDisplayHandle=<n>` | Display callback handle (string, supports 64-bit values) |
| `-f <path>` | Run a file. `-f -` reads from stdin. |
| `-s<Key>=<value>` | Set a string device parameter (equivalent to `gsapi_set_param` with string type). |
| `-d<Key>=<value>` | Set a numeric/boolean device parameter. |

---

## ghost4j / JNA Usage — Critical Gotchas

ghost4j wraps the Ghostscript C API via JNA (no compiled glue layer). The following hard-won lessons apply specifically to this combination.

### argv[0] is the program name and is SKIPPED by GS

`gsapi_init_with_args(instance, argc, argv)` follows C `main()` convention: **`argv[0]` is the program name and is completely ignored for option processing.** This means:

```
{ "-dQUIET", "-dNODISPLAY", ... }  →  argv[0]="-dQUIET" is SKIPPED → banner still prints
{ "gs",      "-dQUIET", "-dNODISPLAY", ... }  →  CORRECT: -dQUIET is effective
```

**`Ghostscript.initialize()` already prepends `"gs"` automatically.** Do NOT add a fake program name placeholder to the args you pass in — it will be treated as a GS flag. The old `"-fonta"` style placeholder in `FontAnalyzer` (e.g. `-f onta` = run missing file "onta") caused a `-100` Fatal error for exactly this reason.

For direct `gsapi_init_with_args` calls (bypassing `Ghostscript.initialize()`), always pass `"gs"` as `argv[0]`:

```java
String[] args = { "gs", "-dQUIET", "-dNODISPLAY", "-dNOPAUSE", "-dBATCH" };
lib.gsapi_init_with_args(inst, args.length, args);
```

### Call `gsapi_set_arg_encoding` before any other setup

GS 9.x requires `gsapi_set_arg_encoding` to be called **before** `gsapi_set_stdio` and `gsapi_init_with_args`. Skipping it causes init to fail.

```java
lib.gsapi_set_arg_encoding(inst, GhostscriptLibrary.GS_ARG_ENCODING_UTF8);
lib.gsapi_set_stdio(inst, stdinCb, stdoutCb, stderrCb);
lib.gsapi_init_with_args(inst, argc, argv);
```

### `-dBATCH` returns `gs_error_Quit (-101)` — the interpreter is still alive

When `-dBATCH` is used without input files, `gsapi_init_with_args` returns `-101` (`gs_error_Quit`). This is normal and expected. **The interpreter is fully initialized (`init_done=2`) and ready for `gsapi_run_file` / `gsapi_run_string` calls.** Do NOT call `gsapi_exit()` at this point — that destroys the interpreter.

```java
int result = lib.gsapi_init_with_args(inst, argc, argv);
if (result == -101) {
    result = 0;  // BATCH quit is normal — interpreter still usable
}
// do NOT call gsapi_exit() here
```

Source path: `psi/imainarg.c` → `gs_main_init_with_args2()` returns `gs_error_Quit` when `run_start=false` (set by `-dBATCH`), after `gs_main_init2()` has fully initialized the interpreter.

### GS 9.50 SAFER mode blocks `gsapi_run_file` on user files

**`-dSAFER` is ON by default in GS 9.50+.** When `gsapi_run_file` is called after `gsapi_init_with_args` has returned, GS SAFER mode blocks reading of user files. The result is "Permission denied" from the OS → execute0 catches the PS error → `1 .quit` → `gsapi_run_file` returns `-100` with `pexit_code=1`.

The error chain: `gsapi_run_file` → `gs_main_run_file2` → `.runfile` PS operator → `execute0` → `1 .quit` → `-100`/`pexit_code=1`.

Note: passing the file inside `gsapi_init_with_args` (via `-f input.ps`) works fine even with SAFER enabled — the restriction applies specifically to **post-init** `gsapi_run_file` calls.

**Option 1 — keep SAFER, whitelist specific paths (preferred):** call `Ghostscript.addControlPath()` before `initialize()`.

GS path-control pattern matching rules (from `gpmisc.c` `validate()`):
- `/path/to/file` — permits exactly that file.
- `/path/to/dir/*` — permits all files directly inside the directory (one level). **Use this for directory access.**
- `/path/to/dir/` — permits files in *sub*directories but NOT direct children. Counterintuitive — prefer `dir/*`.
- `/path/prefix*` — permits anything whose path starts with that prefix.

```java
File file = new File("/abs/path/to/file.ps");
// Permit all files in the same directory (call BEFORE initialize):
gs.addControlPath(Ghostscript.PERMIT_FILE_READING, file.getParent() + "/*");

String[] args = { "-dQUIET", "-dNOPAUSE", "-dBATCH", "-dSAFER", "-sDEVICE=nullpage" };
gs.initialize(args);
gs.runFile(file.getAbsolutePath());
```

**Option 2 — disable SAFER entirely (quick but unsandboxed):** use `-dNOSAFER`:

```java
String[] args = { "-dQUIET", "-dNOPAUSE", "-dBATCH", "-dNOSAFER", "-sDEVICE=nullpage" };
gs.initialize(args);
gs.runFile("/abs/path/to/file.ps");
```

### JNA 5.x: callbacks must be held in strong (static) references

JNA 5.x uses a `WeakHashMap` to track native callbacks. If your callback object is only referenced by a local variable, it will be garbage-collected while GS is still active, causing a crash or silent failure.

**Fix**: Store all callbacks in `static` fields for the lifetime of the interpreter:

```java
// In Ghostscript.java:
private static GhostscriptLibrary.stdin_fn  nativeStdinCallback;
private static GhostscriptLibrary.stdout_fn nativeStdoutCallback;
private static GhostscriptLibrary.stderr_fn nativeStderrCallback;

// Assign before calling gsapi_set_stdio:
nativeStdinCallback  = stdinCallback;
nativeStdoutCallback = stdoutCallback;
nativeStderrCallback = stderrCallback;
lib.gsapi_set_stdio(inst, nativeStdinCallback, nativeStdoutCallback, nativeStderrCallback);
```

### Surefire stdout corruption on headless Linux

When GS writes its startup banner to **native stdout** (e.g. because `-dQUIET` was placed as `argv[0]` and therefore skipped), it corrupts Maven Surefire's binary test-result protocol. Surefire uses the forked JVM's stdout stream to parse test results — native GS output injected into that stream causes all subsequent tests in the same JVM fork to appear as failures with fabricated error messages.

**Symptoms**: Multiple tests reporting "Corrupted STDOUT by directly writing to native stream in forked JVM". The `.dumpstream` file shows `GPL Ghostscript 9.50 (2019-10-15)` mixed into the result stream.

**Fix**: Always set `gs.setStdOut(...)` and `gs.setStdErr(...)` **before** calling `gs.initialize()` to redirect GS output away from the process stdout. `Ghostscript.initialize()` defaults `null` stdout/stderr to `GhostscriptLoggerOutputStream` (SLF4J) — but explicitly setting captures is safer for tests.

### Headless Linux / WSL: always specify a device

When no `-sDEVICE=...` and no `-dNODISPLAY` is in the effective GS args, GS tries to open an X11 display. On headless WSL/Linux this calls `abort()` → SIGABRT → JVM crash. Maven Surefire reports "forked VM terminated without properly saying goodbye" with exit code 134.

**Fix**: Always include `-dNODISPLAY` or `-sDEVICE=<headless-device>` (e.g. `nullpage`, `pdfwrite`) as an effective GS arg (i.e. not in `argv[0]` position).

### `gsapi_exit` must be called after any fatal error

If `gsapi_init_with_args` returns `<= -100` (fatal), you must call `gsapi_exit()` before `gsapi_delete_instance()`. Skipping this is undefined behaviour.

```java
if (result <= -100) {
    lib.gsapi_exit(inst);  // mandatory cleanup
    throw new GhostscriptException("Init failed: " + result);
}
```

---

## Key Constraints to Remember

1. **One instance per process** — never create two simultaneously.
2. **Single-threaded API** — all `gsapi_*` calls from one thread.
3. **`gsapi_set_arg_encoding` first** — must precede `gsapi_set_stdio` and `gsapi_init_with_args` (GS 9.x).
4. **`argv[0]` is the program name and is skipped** — always use `"gs"` as `argv[0]` for direct calls; `Ghostscript.initialize()` adds it automatically.
5. **`gsapi_init_with_args` returning `-101` is NOT fatal** — `-dBATCH` causes this; the interpreter is alive and ready for `run_file`/`run_string`. Do not call `gsapi_exit()`.
6. **Always call `gsapi_exit()` before `gsapi_delete_instance()`** if `init_with_args` was called (including after fatal errors `<= -100`).
7. **SAFER mode (`-dSAFER`, default ON in GS 9.50+) blocks post-init `gsapi_run_file`** — use `addControlPath(PERMIT_FILE_READING, dir + "/*")` before init, or `-dNOSAFER` to disable the sandbox entirely.
8. **JNA 5.x callbacks need static references** — local callback objects get GC'd; store them in static fields.
9. **Capture stdout/stderr before `initialize()`** — prevent GS banner from corrupting Surefire or other stdout consumers.
10. **`gsapi_set_param` triggers `initgraphics`** — only safe at page start.
11. **64 KB buffer limit** on `gsapi_run_string*` — split large inputs.
12. **Return `<= -100` means fatal** — call `gsapi_exit()` and delete immediately.
13. **`gs_error_Quit (-101)` and `gs_error_NeedInput` are not errors** — check for them explicitly before treating a negative return as a failure.
