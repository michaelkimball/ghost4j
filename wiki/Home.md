Ghost4J binds the Ghostscript C API to bring Ghostscript power to the Java world.
It also provides a high-level API to handle PDF and PostScript documents with objects.

## Quick Example — PS to PDF

```java
// load PostScript document
PSDocument document = new PSDocument();
document.load(new File("input.ps"));

// create OutputStream
FileOutputStream fos = new FileOutputStream(new File("rendition.pdf"));

// create converter
PDFConverter converter = new PDFConverter();

// set options
converter.setPDFSettings(PDFConverter.OPTION_PDFSETTINGS_PREPRESS);

// convert
converter.convert(document, fos);
```

## Gradle / Maven

```kotlin
// Gradle (build.gradle.kts)
implementation("io.github.michaelkimball:ghost4j:1.1.0")
```

```xml
<!-- Maven (pom.xml) -->
<dependency>
    <groupId>io.github.michaelkimball</groupId>
    <artifactId>ghost4j</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Where to Go Next

- Interested in PS/PDF conversion or rendering? → [[High-Level-API-Samples]]
- Already know Ghostscript and want advanced control? → [[Core-API-Samples]]
- Running Ghost4J in a web server or concurrent environment? → [[Thread-Safety-and-Multi-Threading]]
