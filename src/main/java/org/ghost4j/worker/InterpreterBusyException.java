/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

/**
 * Thrown by {@code WorkerPool.acquire()} when no idle worker becomes available within the
 * configured acquire timeout. This is an unchecked exception so callers can apply retry or
 * circuit-breaker logic without being forced to declare it.
 */
public class InterpreterBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InterpreterBusyException(String message) {
        super(message);
    }
}
