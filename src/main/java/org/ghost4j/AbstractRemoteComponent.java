/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.ghost4j.worker.WorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract remote component. Used as base class for remote components.
 *
 * <p>Replaces the Cajo/JavaFork-based remote dispatch with a Unix Domain Socket worker pool.
 * Workers are persistent child JVMs that start once and serve many requests without restart,
 * eliminating the per-call JVM startup + Ghostscript initialisation overhead of the previous
 * design.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public abstract class AbstractRemoteComponent extends AbstractComponent {

    /** Logger. */
    protected static final Logger log = LoggerFactory.getLogger(AbstractRemoteComponent.class);

    /**
     * Static registry of worker pools, one per concrete component type. Pools are shared across all
     * instances of the same class within one JVM.
     */
    protected static final ConcurrentHashMap<Class<?>, WorkerPool> pools =
            new ConcurrentHashMap<>();

    /**
     * Maximum number of parallel worker processes. {@code 0} means in-process mode: no child JVM is
     * spawned and the component's {@code run()} method is called directly on the calling thread.
     */
    protected int maxProcessCount = 0;

    /**
     * Per-instance monotonic operation counter. Each remote dispatch increments this and places the
     * low 32-bit value into the request frame so that parent-JVM and child-JVM log entries for the
     * same request can be correlated via the SLF4J MDC key {@code ghost4j.operationId}.
     */
    protected final AtomicInteger operationCounter = new AtomicInteger(0);

    /**
     * Returns the {@link WorkerPool} for this component type, creating it if absent.
     *
     * @param size pool size (== {@code maxProcessCount})
     * @return the pool for this component class
     */
    protected WorkerPool getOrCreatePool(int size) {
        return pools.computeIfAbsent(this.getClass(), cls -> WorkerPool.getOrCreate(cls, size));
    }

    /**
     * Checks whether the concrete class declares a {@code main(String[])} method.
     *
     * @return {@code true} if a {@code main} method is found on the concrete class
     */
    public boolean isStandAloneModeSupported() {
        try {
            this.getClass().getMethod("main", String[].class);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public int getMaxProcessCount() {
        return maxProcessCount;
    }

    public void setMaxProcessCount(int maxProcessCount) {
        this.maxProcessCount = maxProcessCount;
    }
}
