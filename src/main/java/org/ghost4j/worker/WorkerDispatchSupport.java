/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Utility class providing a template method for the instrumented dispatch cycle used by {@code
 * AbstractRemoteConverter}, {@code AbstractRemoteAnalyzer}, and {@code AbstractRemoteRenderer}.
 *
 * <p>Call {@link #dispatch} from each remote component's dispatch path to get:
 *
 * <ul>
 *   <li>SLF4J MDC keys set for the duration of the call (cleared in a {@code finally} block).
 *   <li>DEBUG latency logging after every response.
 *   <li>WARN logging when a request exceeds the slow-request threshold.
 * </ul>
 *
 * <p>Usage sketch in a refactored {@code AbstractRemoteConverter.convert()}:
 *
 * <pre>{@code
 * WorkerPool pool = getOrCreatePool(maxProcessCount);
 * WorkerProcess worker = pool.acquire();
 * try {
 *     byte[] result = WorkerDispatchSupport.dispatch(
 *         operationId,
 *         this.getClass(),
 *         worker,
 *         WorkerDispatchSupport.DEFAULT_SLOW_REQUEST_THRESHOLD_MS,
 *         () -> {
 *             worker.send(req);
 *             return worker.receive();
 *         });
 *     // process result ...
 * } finally {
 *     pool.release(worker);
 * }
 * }</pre>
 *
 * <p>This class has no instance state and cannot be instantiated.
 *
 * @see WorkerPoolMXBean
 */
public final class WorkerDispatchSupport {

    private static final Logger log = LoggerFactory.getLogger(WorkerDispatchSupport.class);

    /** MDC key carrying the hex-encoded operation ID for cross-JVM log correlation. */
    public static final String MDC_OPERATION_ID = "ghost4j.operationId";

    /**
     * MDC key carrying the simple class name of the component that issued the request (e.g. {@code
     * "PDFConverter"}).
     */
    public static final String MDC_COMPONENT = "ghost4j.component";

    /**
     * MDC key carrying a string identifier for the specific worker process that is handling the
     * request (e.g. the UDS socket path or the PID).
     */
    public static final String MDC_WORKER_ID = "ghost4j.workerId";

    /**
     * Default slow-request threshold: 30 seconds. Requests that take longer than this emit a WARN
     * log entry, which is typically visible in production log tailing without raising the log
     * level.
     *
     * <p>Consider making this configurable via a system property such as {@code
     * ghost4j.slowRequestThresholdMs} if different component types have different expected latency
     * profiles.
     */
    public static final long DEFAULT_SLOW_REQUEST_THRESHOLD_MS = 30_000L;

    private WorkerDispatchSupport() {
        // static utility class — do not instantiate
    }

    /**
     * Execute a dispatch call with MDC tagging, latency measurement, and slow-request warning.
     *
     * <p>The three MDC keys ({@link #MDC_OPERATION_ID}, {@link #MDC_COMPONENT}, {@link
     * #MDC_WORKER_ID}) are set before {@code call} is invoked and removed in a {@code finally}
     * block, so they are cleared even if {@code call} throws.
     *
     * <p>Elapsed time is measured with {@link System#nanoTime()} (monotonic, unaffected by wall
     * clock adjustments) and reported in milliseconds.
     *
     * @param <T> return type of the dispatch callable
     * @param operationId unique identifier for this request; stored in the MDC as a hex string and
     *     echoed in the protocol frame header for cross-JVM correlation
     * @param componentClass the component class issuing the request; its simple name is placed in
     *     {@link #MDC_COMPONENT}
     * @param worker the acquired worker process object; {@link Object#toString()} is placed in
     *     {@link #MDC_WORKER_ID} (typed as {@code Object} to avoid a compile-time dependency on
     *     {@code WorkerProcess} which resides in the same package and is not yet available)
     * @param slowThresholdMs emit a WARN log entry when elapsed milliseconds exceed this value; use
     *     {@link #DEFAULT_SLOW_REQUEST_THRESHOLD_MS} unless the component has a known different
     *     latency profile
     * @param call lambda that performs the {@code send} + {@code receive} round-trip and returns
     *     the result; the lambda may throw {@link IOException}
     * @return the value returned by {@code call}
     * @throws IOException propagated unchanged from {@code call}; MDC keys are still removed
     */
    public static <T> T dispatch(
            int operationId,
            Class<?> componentClass,
            Object worker,
            long slowThresholdMs,
            DispatchCallable<T> call)
            throws IOException {

        MDC.put(MDC_OPERATION_ID, Integer.toHexString(operationId));
        MDC.put(MDC_COMPONENT, componentClass.getSimpleName());
        MDC.put(MDC_WORKER_ID, worker.toString());

        long t0 = System.nanoTime();
        try {
            return call.call();
        } finally {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            log.debug(
                    "op={} component={} durationMs={}",
                    Integer.toHexString(operationId),
                    componentClass.getSimpleName(),
                    elapsedMs);

            if (elapsedMs > slowThresholdMs) {
                log.warn(
                        "Slow GS request: op={} durationMs={} threshold={}",
                        Integer.toHexString(operationId),
                        elapsedMs,
                        slowThresholdMs);
            }

            MDC.remove(MDC_OPERATION_ID);
            MDC.remove(MDC_COMPONENT);
            MDC.remove(MDC_WORKER_ID);
        }
    }

    /**
     * Functional interface for the send + receive round-trip passed to {@link #dispatch}.
     *
     * @param <T> the result type (e.g. {@code ResponseFrame} or processed output bytes)
     */
    @FunctionalInterface
    public interface DispatchCallable<T> {

        /**
         * Perform the dispatch and return the result.
         *
         * @return the result
         * @throws IOException on socket or protocol error
         */
        T call() throws IOException;
    }
}
