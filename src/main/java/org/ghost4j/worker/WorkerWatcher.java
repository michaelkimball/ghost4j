/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon thread that monitors a single worker process by blocking on {@link Process#waitFor()}.
 *
 * <p>When the process exits, {@code WorkerWatcher} uses a CAS on {@link WorkerProcess#crashed()} to
 * guarantee exactly-once crash handling. If this thread wins the CAS (i.e. the dispatch path has
 * not already detected the crash via an {@code IOException}), it invokes the supplied {@code
 * onCrash} callback — typically {@code WorkerPool::replaceWorker}.
 */
public class WorkerWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(WorkerWatcher.class);

    private final WorkerProcess worker;
    private final Consumer<WorkerProcess> onCrash;

    /**
     * Creates and starts a {@code WorkerWatcher} for the given worker.
     *
     * @param worker the worker process to watch
     * @param onCrash callback invoked (at most once) when a crash is detected; typically {@code
     *     WorkerPool::replaceWorker}
     */
    public WorkerWatcher(WorkerProcess worker, Consumer<WorkerProcess> onCrash) {
        this.worker = worker;
        this.onCrash = onCrash;
        setDaemon(true);
        setName("ghost4j-watcher-" + worker.id());
        start();
    }

    @Override
    public void run() {
        try {
            int exitCode = worker.process().waitFor();
            if (worker.crashed().compareAndSet(false, true)) {
                // This thread is first to detect the crash.
                log.warn(
                        "Worker {} crashed with exit code {} after {} requests handled."
                                + " Last stderr: {}",
                        worker.id(),
                        exitCode,
                        worker.requestsHandled().get(),
                        worker.stderrDrainer().getLastLines());
                onCrash.accept(worker);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
