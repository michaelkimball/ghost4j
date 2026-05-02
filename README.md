# Ghost4j - Unofficial Ghostscript Java bindings

Ghost4J binds the Ghostscript C API to bring Ghostscript power to the Java world.
It also provides a high-level API to handle PDF and Postscript documents with objects.

### Gradle configuration

```kotlin
dependencies {
    implementation("io.github.michaelkimball:ghost4j:1.2.0")
}
```

### Maven configuration

```xml
<dependency>
    <groupId>io.github.michaelkimball</groupId>
    <artifactId>ghost4j</artifactId>
    <version>1.2.0</version>
</dependency>
```

### A simple example (PS to PDF conversion)

	//load PostScript document
	PSDocument document = new PSDocument();
	document.load(new File("input.ps"));
	
	//create OutputStream
	fos = new FileOutputStream(new File("rendition.pdf"));
	
	//create converter
	PDFConverter converter = new PDFConverter();
	
	//set options
	converter.setPDFSettings(PDFConverter.OPTION_PDFSETTINGS_PREPRESS);
	
	//convert
	converter.convert(document, fos);

### Documentation

Documentation is available on the [GitHub wiki](https://github.com/michaelkimball/ghost4j/wiki)
