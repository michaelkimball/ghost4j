/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

import java.util.Map;

/**
 * Frame sent from parent to worker carrying a document processing request.
 *
 * <p>This is a stub implementation. The authoritative version will be supplied by the
 * FrameCodec/protocol agent.
 *
 * @param operationId monotonically increasing ID echoed in the response for log correlation
 * @param docType document type byte — {@code 0x00} for PDF, {@code 0x01} for PostScript
 * @param settings flat map of component settings (applied via BeanUtils.populate)
 * @param documentBytes raw bytes of the input document
 */
public record RequestFrame(
        int operationId, byte docType, Map<String, Object> settings, byte[] documentBytes)
        implements Frame {

    /** Document type byte identifying a PDF document. */
    public static final byte DOC_PDF = 0x00;

    /** Document type byte identifying a PostScript document. */
    public static final byte DOC_PS = 0x01;
}
