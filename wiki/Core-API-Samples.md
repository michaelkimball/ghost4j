## Convert PostScript to PDF

```java
Ghostscript gs = Ghostscript.getInstance();

String[] gsArgs = {
    "-ps2pdf",
    "-dNOPAUSE",
    "-dBATCH",
    "-dSAFER",
    "-sDEVICE=pdfwrite",
    "-sOutputFile=output.pdf",
    "-c", ".setpdfwrite",
    "-f", "input.ps"
};

try {
    gs.initialize(gsArgs);
    gs.exit();
} catch (GhostscriptException e) {
    System.out.println("ERROR: " + e.getMessage());
}
```

## Display Callback

```java
Ghostscript gs = Ghostscript.getInstance();

ImageWriterDisplayCallback displayCallback = new ImageWriterDisplayCallback();
gs.setDisplayCallback(displayCallback);

String[] gsArgs = {
    "-dQUIET",
    "-dNOPAUSE",
    "-dBATCH",
    "-dSAFER",
    "-sDEVICE=display",
    "-sDisplayHandle=0",
    "-dDisplayFormat=16#804"
};

try {
    gs.initialize(gsArgs);
    gs.runFile("input.ps");
    gs.exit();
} catch (GhostscriptException e) {
    System.out.println("ERROR: " + e.getMessage());
}

// Write captured pages to disk as PNG
for (int i = 0; i < displayCallback.getImages().size(); i++) {
    ImageIO.write((RenderedImage) displayCallback.getImages().get(i), "png", new File((i + 1) + ".png"));
}
```
