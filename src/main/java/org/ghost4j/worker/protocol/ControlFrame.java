/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

/**
 * Control frame with no payload, used for lifecycle signalling (SHUTDOWN, PING, PONG).
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 *
 * @param type one of {@link #SHUTDOWN}, {@link #PING}, {@link #PONG}
 */
public record ControlFrame(byte type) implements Frame {

    /** Parent → Worker: ordered stop, no payload. */
    public static final byte SHUTDOWN = 0x04;

    /** Parent → Worker: liveness check, no payload. */
    public static final byte PING = 0x05;

    /** Worker → Parent: liveness confirmation, no payload. */
    public static final byte PONG = 0x06;
}
