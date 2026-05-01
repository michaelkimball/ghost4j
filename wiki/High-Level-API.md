The high-level API (available since version 0.4.0) makes document handling with Ghostscript easier by working with objects instead of raw interpreter arguments.

It is composed of:

- **Documents** — objects representing a PostScript or PDF file
- **Components** — processing units (Analyzers, Converters, Modifiers, Renderers) that accept documents as input

## Documents

Implemented via the `org.ghost4j.document.Document` interface.

| Class | Description |
|---|---|
| `org.ghost4j.document.PSDocument` | Handles PostScript documents |
| `org.ghost4j.document.PDFDocument` | Handles PDF documents |

## Analyzers

Extract data from documents. Interface: `org.ghost4j.analyzer.Analyzer`.

| Class | Description | PostScript | PDF |
|---|---|---|---|
| `org.ghost4j.analyzer.FontAnalyzer` | Extracts font names and embedding status | No | Yes |
| `org.ghost4j.analyzer.InkAnalyzer` | Extracts CMYK ink coverage per page (%) | Yes | Yes |

## Converters

Convert documents to a file format. Interface: `org.ghost4j.converter.Converter`.

| Class | Description | PostScript | PDF |
|---|---|---|---|
| `org.ghost4j.converter.PSConverter` | Converts to PostScript | Yes | Yes |
| `org.ghost4j.converter.PDFConverter` | Converts to PDF | Yes | Yes |

## Modifiers

Modify documents in place. Interface: `org.ghost4j.modifier.Modifier`.

| Class | Description | PostScript | PDF |
|---|---|---|---|
| `org.ghost4j.modifier.SafeAppenderModifier` | Appends one document to another (types can be mixed) | Yes | Yes |

## Renderers

Render document pages to images. Interface: `org.ghost4j.renderer.Renderer`.

| Class | Description | PostScript | PDF |
|---|---|---|---|
| `org.ghost4j.renderer.SimpleRenderer` | Renders a range of pages as images | Yes | Yes |

## Multi-Threading

All high-level components support multi-process execution via the `maxProcessCount` property. See [[Thread-Safety-and-Multi-Threading]] for details.

For code examples, see [[High-Level-API-Samples]].
