/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

/**
 * Frame sent from worker to parent when request processing fails with an exception.
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 *
 * @param operationId echoed from the corresponding {@link RequestFrame}
 * @param errorClass fully-qualified name of the exception class (e.g. {@code
 *     "org.ghost4j.GhostscriptException"})
 * @param errorMessage the exception message
 */
public record ResponseErrFrame(int operationId, String errorClass, String errorMessage)
        implements Frame {}
