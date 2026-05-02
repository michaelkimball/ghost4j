/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerPool}.
 *
 * <p>Tests use a {@code FakeProcess} / fake {@link WorkerProcess} pair so that no real child JVM or
 * Ghostscript installation is required. The package-private {@code WorkerProcess(UUID, Process)}
 * constructor is used (accessible because this test is in the same package).
 *
 * <p>Integration tests that exercise the full IPC round-trip (frame encoding, GS initialisation,
 * crash recovery via real process exit) are deferred to a separate test class that requires
 * Ghostscript installed. See {@code docs/AGENT-IMPL-POOL.md} for the list of skipped scenarios.
 */
class WorkerPoolTest {

    /** Dummy component class — used only for logging and JMX naming in the pool. */
    private static final Class<?> COMPONENT_CLASS = String.class;

    private WorkerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * Verifies the normal acquire → use → release cycle:
     *
     * <ul>
     *   <li>Idle count decrements by one when a worker is acquired.
     *   <li>Active count increments correspondingly.
     *   <li>After release the worker is returned to the idle queue.
     *   <li>{@link WorkerPool#getRequestsProcessed()} is incremented on release.
     * </ul>
     */
    @Test
    void testAcquireAndRelease() {
        pool = createPool(2);

        assertEquals(2, pool.getIdleWorkers(), "Both workers should start idle");
        assertEquals(0, pool.getActiveWorkers());
        assertEquals(0, pool.getRequestsProcessed());

        WorkerProcess w1 = pool.acquire();

        assertNotNull(w1);
        assertEquals(1, pool.getIdleWorkers(), "One worker acquired — one should remain idle");
        assertEquals(1, pool.getActiveWorkers());
        assertEquals(0, pool.getRequestsProcessed(), "Release has not happened yet");

        pool.release(w1);

        assertEquals(2, pool.getIdleWorkers(), "Worker should be back in the idle queue");
        assertEquals(0, pool.getActiveWorkers());
        assertEquals(1, pool.getRequestsProcessed(), "Release increments requestsProcessed");
    }

    /**
     * Verifies that {@link InterpreterBusyException} is thrown when all workers are busy and the
     * acquire timeout elapses.
     *
     * <p>Pool is created with a very short acquire timeout (100 ms) to keep the test fast.
     */
    @Test
    void testAcquireTimeoutThrowsInterpreterBusyException() {
        pool =
                createPool(
                        1,
                        /* acquireTimeoutMs= */ 100L,
                        /* shutdownGracePeriodMs= */ WorkerPool.DEFAULT_SHUTDOWN_GRACE_PERIOD_MS);

        WorkerProcess w = pool.acquire(); // acquires the only worker
        assertEquals(0, pool.getIdleWorkers(), "Pool should be fully busy");

        assertThrows(
                InterpreterBusyException.class,
                pool::acquire,
                "Should throw InterpreterBusyException when all workers are busy");

        assertEquals(1, pool.getTimeoutCount(), "Timeout counter should have been incremented");

        // Clean up: return the worker so @AfterEach shutdown() can drain the queue.
        pool.release(w);
    }

    /**
     * Verifies that releasing a crashed worker is a no-op: the worker is not re-added to the idle
     * queue and {@link WorkerPool#getRequestsProcessed()} is not incremented.
     */
    @Test
    void testReleaseAfterCrashIsNoOp() {
        pool = createPool(1);

        WorkerProcess w = pool.acquire();
        assertEquals(0, pool.getIdleWorkers());

        // Simulate a crash by setting the flag that WorkerWatcher/dispatch would normally set.
        w.crashed().set(true);

        pool.release(w);

        assertEquals(
                0, pool.getIdleWorkers(), "Crashed worker must not be returned to the idle queue");
        assertEquals(
                0,
                pool.getRequestsProcessed(),
                "requestsProcessed must not be incremented for a crashed release");
    }

    /**
     * Verifies that releasing a worker after {@link WorkerPool#shutdown()} has been called is a
     * no-op: the worker is not re-added to the idle queue and {@link
     * WorkerPool#getRequestsProcessed()} is not incremented.
     *
     * <p>Uses a local pool variable (not the {@code pool} field) so that {@code @AfterEach} does
     * not attempt a second shutdown.
     */
    @Test
    void testShuttingDownReleaseIsNoOp() {
        // Use a short grace period so shutdown() completes quickly in the test.
        WorkerPool shutdownPool =
                createPool(1, WorkerPool.DEFAULT_ACQUIRE_TIMEOUT_MS, /* gracePeriodMs= */ 200L);

        WorkerProcess w = shutdownPool.acquire();
        assertEquals(0, shutdownPool.getIdleWorkers(), "Worker checked out — idle should be 0");

        // Trigger shutdown: sets shuttingDown = true internally.
        shutdownPool.shutdown();

        // Attempting to release a worker into a shut-down pool must be a no-op.
        shutdownPool.release(w);

        assertEquals(
                0,
                shutdownPool.getIdleWorkers(),
                "Worker must not be re-enqueued into a shut-down pool");
        assertEquals(
                0,
                shutdownPool.getRequestsProcessed(),
                "requestsProcessed must not be incremented after shutdown");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates a pool with default timeouts and the given size. */
    private WorkerPool createPool(int size) {
        return createPool(
                size,
                WorkerPool.DEFAULT_ACQUIRE_TIMEOUT_MS,
                WorkerPool.DEFAULT_SHUTDOWN_GRACE_PERIOD_MS);
    }

    /** Creates a pool with a custom acquire timeout and default grace period. */
    private WorkerPool createPool(int size, long acquireTimeoutMs, long shutdownGracePeriodMs) {
        return new WorkerPool(
                COMPONENT_CLASS,
                size,
                "64m",
                acquireTimeoutMs,
                shutdownGracePeriodMs,
                WorkerPoolTest::newFakeWorker);
    }

    /** Factory method: returns a fresh {@link WorkerProcess} backed by a {@link FakeProcess}. */
    private static WorkerProcess newFakeWorker() {
        return new WorkerProcess(UUID.randomUUID(), new FakeProcess());
    }

    // =========================================================================
    // FakeProcess — a Process stub that never exits until explicitly killed
    // =========================================================================

    /**
     * A {@link Process} stub suitable for WorkerPool unit testing.
     *
     * <p>Design rationale:
     *
     * <ul>
     *   <li>{@code isAlive()} returns {@code true} until {@link #terminateNormally()} or {@link
     *       #destroyForcibly()} is called. This prevents the {@link WorkerWatcher} daemon thread
     *       from immediately triggering a crash-replacement loop.
     *   <li>{@code waitFor(timeout, unit)} always returns {@code true} immediately. This allows
     *       {@code WorkerProcess.shutdown()} (which calls this method) to complete without
     *       blocking, keeping test execution fast.
     *   <li>{@code waitFor()} (no-arg) blocks until the latch is released. {@link WorkerWatcher}
     *       calls this and is correctly held until the fake process is explicitly terminated.
     * </ul>
     */
    private static final class FakeProcess extends Process {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean alive = true;

        /** Simulates normal process termination (unblocks {@link #waitFor()}). */
        void terminateNormally() {
            alive = false;
            latch.countDown();
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        /**
         * Blocks until {@link #terminateNormally()} or {@link #destroyForcibly()} is called. Used
         * by {@link WorkerWatcher} to detect process exit.
         */
        @Override
        public int waitFor() throws InterruptedException {
            latch.await();
            return 0;
        }

        /**
         * Returns {@code true} immediately, simulating a process that has already exited within the
         * timeout. Used by {@code WorkerProcess.shutdown()} to avoid 5-second test delays.
         */
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public Process destroyForcibly() {
            terminateNormally();
            return this;
        }

        @Override
        public void destroy() {
            terminateNormally();
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("Process has not terminated");
            }
            return 0;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }
    }
}
