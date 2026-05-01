## Convert PostScript to PDF

```java
PSDocument document = new PSDocument();
document.load(new File("input.ps"));

FileOutputStream fos = new FileOutputStream(new File("rendition.pdf"));

PDFConverter converter = new PDFConverter();
converter.setPDFSettings(PDFConverter.OPTION_PDFSETTINGS_PREPRESS);
converter.convert(document, fos);
```

## Count Pages of a PostScript Document

```java
PSDocument psDocument = new PSDocument();
psDocument.load(new File("input.ps"));
System.out.println("Page count: " + psDocument.getPageCount());
```

## List Fonts of a PDF Document

```java
PDFDocument document = new PDFDocument();
document.load(new File("input.pdf"));

FontAnalyzer analyzer = new FontAnalyzer();
List<AnalysisItem> fonts = analyzer.analyze(document);

for (AnalysisItem item : fonts) {
    System.out.println(item);
}
```

## Render a PDF Document to Images

```java
PDFDocument document = new PDFDocument();
document.load(new File("input.pdf"));

SimpleRenderer renderer = new SimpleRenderer();
renderer.setResolution(300);

List<Image> images = renderer.render(document);

for (int i = 0; i < images.size(); i++) {
    ImageIO.write((RenderedImage) images.get(i), "png", new File((i + 1) + ".png"));
}
```

## Append a PDF to a PostScript Document

```java
PSDocument psDocument = new PSDocument();
psDocument.load(new File("input.ps"));

PDFDocument pdfDocument = new PDFDocument();
pdfDocument.load(new File("input.pdf"));

SafeAppenderModifier modifier = new SafeAppenderModifier();

Map<String, Serializable> parameters = new HashMap<>();
parameters.put(SafeAppenderModifier.PARAMETER_APPEND_DOCUMENT, pdfDocument);

Document result = modifier.modify(psDocument, parameters);
result.write(new File("merged.ps"));
```

## Analyze Ink Coverage of a PostScript Document

```java
PSDocument document = new PSDocument();
document.load(new File("input-2pages.ps"));

InkAnalyzer analyzer = new InkAnalyzer();
List<AnalysisItem> coverageData = analyzer.analyze(document);

for (AnalysisItem item : coverageData) {
    System.out.println(item);
}
```
