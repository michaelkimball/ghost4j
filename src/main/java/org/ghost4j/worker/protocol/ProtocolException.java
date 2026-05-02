/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

import java.io.IOException;

/**
 * Thrown by {@link FrameCodec} when a frame header magic number does not match the expected value,
 * indicating protocol corruption or a version mismatch.
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 */
public class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
