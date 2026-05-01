The Core API is the lowest level of the library, allowing direct interaction with the Ghostscript interpreter.

It is composed of:

- **`GhostscriptLibrary` interface** — the JNA interface with all native Ghostscript API bindings. Never use this directly; always go through the `Ghostscript` class.
- **`Ghostscript` singleton class** — the object used to control the Ghostscript interpreter.
- **`org.ghost4j.display` package** — classes for writing custom display callbacks.

## Ghostscript Singleton

The native C Ghostscript API allows only one interpreter instance per native system process. Accordingly, `Ghostscript` is a singleton in Ghost4J.

Obtain the instance with `Ghostscript.getInstance()` — it is created lazily on first call.

When done, call `Ghostscript.deleteInstance()` to free the native interpreter. This is recommended to ensure a clean instance is returned on the next call.

In multi-threaded environments, always access the interpreter synchronously. See [[Thread-Safety-and-Multi-Threading]].

## Display Callbacks

Display callbacks let you interact with Ghostscript's raster output.

1. Implement the `DisplayCallback` interface.
2. Bind it via `gs.setDisplayCallback(callback)` **before** initializing the interpreter.
3. Initialize with the parameters: `-sDEVICE=display`, `-dDisplayHandle=0`, `-dDisplayFormat=16#804` (see Ghostscript docs for other formats).

For code examples, see [[Core-API-Samples]].
