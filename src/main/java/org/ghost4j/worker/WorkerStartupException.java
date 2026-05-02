/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker;

import java.io.IOException;

/**
 * Thrown by {@code WorkerProcess.spawn()} when the child worker JVM does not write its ready byte
 * (0x01) to stdout within the startup timeout, or writes an unexpected byte value.
 */
public class WorkerStartupException extends IOException {

    private static final long serialVersionUID = 1L;

    public WorkerStartupException(String message) {
        super(message);
    }

    public WorkerStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
