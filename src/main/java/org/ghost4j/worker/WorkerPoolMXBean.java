/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

/**
 * JMX MXBean interface exposing runtime metrics for a {@code WorkerPool}.
 *
 * <p>One MBean is registered per pool under the object name:
 *
 * <pre>
 *     org.ghost4j:type=WorkerPool,component=&lt;ComponentSimpleName&gt;
 * </pre>
 *
 * <p>The MXBean naming convention (interface name ending in {@code MXBean}) means no annotation is
 * required — the JVM's management infrastructure automatically treats this as an open MXBean and
 * maps all return types to standard open types.
 *
 * @see WorkerPool
 */
public interface WorkerPoolMXBean {

    /**
     * Fully-qualified class name of the component this pool serves.
     *
     * @return e.g. {@code "org.ghost4j.converter.PDFConverter"}
     */
    String getComponentClass();

    /**
     * Total number of worker slots in the pool (fixed at construction time).
     *
     * @return pool size
     */
    int getPoolSize();

    /**
     * Number of currently idle (available) workers at the instant this attribute is read.
     *
     * <p>This is a point-in-time snapshot of the idle queue depth. It is not atomically consistent
     * with {@link #getActiveWorkers()} — the two values can temporarily not sum to {@link
     * #getPoolSize()} if a worker is mid-transition between states.
     *
     * @return idle worker count
     */
    int getIdleWorkers();

    /**
     * Number of currently active (busy) workers.
     *
     * <p>Computed as {@code poolSize - idleWorkers}. Because both reads are independent
     * (non-atomic), this value may transiently be negative or greater than {@link #getPoolSize()}
     * during worker state transitions. Treat it as a best-effort approximation rather than a
     * precise counter.
     *
     * @return active worker count (approximation)
     */
    int getActiveWorkers();

    /**
     * Total number of requests successfully completed since pool creation.
     *
     * <p>Incremented in {@code WorkerPool.release()} after a successful round trip. Not incremented
     * for requests that end in a worker crash or an acquire timeout.
     *
     * @return cumulative successful request count
     */
    long getRequestsProcessed();

    /**
     * Total number of worker crash replacements since pool creation.
     *
     * <p>Incremented each time {@code WorkerPool.replaceWorker()} is called, regardless of whether
     * the crash was detected by the calling thread or by the {@code WorkerWatcher} daemon thread.
     *
     * @return cumulative crash count
     */
    long getCrashCount();

    /**
     * Total number of {@code InterpreterBusyException} throws (acquire timeouts) since pool
     * creation.
     *
     * <p>Incremented in {@code WorkerPool.acquire()} when the blocking poll times out with no idle
     * worker available.
     *
     * @return cumulative timeout count
     */
    long getTimeoutCount();

    /**
     * Wall-clock milliseconds taken to spawn the most-recently started worker process.
     *
     * <p>Measured from {@code ProcessBuilder.start()} to receipt of the {@code 0x01} ready-byte on
     * the worker's stdout. Returns {@code -1} if no worker has been spawned yet (e.g. before the
     * pool is fully initialised).
     *
     * @return last worker startup duration in milliseconds, or {@code -1}
     */
    long getLastWorkerStartupMs();
}
