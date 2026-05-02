/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed-size pool of persistent {@link WorkerProcess} instances for one component type.
 *
 * <p>Pools are created lazily on the first remote call and stored in a static registry keyed by
 * component class. Use {@link #getOrCreate} to obtain a pool:
 *
 * <pre>{@code
 * WorkerPool pool = WorkerPool.getOrCreate(PDFConverter.class, maxProcessCount);
 * WorkerProcess worker = pool.acquire();   // blocks until a worker is available
 * try {
 *     worker.send(requestFrame);
 *     Frame response = worker.receive();
 *     // ... handle response ...
 * } catch (IOException e) {
 *     if (worker.crashed().compareAndSet(false, true)) {
 *         pool.replaceWorker(worker);
 *     }
 *     throw new WorkerCrashedException("Worker crashed during request", e);
 * } finally {
 *     pool.release(worker);
 * }
 * }</pre>
 *
 * <p>Thread safety:
 *
 * <ul>
 *   <li>{@link #acquire} and {@link #release} are thread-safe via the underlying {@link
 *       LinkedBlockingQueue}.
 *   <li>{@link #replaceWorker} is {@code synchronized} as an additional guard on the {@code all}
 *       list mutation; the primary exactly-once guarantee comes from the CAS on {@link
 *       WorkerProcess#crashed()}.
 *   <li>The static pool registry uses {@link ConcurrentHashMap#computeIfAbsent} to ensure
 *       single-creation semantics even under concurrent first-call races.
 * </ul>
 *
 * @see WorkerPoolMXBean
 * @see WorkerProcess
 * @see WorkerWatcher
 */
public class WorkerPool implements WorkerPoolMXBean {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    /** Default acquire timeout: 60 seconds. Callers receive {@link InterpreterBusyException}. */
    static final long DEFAULT_ACQUIRE_TIMEOUT_MS = 60_000L;

    /** Default grace period for ordered shutdown before force-kill. */
    static final long DEFAULT_SHUTDOWN_GRACE_PERIOD_MS = 10_000L;

    /** Singleton pool registry: one pool per component class per JVM. */
    private static final ConcurrentHashMap<Class<?>, WorkerPool> pools = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Configuration (immutable after construction)
    // -------------------------------------------------------------------------

    private final Class<?> componentClass;
    private final int size;
    private final String xmx;
    private final long acquireTimeoutMs;
    private final long shutdownGracePeriodMs;
    private final Supplier<WorkerProcess> workerFactory;

    // -------------------------------------------------------------------------
    // Mutable pool state
    // -------------------------------------------------------------------------

    /** Queue of idle workers. Capacity is fixed at {@code size}. */
    private final BlockingQueue<WorkerProcess> idle;

    /**
     * All tracked workers (idle + active). {@link CopyOnWriteArrayList} is chosen because reads
     * (iteration in shutdown, {@link #replaceWorker} existence check) vastly outnumber writes
     * (construction, crash replacement).
     */
    private final List<WorkerProcess> all;

    private final AtomicLong requestsProcessed = new AtomicLong(0L);
    private final AtomicLong crashCount = new AtomicLong(0L);
    private final AtomicLong timeoutCount = new AtomicLong(0L);

    /** Wall-clock ms for the most recent {@link #startWorker()} call. {@code -1} until set. */
    private volatile long lastWorkerStartupMs = -1L;

    /** Set to {@code true} in {@link #shutdown()}; makes {@link #release} a no-op. */
    private volatile boolean shuttingDown = false;

    // =========================================================================
    // Static factory methods
    // =========================================================================

    /**
     * Returns the existing pool for {@code componentClass}, or creates a new one with the default
     * Xmx value of {@code "256m"}.
     *
     * @param componentClass the component type managed by this pool
     * @param size the number of persistent worker slots
     * @return the pool (possibly newly created)
     */
    public static WorkerPool getOrCreate(Class<?> componentClass, int size) {
        return getOrCreate(componentClass, size, "256m");
    }

    /**
     * Returns the existing pool for {@code componentClass}, or creates a new one.
     *
     * <p>Uses {@link ConcurrentHashMap#computeIfAbsent} to guarantee exactly-once creation even
     * under concurrent first-call races.
     *
     * @param componentClass the component type managed by this pool
     * @param size the number of persistent worker slots
     * @param xmx the {@code -Xmx} value for each child JVM (e.g. {@code "256m"})
     * @return the pool (possibly newly created)
     */
    public static WorkerPool getOrCreate(Class<?> componentClass, int size, String xmx) {
        return pools.computeIfAbsent(componentClass, cls -> new WorkerPool(cls, size, xmx));
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Production constructor — uses {@link WorkerProcess#spawn} as the worker factory.
     *
     * @param componentClass the component class to pass to {@code WorkerProcess.spawn}
     * @param size the number of worker slots to create immediately
     * @param xmx the {@code -Xmx} value for each child JVM
     */
    private WorkerPool(Class<?> componentClass, int size, String xmx) {
        this(
                componentClass,
                size,
                xmx,
                DEFAULT_ACQUIRE_TIMEOUT_MS,
                DEFAULT_SHUTDOWN_GRACE_PERIOD_MS,
                () -> {
                    try {
                        return WorkerProcess.spawn(componentClass, xmx);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "Failed to spawn worker for " + componentClass.getSimpleName(), e);
                    }
                });
    }

    /**
     * Package-private constructor for testing — allows injecting a custom worker factory so that
     * unit tests can supply pre-built {@link WorkerProcess} stubs without forking real JVMs.
     *
     * @param componentClass the component class (used for logging and JMX name)
     * @param size the number of worker slots
     * @param xmx the {@code -Xmx} string (passed to the factory; may be ignored by test stubs)
     * @param workerFactory called once per slot at construction time and once per crash replacement
     */
    WorkerPool(
            Class<?> componentClass, int size, String xmx, Supplier<WorkerProcess> workerFactory) {
        this(
                componentClass,
                size,
                xmx,
                DEFAULT_ACQUIRE_TIMEOUT_MS,
                DEFAULT_SHUTDOWN_GRACE_PERIOD_MS,
                workerFactory);
    }

    /**
     * Package-private full constructor — all parameters explicit. Used by tests that need to
     * control acquire timeout and shutdown grace period.
     *
     * @param componentClass the component class
     * @param size the number of worker slots
     * @param xmx the {@code -Xmx} string
     * @param acquireTimeoutMs milliseconds to block in {@link #acquire()} before throwing
     * @param shutdownGracePeriodMs milliseconds to wait for workers to exit in {@link #shutdown()}
     * @param workerFactory supplier of fresh {@link WorkerProcess} instances
     */
    WorkerPool(
            Class<?> componentClass,
            int size,
            String xmx,
            long acquireTimeoutMs,
            long shutdownGracePeriodMs,
            Supplier<WorkerProcess> workerFactory) {
        this.componentClass = componentClass;
        this.size = size;
        this.xmx = xmx;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.shutdownGracePeriodMs = shutdownGracePeriodMs;
        this.workerFactory = workerFactory;
        this.idle = new LinkedBlockingQueue<>(size);
        this.all = new CopyOnWriteArrayList<>();

        for (int i = 0; i < size; i++) {
            startWorker();
        }

        registerJmx();
        registerShutdownHook();
    }

    // =========================================================================
    // Core pool operations
    // =========================================================================

    /**
     * Acquires an idle worker, blocking up to {@code acquireTimeoutMs} if all workers are busy.
     *
     * @return an idle {@link WorkerProcess}, removed from the idle queue
     * @throws InterpreterBusyException if no worker becomes available within the configured acquire
     *     timeout, or if the current thread is interrupted while waiting
     */
    public WorkerProcess acquire() {
        try {
            WorkerProcess worker = idle.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (worker == null) {
                timeoutCount.incrementAndGet();
                throw new InterpreterBusyException(
                        "All "
                                + size
                                + " workers are busy (timeout after "
                                + acquireTimeoutMs
                                + " ms)");
            }
            return worker;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timeoutCount.incrementAndGet();
            throw new InterpreterBusyException("Interrupted while waiting for an idle worker");
        }
    }

    /**
     * Returns a worker to the idle queue after a successful request.
     *
     * <p>This method is a no-op (and does <em>not</em> increment {@link #getRequestsProcessed()})
     * when any of the following are true:
     *
     * <ul>
     *   <li>The pool is shutting down ({@link #shutdown()} has been called).
     *   <li>The worker has been marked as crashed ({@link WorkerProcess#crashed()} returns {@code
     *       true}).
     * </ul>
     *
     * @param worker the worker to release; must have been obtained from {@link #acquire()}
     */
    public void release(WorkerProcess worker) {
        if (shuttingDown || worker.crashed().get()) {
            return;
        }
        requestsProcessed.incrementAndGet();
        idle.offer(worker);
    }

    /**
     * Replaces a crashed worker with a newly spawned one.
     *
     * <p>Called by {@link WorkerWatcher} (when the OS process exits unexpectedly) and by the
     * dispatch path on {@link IOException} (when a socket error implies the worker has died).
     *
     * <p>Exactly-once semantics are guaranteed by two complementary mechanisms:
     *
     * <ol>
     *   <li>Call sites do {@code worker.crashed().compareAndSet(false, true)} before calling this
     *       method, so only one caller ever proceeds.
     *   <li>This method is {@code synchronized}: if two threads somehow arrive concurrently, the
     *       second sees {@code all.remove(dead)} return {@code false} and returns immediately.
     * </ol>
     *
     * <p>If the pool is shutting down, the method returns immediately without spawning a
     * replacement. If spawning fails, the pool shrinks by one slot (best-effort degraded mode).
     *
     * @param dead the crashed {@link WorkerProcess} to evict from the pool
     */
    public synchronized void replaceWorker(WorkerProcess dead) {
        if (shuttingDown) {
            // Pool is shutting down — no new workers needed.
            return;
        }
        if (!all.remove(dead)) {
            // Another thread already handled this crash.
            return;
        }
        crashCount.incrementAndGet();
        log.warn(
                "Worker {} for {} crashed after {} requests. Remaining pool size: {}. Spawning"
                        + " replacement.",
                dead.id(),
                componentClass.getSimpleName(),
                dead.requestsHandled().get(),
                all.size());
        try {
            startWorker();
        } catch (Exception e) {
            log.error(
                    "Failed to spawn replacement worker for {}. Pool is now degraded with {} workers.",
                    componentClass.getSimpleName(),
                    all.size(),
                    e);
        }
    }

    /**
     * Initiates an ordered pool shutdown.
     *
     * <ol>
     *   <li>Sets {@code shuttingDown = true} so that {@link #release} and {@link #replaceWorker}
     *       become no-ops.
     *   <li>Drains all currently idle workers and sends each an ordered {@code SHUTDOWN} frame via
     *       {@link WorkerProcess#shutdown()}.
     *   <li>Waits up to {@code shutdownGracePeriodMs} for every tracked worker process (including
     *       those currently processing a request) to exit naturally.
     *   <li>Force-kills any process that has not exited within the grace period.
     *   <li>Deregisters the JMX MBean and removes this pool from the static registry.
     * </ol>
     *
     * <p>This method is idempotent — repeated calls are safe.
     */
    public void shutdown() {
        shuttingDown = true;

        // Drain idle workers and attempt ordered shutdown of each.
        WorkerProcess w;
        while ((w = idle.poll()) != null) {
            try {
                w.shutdown();
            } catch (Exception e) {
                log.warn("Error during ordered shutdown of idle worker {}; continuing.", w.id(), e);
            }
        }

        // Wait for every tracked worker (idle + currently active) to exit.
        long deadline = System.currentTimeMillis() + shutdownGracePeriodMs;
        for (WorkerProcess worker : all) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0 && worker.isAlive()) {
                try {
                    worker.process().waitFor(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (worker.isAlive()) {
                log.warn(
                        "Worker {} for {} did not exit within the {} ms grace period; force-killing.",
                        worker.id(),
                        componentClass.getSimpleName(),
                        shutdownGracePeriodMs);
                worker.process().destroyForcibly();
            }
        }

        deregisterJmx();
        pools.remove(componentClass);
        log.info("WorkerPool for {} shut down.", componentClass.getSimpleName());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Spawns a single new worker via the configured factory, attaches a {@link WorkerWatcher}, and
     * offers the worker to both the {@code all} list and the {@code idle} queue.
     *
     * <p>{@link WorkerWatcher} starts itself in its constructor; no explicit {@code start()} call
     * is required here.
     *
     * @throws RuntimeException (wrapping any {@link IOException}) if the factory fails
     */
    private void startWorker() {
        long t0 = System.currentTimeMillis();
        WorkerProcess worker = workerFactory.get(); // may throw
        lastWorkerStartupMs = System.currentTimeMillis() - t0;

        // WorkerWatcher.start() is called inside the constructor.
        new WorkerWatcher(worker, this::replaceWorker);

        // Add to `all` before offering to `idle` so that shutdown() sees the worker
        // even if it is checked out immediately after being offered.
        all.add(worker);
        idle.offer(worker);

        log.debug(
                "Started worker {} for {} in {} ms.",
                worker.id(),
                componentClass.getSimpleName(),
                lastWorkerStartupMs);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (!shuttingDown) {
                                        shutdown();
                                    }
                                },
                                "ghost4j-pool-shutdown-" + componentClass.getSimpleName()));
    }

    private void registerJmx() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = jmxObjectName();
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
                log.debug("Registered JMX MBean: {}", name);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to register WorkerPool JMX MBean for {}; pool will function without JMX"
                            + " observability.",
                    componentClass.getSimpleName(),
                    e);
        }
    }

    private void deregisterJmx() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = jmxObjectName();
            if (mbs.isRegistered(name)) {
                mbs.unregisterMBean(name);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to deregister WorkerPool JMX MBean for {}.",
                    componentClass.getSimpleName(),
                    e);
        }
    }

    private ObjectName jmxObjectName() throws MalformedObjectNameException {
        return new ObjectName(
                "org.ghost4j:type=WorkerPool,component=" + componentClass.getSimpleName());
    }

    // =========================================================================
    // WorkerPoolMXBean implementation
    // =========================================================================

    @Override
    public String getComponentClass() {
        return componentClass.getName();
    }

    @Override
    public int getPoolSize() {
        return size;
    }

    @Override
    public int getIdleWorkers() {
        return idle.size();
    }

    @Override
    public int getActiveWorkers() {
        return size - idle.size();
    }

    @Override
    public long getRequestsProcessed() {
        return requestsProcessed.get();
    }

    @Override
    public long getCrashCount() {
        return crashCount.get();
    }

    @Override
    public long getTimeoutCount() {
        return timeoutCount.get();
    }

    @Override
    public long getLastWorkerStartupMs() {
        return lastWorkerStartupMs;
    }
}
