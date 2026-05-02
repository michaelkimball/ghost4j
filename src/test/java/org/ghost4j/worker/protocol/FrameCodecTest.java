/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FrameCodec}. Each test uses {@link ByteArrayOutputStream} / {@link
 * ByteArrayInputStream} so no live socket or child process is required.
 */
public class FrameCodecTest {

    /** Encode a RequestFrame then decode it — all fields must survive the round-trip. */
    @Test
    public void testRequestFrameRoundTrip() throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("resolution", 150);
        settings.put("compress", true);

        byte[] docBytes = new byte[] {0x25, 0x50, 0x44, 0x46}; // "%PDF"
        RequestFrame original = new RequestFrame(42, RequestFrame.DOC_PDF, settings, docBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(RequestFrame.class, decoded);
        RequestFrame req = (RequestFrame) decoded;
        assertEquals(42, req.operationId());
        assertEquals(RequestFrame.DOC_PDF, req.docType());
        assertEquals(150, req.settings().get("resolution"));
        assertEquals(Boolean.TRUE, req.settings().get("compress"));
        assertArrayEquals(docBytes, req.documentBytes());
    }

    /** Encode a ResponseOkFrame then decode it — operationId and result bytes must match. */
    @Test
    public void testResponseOkRoundTrip() throws Exception {
        byte[] result = new byte[] {0x01, 0x02, 0x03, 0x04};
        ResponseOkFrame original = new ResponseOkFrame(7, result);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(ResponseOkFrame.class, decoded);
        ResponseOkFrame resp = (ResponseOkFrame) decoded;
        assertEquals(7, resp.operationId());
        assertArrayEquals(result, resp.result());
    }

    /** Encode a ResponseErrFrame then decode it — all string fields must survive. */
    @Test
    public void testResponseErrRoundTrip() throws Exception {
        ResponseErrFrame original =
                new ResponseErrFrame(
                        3, "org.ghost4j.GhostscriptException", "Ghostscript error: -100");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(ResponseErrFrame.class, decoded);
        ResponseErrFrame err = (ResponseErrFrame) decoded;
        assertEquals(3, err.operationId());
        assertEquals("org.ghost4j.GhostscriptException", err.errorClass());
        assertEquals("Ghostscript error: -100", err.errorMessage());
    }

    /** Encode a PING control frame and verify it decodes with type == PING. */
    @Test
    public void testControlFramePing() throws Exception {
        ControlFrame original = new ControlFrame(ControlFrame.PING);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(ControlFrame.class, decoded);
        assertEquals(ControlFrame.PING, ((ControlFrame) decoded).type());
    }

    /** Encode a SHUTDOWN control frame and verify it decodes with type == SHUTDOWN. */
    @Test
    public void testControlFrameShutdown() throws Exception {
        ControlFrame original = new ControlFrame(ControlFrame.SHUTDOWN);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(ControlFrame.class, decoded);
        assertEquals(ControlFrame.SHUTDOWN, ((ControlFrame) decoded).type());
    }

    /**
     * A header with an incorrect magic number must cause {@link ProtocolException} without
     * attempting to read any payload bytes.
     */
    @Test
    public void testMagicMismatch() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(0xDEADBEEF); // wrong magic
        dos.writeByte(FrameCodec.PING);
        dos.writeInt(0); // zero-length payload
        dos.flush();

        assertThrows(
                ProtocolException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray())));
    }

    /**
     * A header claiming a payload length of {@link FrameCodec#MAX_PAYLOAD_BYTES}{@code +1} must
     * cause {@link ProtocolException} before any allocation attempt.
     */
    @Test
    public void testOversizedPayload() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(FrameCodec.MAGIC);
        dos.writeByte(FrameCodec.PING);
        dos.writeInt(FrameCodec.MAX_PAYLOAD_BYTES + 1); // one byte over the limit
        dos.flush();

        assertThrows(
                ProtocolException.class,
                () -> FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray())));
    }

    /**
     * A settings map containing one value of each supported type must survive encode → decode with
     * types preserved. {@code Long} is tested with a value outside {@code Integer} range so it
     * cannot be confused with {@code Integer} on decoding.
     */
    @Test
    public void testSettingsJsonRoundTrip() throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("strKey", "hello world");
        settings.put("intKey", 42);
        settings.put("longKey", 5_000_000_000L); // outside Integer range
        settings.put("dblKey", 3.14);
        settings.put("boolKey", Boolean.TRUE);

        RequestFrame original = new RequestFrame(1, RequestFrame.DOC_PS, settings, new byte[0]);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(RequestFrame.class, decoded);
        Map<String, Object> s = ((RequestFrame) decoded).settings();
        assertEquals("hello world", s.get("strKey"));
        assertEquals(42, s.get("intKey"));
        assertEquals(5_000_000_000L, s.get("longKey"));
        assertEquals(3.14, (Double) s.get("dblKey"), 1e-10);
        assertEquals(Boolean.TRUE, s.get("boolKey"));
    }

    /** An empty settings map must encode and decode without error, producing an empty map. */
    @Test
    public void testEmptySettings() throws Exception {
        RequestFrame original = new RequestFrame(0, RequestFrame.DOC_PDF, Map.of(), new byte[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, original);
        Frame decoded = FrameCodec.readFrame(new ByteArrayInputStream(baos.toByteArray()));

        assertInstanceOf(RequestFrame.class, decoded);
        assertTrue(((RequestFrame) decoded).settings().isEmpty());
    }
}
