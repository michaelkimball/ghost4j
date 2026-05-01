## Version 1.1.0

- Migrated build system from Maven to Gradle 9.5.0.
- Migrated test framework from JUnit 3 to JUnit 6 Jupiter (48 tests).
- Migrated iText dependency from 2.x to 9.6.0 with updated PDF manipulation API.
- Upgraded all dependencies: JNA 5.18.1, SLF4J 2.0.17, Commons BeanUtils 1.11.0, xmlgraphics-commons 2.11.
- Added Spotless code formatting (Google Java Format 1.27.0 AOSP style).
- Published under new artifact coordinates: `io.github.michaelkimball:ghost4j`.

## Version 1.0.2

- Multi-threading support for JBoss EAP 6.x and JBoss 7.x (Thanks [HighTechBull](https://github.com/HighTechBull)).

## Version 1.0.1 — 14/03/2016

- Upgraded JNA to 4.1.0.

## Version 1.0.0 — 16/06/2015

- Artifact is now available from the Central Maven Repository.

## Version 0.5.1 — 27/11/2013

- Fixed bug with antialiasing in `SimpleRenderer` (Thanks [squallssck](https://github.com/squallssck)).
- Fixed default autorotation of pages behavior in `PDFConverter`: by default no autorotate parameter is set anymore (Thanks [jmkgreen](https://github.com/jmkgreen)).

## Version 0.5.0 — 25/01/2013

- Added `antialiasing` property to `SimpleRenderer`.
- Added `extract` method on `Document` — extract a range of pages into a new document.
- Added `append` method on `Document` — append another document to the current one.
- Added `explode` method on `Document` — build one document per page.
- Upgraded xmlgraphics-commons to 1.4.
- Upgraded jna to 3.3.0.
- Added `PaperSize` class.
- Added `device` property to `PSConverter`; defaults to `ps2write` if available.
- Added `SafeAppenderModifier` — safely appends documents of mixed types.
- Added `InkAnalyzer` — retrieves CMYK ink coverage per page.
- Added examples in `org.ghost4j.example` package.

## Version 0.4.6 — 30/12/2012

- Renamed package from `net.sf.ghost4j` to `org.ghost4j`.

## Version 0.4.5 — 15/02/2012

- Fixed bug with `PSDocument.getPageCount()` (Thanks Jörg!).
- Fixed bug in `Renderer` on 64-bit architectures.

## Version 0.4.4 — 26/11/2011

- Added `paperSize` property to `PDFConverter` and `PSConverter`.
- Support for 64-bit architecture on Windows.

## Version 0.4.3 — 01/06/2011

- Added `ghost4j.encoding` system property support.

## Version 0.4.2 — 18/03/2011

- Fixed multi-process support when running from a Servlet container on Windows.

## Version 0.4.1 — 17/03/2011

- Fixed multi-process support when running from a Servlet container (Thanks Michael!).

## Version 0.4.0 — 02/12/2010

- High-level API with multi-process support.
- `PDFConverter`: convert PostScript or PDF to PDF.
- `PSConverter`: convert PostScript or PDF to PostScript.
- `FontAnalyzer`: analyze fonts in a PDF document.
- `SimpleRenderer`: render a PostScript or PDF document as images.

## Version 0.3.3 — 04/09/2010

- Fixed display callback compatibility for old Ghostscript versions.

## Version 0.3.2 — 03/14/2010

- Added Log4J support for Ghostscript stdout/stderr output.

## Version 0.3 — 07/26/2009

- Added display callback support for Ghostscript raster output.
- Fixed `Ghostscript.exit()` — was not calling the native exit function (caused output files to be incomplete or undeletable).
- Added code samples in `net.sf.ghost4j.example` package.
