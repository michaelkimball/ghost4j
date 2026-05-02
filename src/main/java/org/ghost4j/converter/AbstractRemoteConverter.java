/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.converter;

import java.io.IOException;
import java.io.OutputStream;
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
 * Abstract remote converter. Replaces Cajo/JavaFork dispatch with UDS worker-pool dispatch.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public abstract class AbstractRemoteConverter extends AbstractRemoteComponent
        implements RemoteConverter {

    protected abstract void run(Document document, OutputStream outputStream)
            throws IOException, ConverterException, DocumentException;

    public void convert(Document document, OutputStream outputStream)
            throws IOException, ConverterException, DocumentException {

        if (maxProcessCount == 0) {
            run(document, outputStream);
            return;
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
                throw new ConverterException("Failed to extract component settings", e);
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
                throw new WorkerCrashedException("Worker crashed during conversion", e);
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
                outputStream.write(ok.result());
            } else if (response instanceof ResponseErrFrame err) {
                throw new ConverterException(err.errorClass() + ": " + err.errorMessage());
            }

        } finally {
            pool.release(worker);
            MDC.remove("ghost4j.operationId");
            MDC.remove("ghost4j.component");
            MDC.remove("ghost4j.workerId");
        }
    }
}
