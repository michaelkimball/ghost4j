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
    synchronized (gs) {
        gs.initialize(gsArgs);
        gs.exit();
    }
} catch (GhostscriptException e) {
    System.out.println("ERROR: " + e.getMessage());
} finally {
    try {
        Ghostscript.deleteInstance();
    } catch (GhostscriptException e) {
        // ignore
    }
}
```

## Display Callback

`ImageWriterDisplayCallback` captures each rendered page as a `java.awt.Image`.

```java
ImageWriterDisplayCallback displayCallback = new ImageWriterDisplayCallback();

Ghostscript gs = Ghostscript.getInstance();
gs.setDisplayCallback(displayCallback);

String[] gsArgs = {
    "-dQUIET",
    "-dNOPAUSE",
    "-dBATCH",
    "-dSAFER",
    "-sDEVICE=display",
    "-dDisplayHandle=0",
    "-dDisplayFormat=16#804"
};

try {
    synchronized (gs) {
        gs.initialize(gsArgs);
        gs.runFile("input.ps");
        gs.exit();
    }
} catch (GhostscriptException e) {
    System.out.println("ERROR: " + e.getMessage());
} finally {
    try {
        Ghostscript.deleteInstance();
    } catch (GhostscriptException e) {
        // ignore
    }
}

// Write captured pages to disk as PNG
List<Image> images = displayCallback.getImages();
for (int i = 0; i < images.size(); i++) {
    ImageIO.write((RenderedImage) images.get(i), "png", new File((i + 1) + ".png"));
}
```

### Custom Display Callback

To implement your own callback, implement `org.ghost4j.display.DisplayCallback`:

```java
public class MyDisplayCallback implements DisplayCallback {

    public void displayOpen() throws GhostscriptException {}
    public void displayPreClose() throws GhostscriptException {}
    public void displayClose() throws GhostscriptException {}
    public void displayPreSize(int width, int height, int raster, int format) throws GhostscriptException {}
    public void displaySize(int width, int height, int raster, int format) throws GhostscriptException {}
    public void displaySync() throws GhostscriptException {}
    public void displayUpdate(int x, int y, int width, int height) throws GhostscriptException {}

    public void displayPage(int width, int height, int raster, int format,
                            int copies, int flush, byte[] imageData) throws GhostscriptException {
        // each call = one rendered page
        // imageData contains raw pixel bytes in the format specified by dDisplayFormat
    }
}
```
