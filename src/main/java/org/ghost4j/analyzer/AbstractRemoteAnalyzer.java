/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.ghost4j.AbstractRemoteComponent;
import org.ghost4j.document.Document;
import org.ghost4j.document.DocumentException;
import org.ghost4j.document.PDFDocument;
import org.ghost4j.worker.WorkerCrashedException;
import org.ghost4j.worker.WorkerPool;
import org.ghost4j.worker.WorkerProcess;
import org.ghost4j.worker.protocol.Frame;
import org.ghost4j.worker.protocol.RequestFrame;
import org.ghost4j.worker.protocol.ResponseErrFrame;
import org.ghost4j.worker.protocol.ResponseOkFrame;
import org.slf4j.MDC;

/**
 * Abstract remote analyzer. Replaces Cajo/JavaFork dispatch with UDS worker-pool dispatch.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public abstract class AbstractRemoteAnalyzer extends AbstractRemoteComponent
        implements RemoteAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected abstract List<AnalysisItem> run(Document document)
            throws IOException, AnalyzerException, DocumentException;

    public List<AnalysisItem> analyze(Document document)
            throws IOException, AnalyzerException, DocumentException {

        if (maxProcessCount == 0) {
            return run(document);
        }

        WorkerPool pool = getOrCreatePool(maxProcessCount);
        WorkerProcess worker = pool.acquire();
        int opId = operationCounter.getAndIncrement();

        MDC.put("ghost4j.operationId", Integer.toHexString(opId));
        MDC.put("ghost4j.component", this.getClass().getSimpleName());
        MDC.put("ghost4j.workerId", worker.id().toString());

        try {
            byte docType =
                    (document instanceof PDFDocument) ? RequestFrame.DOC_PDF : RequestFrame.DOC_PS;

            Map<String, Object> settings;
            try {
                settings = extractSettings();
            } catch (Exception e) {
                throw new AnalyzerException("Failed to extract component settings", e);
            }

            long t0 = System.nanoTime();
            RequestFrame req = new RequestFrame(opId, docType, settings, document.getContent());

            Frame response;
            try {
                worker.send(req);
                response = worker.receive();
            } catch (IOException e) {
                if (worker.crashed().compareAndSet(false, true)) {
                    pool.replaceWorker(worker);
                }
                throw new WorkerCrashedException("Worker crashed during analysis", e);
            }

            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.debug(
                    "op={} component={} docBytes={} durationMs={}",
                    Integer.toHexString(opId),
                    this.getClass().getSimpleName(),
                    document.getContent().length,
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
                    return deserializeItems(ok.result());
                } catch (IOException e) {
                    throw new AnalyzerException(
                            "Failed to deserialize analysis result from worker", e);
                }
            } else if (response instanceof ResponseErrFrame err) {
                throw new AnalyzerException(err.errorClass() + ": " + err.errorMessage());
            }

            throw new AnalyzerException("Unexpected frame type from worker: " + response);

        } finally {
            pool.release(worker);
            MDC.remove("ghost4j.operationId");
            MDC.remove("ghost4j.component");
            MDC.remove("ghost4j.workerId");
        }
    }

    public static byte[] serializeItems(List<AnalysisItem> items) throws IOException {
        ArrayNode arr = MAPPER.createArrayNode();
        for (AnalysisItem item : items) {
            ObjectNode node = MAPPER.valueToTree(item);
            node.put("@class", item.getClass().getName());
            arr.add(node);
        }
        return MAPPER.writeValueAsBytes(arr);
    }

    public static List<AnalysisItem> deserializeItems(byte[] bytes) throws IOException {
        JsonNode arr = MAPPER.readTree(bytes);
        List<AnalysisItem> result = new ArrayList<>();
        for (JsonNode node : arr) {
            JsonNode classNode = node.get("@class");
            if (classNode == null || classNode.isNull()) {
                throw new IOException(
                        "Analysis result item is missing required '@class' discriminator");
            }
            String className = classNode.asText();
            Class<?> cls;
            try {
                cls = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IOException("Unknown analysis item type: " + className, e);
            }
            if (!AnalysisItem.class.isAssignableFrom(cls)) {
                throw new IOException(
                        "Refusing to deserialize non-AnalysisItem type: " + className);
            }
            // Remove @class before deserialization — it is not a known property of the subtype.
            ((ObjectNode) node).remove("@class");
            result.add((AnalysisItem) MAPPER.treeToValue(node, cls));
        }
        return result;
    }
}
