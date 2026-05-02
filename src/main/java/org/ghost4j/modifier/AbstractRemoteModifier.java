/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.modifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.ghost4j.AbstractRemoteComponent;
import org.ghost4j.document.Document;
import org.ghost4j.document.DocumentException;
import org.ghost4j.document.PDFDocument;
import org.ghost4j.document.PSDocument;
import org.ghost4j.worker.WorkerCrashedException;
import org.ghost4j.worker.WorkerPool;
import org.ghost4j.worker.WorkerProcess;
import org.ghost4j.worker.protocol.Frame;
import org.ghost4j.worker.protocol.RequestFrame;
import org.ghost4j.worker.protocol.ResponseErrFrame;
import org.ghost4j.worker.protocol.ResponseOkFrame;
import org.slf4j.MDC;

/**
 * Abstract remote modifier. Replaces Cajo/JavaFork dispatch with UDS worker-pool dispatch.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public abstract class AbstractRemoteModifier extends AbstractRemoteComponent
        implements RemoteModifier {

    /** Settings-key prefix for modifier parameters passed to {@link #modify}. */
    public static final String PARAM_KEY_PREFIX = "_param_";

    /**
     * Prefix for the encoded form of a {@link Document} parameter. Stored as a JSON-safe string:
     * {@code __DOC__:PDF:<base64>} or {@code __DOC__:PS:<base64>}.
     */
    private static final String DOC_PARAM_PREFIX = "__DOC__:";

    protected abstract Document run(Document source, Map<String, Serializable> parameters)
            throws ModifierException, DocumentException, IOException;

    public Document modify(Document source, Map<String, Serializable> parameters)
            throws ModifierException, DocumentException, IOException {

        if (maxProcessCount == 0) {
            return run(source, parameters);
        }

        WorkerPool pool = getOrCreatePool(maxProcessCount);
        WorkerProcess worker = pool.acquire();
        int opId = operationCounter.getAndIncrement();

        MDC.put("ghost4j.operationId", Integer.toHexString(opId));
        MDC.put("ghost4j.component", this.getClass().getSimpleName());
        MDC.put("ghost4j.workerId", worker.id().toString());

        try {
            byte docType =
                    (source instanceof PDFDocument) ? RequestFrame.DOC_PDF : RequestFrame.DOC_PS;

            Map<String, Object> settings;
            try {
                settings = extractSettings();
            } catch (Exception e) {
                throw new ModifierException("Failed to extract component settings", e);
            }

            if (parameters != null) {
                for (Map.Entry<String, Serializable> entry : parameters.entrySet()) {
                    Serializable val = entry.getValue();
                    if (val instanceof Document doc) {
                        // Encode Document as a wire-safe string: __DOC__:<type>:<base64 content>
                        String docTypeStr = (doc instanceof PDFDocument) ? "PDF" : "PS";
                        String encoded =
                                DOC_PARAM_PREFIX
                                        + docTypeStr
                                        + ":"
                                        + Base64.getEncoder().encodeToString(doc.getContent());
                        settings.put(PARAM_KEY_PREFIX + entry.getKey(), encoded);
                    } else {
                        settings.put(PARAM_KEY_PREFIX + entry.getKey(), val);
                    }
                }
            }

            long t0 = System.nanoTime();
            RequestFrame req = new RequestFrame(opId, docType, settings, source.getContent());

            Frame response;
            try {
                worker.send(req);
                response = worker.receive();
            } catch (IOException e) {
                if (worker.crashed().compareAndSet(false, true)) {
                    pool.replaceWorker(worker);
                }
                throw new WorkerCrashedException("Worker crashed during modification", e);
            }

            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.debug(
                    "op={} component={} docBytes={} durationMs={}",
                    Integer.toHexString(opId),
                    this.getClass().getSimpleName(),
                    source.getContent().length,
                    elapsedMs);
            if (elapsedMs > 30_000L) {
                log.warn(
                        "Slow GS request: op={} durationMs={}",
                        Integer.toHexString(opId),
                        elapsedMs);
            }

            if (response instanceof ResponseOkFrame ok) {
                worker.requestsHandled().incrementAndGet();
                try {
                    return decodeResult(ok.result());
                } catch (IOException e) {
                    throw new ModifierException(
                            "Failed to decode modified document from worker", e);
                }
            } else if (response instanceof ResponseErrFrame err) {
                throw new ModifierException(err.errorClass() + ": " + err.errorMessage());
            }

            throw new ModifierException("Unexpected frame type from worker: " + response);

        } finally {
            pool.release(worker);
            MDC.remove("ghost4j.operationId");
            MDC.remove("ghost4j.component");
            MDC.remove("ghost4j.workerId");
        }
    }

    public static byte[] encodeResult(Document doc) throws IOException {
        byte[] content = doc.getContent();
        byte docTypeByte =
                (doc instanceof PDFDocument) ? RequestFrame.DOC_PDF : RequestFrame.DOC_PS;
        byte[] encoded = new byte[1 + 4 + content.length];
        encoded[0] = docTypeByte;
        encoded[1] = (byte) ((content.length >>> 24) & 0xFF);
        encoded[2] = (byte) ((content.length >>> 16) & 0xFF);
        encoded[3] = (byte) ((content.length >>> 8) & 0xFF);
        encoded[4] = (byte) (content.length & 0xFF);
        System.arraycopy(content, 0, encoded, 5, content.length);
        return encoded;
    }

    public static Document decodeResult(byte[] bytes) throws IOException {
        if (bytes.length < 5) {
            throw new IOException("Modifier result too short: " + bytes.length + " bytes");
        }
        byte docTypeByte = bytes[0];
        int contentLen =
                ((bytes[1] & 0xFF) << 24)
                        | ((bytes[2] & 0xFF) << 16)
                        | ((bytes[3] & 0xFF) << 8)
                        | (bytes[4] & 0xFF);
        if (bytes.length < 5 + contentLen) {
            throw new IOException(
                    "Modifier result truncated: expected "
                            + (5 + contentLen)
                            + " bytes, got "
                            + bytes.length);
        }
        byte[] content = new byte[contentLen];
        System.arraycopy(bytes, 5, content, 0, contentLen);

        if (docTypeByte == RequestFrame.DOC_PDF) {
            PDFDocument doc = new PDFDocument();
            doc.load(new ByteArrayInputStream(content));
            return doc;
        } else if (docTypeByte == RequestFrame.DOC_PS) {
            PSDocument doc = new PSDocument();
            doc.load(new ByteArrayInputStream(content));
            return doc;
        } else {
            throw new IOException("Unknown document type byte in modifier result: " + docTypeByte);
        }
    }

    public static Map<String, Serializable> extractParameters(Map<String, Object> settings) {
        Map<String, Serializable> parameters = new HashMap<>();
        settings.entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getKey().startsWith(PARAM_KEY_PREFIX)) {
                                String paramKey =
                                        entry.getKey().substring(PARAM_KEY_PREFIX.length());
                                Object val = entry.getValue();
                                if (val instanceof String s && s.startsWith(DOC_PARAM_PREFIX)) {
                                    // Decode Document from __DOC__:<type>:<base64> wire encoding.
                                    String rest = s.substring(DOC_PARAM_PREFIX.length());
                                    int colon = rest.indexOf(':');
                                    if (colon >= 0) {
                                        String docTypeStr = rest.substring(0, colon);
                                        byte[] content =
                                                Base64.getDecoder()
                                                        .decode(rest.substring(colon + 1));
                                        try {
                                            Document doc =
                                                    "PDF".equals(docTypeStr)
                                                            ? new PDFDocument()
                                                            : new PSDocument();
                                            doc.load(new ByteArrayInputStream(content));
                                            parameters.put(paramKey, (Serializable) doc);
                                        } catch (IOException e) {
                                            // Leave parameter absent; run() will throw clear error.
                                        }
                                    }
                                } else if (val instanceof Serializable sv) {
                                    parameters.put(paramKey, sv);
                                }
                                return true;
                            }
                            return false;
                        });
        return parameters;
    }
}
