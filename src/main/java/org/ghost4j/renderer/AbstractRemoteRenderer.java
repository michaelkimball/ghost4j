/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.renderer;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.ghost4j.AbstractRemoteComponent;
import org.ghost4j.display.PageRaster;
import org.ghost4j.document.Document;
import org.ghost4j.document.DocumentException;
import org.ghost4j.document.PDFDocument;
import org.ghost4j.util.ImageUtil;
import org.ghost4j.worker.WorkerCrashedException;
import org.ghost4j.worker.WorkerPool;
import org.ghost4j.worker.WorkerProcess;
import org.ghost4j.worker.protocol.Frame;
import org.ghost4j.worker.protocol.RequestFrame;
import org.ghost4j.worker.protocol.ResponseErrFrame;
import org.ghost4j.worker.protocol.ResponseOkFrame;
import org.slf4j.MDC;

/**
 * Abstract remote renderer. Replaces Cajo/JavaFork dispatch with UDS worker-pool dispatch.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public abstract class AbstractRemoteRenderer extends AbstractRemoteComponent
        implements RemoteRenderer {

    public static final String SETTINGS_KEY_PAGE_BEGIN = "_pageBegin";
    public static final String SETTINGS_KEY_PAGE_END = "_pageEnd";

    public abstract List<PageRaster> run(Document document, int begin, int end)
            throws IOException, RendererException, DocumentException;

    public List<Image> render(Document document)
            throws IOException, RendererException, DocumentException {
        return this.render(document, 0, document.getPageCount() - 1);
    }

    public List<Image> render(Document document, int begin, int end)
            throws IOException, RendererException, DocumentException {

        if ((begin > end) || (end > document.getPageCount()) || (begin < 0) || (end < 0)) {
            throw new RendererException("Invalid page range");
        }

        if (maxProcessCount == 0) {
            return ImageUtil.convertPageRastersToImages(run(document, begin, end));
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
                throw new RendererException("Failed to extract component settings", e);
            }

            settings.put(SETTINGS_KEY_PAGE_BEGIN, begin);
            settings.put(SETTINGS_KEY_PAGE_END, end);

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
                throw new WorkerCrashedException("Worker crashed during rendering", e);
            }

            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            log.debug(
                    "op={} component={} docBytes={} pages={}-{} durationMs={}",
                    Integer.toHexString(opId),
                    this.getClass().getSimpleName(),
                    document.getContent().length,
                    begin,
                    end,
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
                    List<PageRaster> rasters = deserializeRasters(ok.result());
                    return ImageUtil.convertPageRastersToImages(rasters);
                } catch (IOException e) {
                    throw new RendererException(
                            "Failed to deserialize render result from worker", e);
                }
            } else if (response instanceof ResponseErrFrame err) {
                throw new RendererException(err.errorClass() + ": " + err.errorMessage());
            }

            throw new RendererException("Unexpected frame type from worker: " + response);

        } finally {
            pool.release(worker);
            MDC.remove("ghost4j.operationId");
            MDC.remove("ghost4j.component");
            MDC.remove("ghost4j.workerId");
        }
    }

    public static byte[] serializeRasters(List<PageRaster> rasters) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(rasters.size());
        for (PageRaster r : rasters) {
            dos.writeInt(r.getWidth());
            dos.writeInt(r.getHeight());
            dos.writeInt(r.getRaster());
            dos.writeInt(r.getFormat());
            byte[] data = (r.getData() != null) ? r.getData() : new byte[0];
            dos.writeInt(data.length);
            dos.write(data);
        }
        dos.flush();
        return baos.toByteArray();
    }

    public static List<PageRaster> deserializeRasters(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        int count = dis.readInt();
        List<PageRaster> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            PageRaster r = new PageRaster();
            r.setWidth(dis.readInt());
            r.setHeight(dis.readInt());
            r.setRaster(dis.readInt());
            r.setFormat(dis.readInt());
            int dataLen = dis.readInt();
            byte[] data = new byte[dataLen];
            dis.readFully(data);
            r.setData(data);
            result.add(r);
        }
        return result;
    }
}
