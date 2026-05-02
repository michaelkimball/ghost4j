/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.IOException;

/**
 * Thrown by the request-dispatch path when an {@code IOException} occurs on the worker socket and
 * is determined to be caused by the worker process having crashed (i.e. the process is no longer
 * alive).
 */
public class WorkerCrashedException extends IOException {

    private static final long serialVersionUID = 1L;

    public WorkerCrashedException(String message) {
        super(message);
    }

    public WorkerCrashedException(String message, Throwable cause) {
        super(message, cause);
    }
}
