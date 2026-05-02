/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

/**
 * Sealed marker interface for all Ghost4J worker protocol frames.
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 *
 * @see RequestFrame
 * @see ResponseOkFrame
 * @see ResponseErrFrame
 * @see ControlFrame
 */
public sealed interface Frame
        permits RequestFrame, ResponseOkFrame, ResponseErrFrame, ControlFrame {}
