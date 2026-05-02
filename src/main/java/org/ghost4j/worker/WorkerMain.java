/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.beanutils.BeanUtils;
import org.ghost4j.analyzer.AbstractRemoteAnalyzer;
import org.ghost4j.analyzer.AnalysisItem;
import org.ghost4j.analyzer.AnalyzerException;
import org.ghost4j.converter.AbstractRemoteConverter;
import org.ghost4j.converter.ConverterException;
import org.ghost4j.display.PageRaster;
import org.ghost4j.document.Document;
import org.ghost4j.document.DocumentException;
import org.ghost4j.document.PDFDocument;
import org.ghost4j.document.PSDocument;
import org.ghost4j.modifier.AbstractRemoteModifier;
import org.ghost4j.modifier.ModifierException;
import org.ghost4j.renderer.AbstractRemoteRenderer;
import org.ghost4j.renderer.RendererException;
import org.ghost4j.worker.protocol.ControlFrame;
import org.ghost4j.worker.protocol.Frame;
import org.ghost4j.worker.protocol.FrameCodec;
import org.ghost4j.worker.protocol.RequestFrame;
import org.ghost4j.worker.protocol.ResponseErrFrame;
import org.ghost4j.worker.protocol.ResponseOkFrame;

/**
 * Entry point for worker child JVMs. Every worker process starts here. The class:
 *
 * <ol>
 *   <li>Binds a Unix Domain Socket server at the path supplied via the {@code
 *       ghost4j.worker.socket} system property.
 *   <li>Writes {@code 0x01} to stdout to signal the parent that the socket is ready.
 *   <li>Accepts the parent's connection.
 *   <li>Loops, reading {@link Frame}s and processing each {@link RequestFrame} by instantiating the
 *       component, applying settings, and calling the appropriate {@code run()} method.
 *   <li>Exits cleanly on receiving a {@link ControlFrame#SHUTDOWN} frame.
 * </ol>
 *
 * <p>WorkerMain dispatches by casting the component instance to one of:
 *
 * <ul>
 *   <li>{@link AbstractRemoteConverter} — calls {@code run(doc, baos)}; result is raw bytes
 *   <li>{@link AbstractRemoteAnalyzer} — calls {@code analyze(doc)}; result JSON-encoded via {@link
 *       AbstractRemoteAnalyzer#serializeItems}
 *   <li>{@link AbstractRemoteRenderer} — extracts {@code _pageBegin}/{@code _pageEnd} from
 *       settings, calls {@code run(doc, begin, end)}; result binary-encoded via {@link
 *       AbstractRemoteRenderer#serializeRasters}
 *   <li>{@link AbstractRemoteModifier} — extracts {@code _param_*} keys from settings via {@link
 *       AbstractRemoteModifier#extractParameters}, calls {@code modify(doc, params)}; result
 *       encoded via {@link AbstractRemoteModifier#encodeResult}
 * </ul>
 */
public class WorkerMain {

    private WorkerMain() {}

    /**
     * Main entry point for the worker child JVM.
     *
     * @param args unused; configuration is supplied via system properties
     * @throws Exception if startup fails fatally
     */
    public static void main(String[] args) throws Exception {
        String socketPathStr = System.getProperty("ghost4j.worker.socket");
        String componentClassName = System.getProperty("ghost4j.worker.class");

        if (socketPathStr == null || socketPathStr.isBlank()) {
            throw new IllegalStateException("System property ghost4j.worker.socket is not set");
        }
        if (componentClassName == null || componentClassName.isBlank()) {
            throw new IllegalStateException("System property ghost4j.worker.class is not set");
        }

        Path socketPath = Path.of(socketPathStr);

        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));

        WorkerProcess.setSocketPermissions(socketPath);

        System.out.write(0x01);
        System.out.flush();

        SocketChannel conn = serverChannel.accept();

        InputStream in = Channels.newInputStream(conn);
        OutputStream out = Channels.newOutputStream(conn);

        try {
            while (true) {
                Frame frame = FrameCodec.readFrame(in);

                if (frame instanceof ControlFrame cf) {
                    if (cf.type() == ControlFrame.SHUTDOWN) {
                        break;
                    }
                    if (cf.type() == ControlFrame.PING) {
                        FrameCodec.writeFrame(out, new ControlFrame(ControlFrame.PONG));
                        continue;
                    }
                    continue;
                }

                if (frame instanceof RequestFrame req) {
                    processRequest(req, out, componentClassName);
                }
            }
        } finally {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Processes a single {@link RequestFrame}: reconstructs the document, instantiates and
     * configures the component, invokes it, and writes the response frame.
     */
    private static void processRequest(
            RequestFrame req, OutputStream out, String componentClassName) throws IOException {
        try {
            Document document = buildDocument(req.docType(), req.documentBytes());

            Class<?> componentClass = Class.forName(componentClassName);
            Object component = componentClass.getDeclaredConstructor().newInstance();

            // Mutable copy so renderer/modifier extraction can modify in place.
            Map<String, Object> settings =
                    new HashMap<>(req.settings() != null ? req.settings() : Collections.emptyMap());

            byte[] result = invokeComponent(component, document, settings);

            FrameCodec.writeFrame(out, new ResponseOkFrame(req.operationId(), result));

        } catch (Exception e) {
            String errorClass = e.getClass().getName();
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            FrameCodec.writeFrame(
                    out, new ResponseErrFrame(req.operationId(), errorClass, errorMessage));
        }
    }

    /**
     * Dispatches the component invocation based on which {@code AbstractRemote*} type the component
     * is an instance of.
     *
     * <p>Settings are applied to the component via BeanUtils after any reserved keys have been
     * extracted (page range for renderers, parameter prefix for modifiers).
     */
    private static byte[] invokeComponent(
            Object component, Document document, Map<String, Object> settings)
            throws IOException,
                    ConverterException,
                    AnalyzerException,
                    RendererException,
                    ModifierException,
                    DocumentException,
                    IllegalAccessException,
                    InvocationTargetException {

        if (component instanceof AbstractRemoteConverter converter) {
            applySettings(converter, settings);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // maxProcessCount defaults to 0 in fresh instance → calls run() directly
            converter.convert(document, baos);
            return baos.toByteArray();

        } else if (component instanceof AbstractRemoteAnalyzer analyzer) {
            applySettings(analyzer, settings);
            // maxProcessCount=0 → calls run() directly
            List<AnalysisItem> items = analyzer.analyze(document);
            return AbstractRemoteAnalyzer.serializeItems(items);

        } else if (component instanceof AbstractRemoteRenderer renderer) {
            // Extract page range before applying settings; _pageBegin/_pageEnd are not bean
            // properties and would cause BeanUtils to throw or silently fail.
            Object beginObj = settings.remove(AbstractRemoteRenderer.SETTINGS_KEY_PAGE_BEGIN);
            Object endObj = settings.remove(AbstractRemoteRenderer.SETTINGS_KEY_PAGE_END);
            int begin = (beginObj instanceof Number n) ? n.intValue() : 0;
            int end = (endObj instanceof Number n) ? n.intValue() : document.getPageCount() - 1;
            applySettings(renderer, settings);
            // run() is public in AbstractRemoteRenderer — returns List<PageRaster> directly.
            List<PageRaster> rasters = renderer.run(document, begin, end);
            return AbstractRemoteRenderer.serializeRasters(rasters);

        } else if (component instanceof AbstractRemoteModifier modifier) {
            // Extract _param_* keys before applying settings to the component.
            Map<String, Serializable> params = AbstractRemoteModifier.extractParameters(settings);
            applySettings(modifier, settings);
            // maxProcessCount=0 → calls run(source, params) directly
            Document result = modifier.modify(document, params);
            return AbstractRemoteModifier.encodeResult(result);

        } else {
            throw new IOException(
                    "Component "
                            + component.getClass().getName()
                            + " does not extend AbstractRemoteConverter, AbstractRemoteAnalyzer,"
                            + " AbstractRemoteRenderer, or AbstractRemoteModifier");
        }
    }

    /** Applies settings to a component instance using BeanUtils reflection. */
    private static void applySettings(Object component, Map<String, Object> settings)
            throws IllegalAccessException, InvocationTargetException {
        if (settings != null && !settings.isEmpty()) {
            BeanUtils.populate(component, settings);
        }
    }

    /**
     * Reconstructs a {@link Document} from raw bytes and the doc-type byte.
     *
     * @param docType {@code 0x00} for PDF, {@code 0x01} for PostScript
     * @param bytes the raw document bytes
     * @return a loaded document instance
     * @throws IOException if document loading fails
     * @throws DocumentException if the bytes do not form a valid document
     */
    private static Document buildDocument(byte docType, byte[] bytes)
            throws IOException, DocumentException {
        Document doc;
        if (docType == RequestFrame.DOC_PDF) {
            doc = new PDFDocument();
        } else if (docType == RequestFrame.DOC_PS) {
            doc = new PSDocument();
        } else {
            throw new IOException(
                    "Unknown document type byte: 0x" + Integer.toHexString(docType & 0xFF));
        }
        doc.load(new ByteArrayInputStream(bytes));
        return doc;
    }
}
