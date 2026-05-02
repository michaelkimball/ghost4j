/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

/**
 * Frame sent from worker to parent on successful request completion.
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 *
 * @param operationId echoed from the corresponding {@link RequestFrame}
 * @param result the serialised result bytes
 */
public record ResponseOkFrame(int operationId, byte[] result) implements Frame {}
