/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;

/**
 * Tests for {@link WorkerDispatchSupport}.
 *
 * <p>These tests focus on MDC correctness and dispatch mechanics. They do not assert on SLF4J log
 * output — a {@code ListAppender} is not required because the observable behaviour under test is
 * the MDC state visible from within and after the callable.
 */
class WorkerDispatchSupportTest {

    /** A no-op worker object whose {@code toString()} returns a stable identifier. */
    private static final Object FAKE_WORKER =
            new Object() {
                @Override
                public String toString() {
                    return "worker-test-1";
                }
            };

    private static final int OPERATION_ID = 0xABCD;
    private static final Class<?> COMPONENT = WorkerDispatchSupportTest.class;

    @BeforeAll
    static void installMdcAdapter() throws Exception {
        // Force SLF4J to complete its full initialization (provider discovery → sets MDC_ADAPTER
        // to NOPMDCAdapter for slf4j-simple) BEFORE we overwrite it.  Without this, the first
        // test that triggers WorkerDispatchSupport class-loading would call
        // LoggerFactory.getLogger,
        // which re-initializes SLF4J and silently overwrites our BasicMDCAdapter with
        // NOPMDCAdapter.
        LoggerFactory.getLogger(WorkerDispatchSupportTest.class);

        // Now replace the no-op adapter with one that actually stores MDC values per-thread.
        Field f = MDC.class.getDeclaredField("MDC_ADAPTER");
        f.setAccessible(true);
        f.set(null, new BasicMDCAdapter());
    }

    @AfterEach
    void clearMdc() {
        // Guard: ensure no MDC pollution leaks between tests even if a test body exits early.
        MDC.remove(WorkerDispatchSupport.MDC_OPERATION_ID);
        MDC.remove(WorkerDispatchSupport.MDC_COMPONENT);
        MDC.remove(WorkerDispatchSupport.MDC_WORKER_ID);
    }

    /**
     * Verify that the three MDC keys are present with the expected values while the callable is
     * executing.
     */
    @Test
    void testMdcKeysSetDuringDispatch() throws IOException {
        AtomicReference<String> capturedOpId = new AtomicReference<>();
        AtomicReference<String> capturedComponent = new AtomicReference<>();
        AtomicReference<String> capturedWorkerId = new AtomicReference<>();

        WorkerDispatchSupport.dispatch(
                OPERATION_ID,
                COMPONENT,
                FAKE_WORKER,
                WorkerDispatchSupport.DEFAULT_SLOW_REQUEST_THRESHOLD_MS,
                () -> {
                    capturedOpId.set(MDC.get(WorkerDispatchSupport.MDC_OPERATION_ID));
                    capturedComponent.set(MDC.get(WorkerDispatchSupport.MDC_COMPONENT));
                    capturedWorkerId.set(MDC.get(WorkerDispatchSupport.MDC_WORKER_ID));
                    return Boolean.TRUE;
                });

        assertNotNull(capturedOpId.get(), "MDC_OPERATION_ID should be set during dispatch");
        assertEquals(
                Integer.toHexString(OPERATION_ID),
                capturedOpId.get(),
                "MDC_OPERATION_ID should be the hex-encoded operation id");

        assertNotNull(capturedComponent.get(), "MDC_COMPONENT should be set during dispatch");
        assertEquals(
                COMPONENT.getSimpleName(),
                capturedComponent.get(),
                "MDC_COMPONENT should be the simple class name");

        assertNotNull(capturedWorkerId.get(), "MDC_WORKER_ID should be set during dispatch");
        assertEquals(
                FAKE_WORKER.toString(),
                capturedWorkerId.get(),
                "MDC_WORKER_ID should be the worker toString()");
    }

    /**
     * Verify that all three MDC keys are removed after a successful dispatch so that they cannot
     * contaminate subsequent log statements on the same thread.
     */
    @Test
    void testMdcKeysRemovedAfterDispatch() throws IOException {
        WorkerDispatchSupport.dispatch(
                OPERATION_ID,
                COMPONENT,
                FAKE_WORKER,
                WorkerDispatchSupport.DEFAULT_SLOW_REQUEST_THRESHOLD_MS,
                () -> "result");

        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_OPERATION_ID),
                "MDC_OPERATION_ID must be removed after successful dispatch");
        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_COMPONENT),
                "MDC_COMPONENT must be removed after successful dispatch");
        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_WORKER_ID),
                "MDC_WORKER_ID must be removed after successful dispatch");
    }

    /**
     * Verify that the MDC is cleaned up even when the callable throws {@link IOException}. This is
     * the most important invariant: a failing dispatch must not leave stale MDC state that corrupts
     * subsequent log entries on the same thread.
     */
    @Test
    void testMdcKeysRemovedOnException() {
        IOException simulatedError = new IOException("simulated socket failure");

        assertThrows(
                IOException.class,
                () ->
                        WorkerDispatchSupport.dispatch(
                                OPERATION_ID,
                                COMPONENT,
                                FAKE_WORKER,
                                WorkerDispatchSupport.DEFAULT_SLOW_REQUEST_THRESHOLD_MS,
                                () -> {
                                    throw simulatedError;
                                }),
                "IOException from callable must propagate out of dispatch");

        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_OPERATION_ID),
                "MDC_OPERATION_ID must be removed even after IOException");
        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_COMPONENT),
                "MDC_COMPONENT must be removed even after IOException");
        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_WORKER_ID),
                "MDC_WORKER_ID must be removed even after IOException");
    }

    /**
     * Verify that dispatch completes without error when the callable introduces artificial latency.
     * This exercises the elapsed-time measurement code path and indirectly exercises the slow-
     * request WARN path by using a very low threshold (1 ms) so the warn branch is taken.
     */
    @Test
    void testElapsedTimeIsLogged() throws IOException {
        // Use a 1 ms slow-request threshold so the WARN branch is exercised.
        long veryLowThresholdMs = 1L;

        String result =
                WorkerDispatchSupport.dispatch(
                        OPERATION_ID,
                        COMPONENT,
                        FAKE_WORKER,
                        veryLowThresholdMs,
                        () -> {
                            // Introduce enough latency to exceed the 1 ms threshold reliably.
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        });

        assertEquals("done", result, "dispatch must return the callable's result unchanged");

        // MDC must be clean after a slow-path dispatch too.
        assertNull(
                MDC.get(WorkerDispatchSupport.MDC_OPERATION_ID),
                "MDC must be clean after slow dispatch");
    }
}
