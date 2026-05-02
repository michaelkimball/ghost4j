/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.worker.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes and decodes {@link Frame}s over a byte stream using the Ghost4J binary wire protocol.
 *
 * <p>Frame header (9 bytes, fixed):
 *
 * <pre>
 * [ 4 bytes: magic 0x47344A31 ("G4J1") ]
 * [ 1 byte:  message type              ]
 * [ 4 bytes: payload length (big-endian int) ]
 * </pre>
 *
 * <p>The payload immediately follows the header. Control frames ({@code SHUTDOWN}, {@code PING},
 * {@code PONG}) carry no payload (length = 0). See the design document for per-type payload
 * layouts.
 *
 * <p>Settings are encoded as a flat JSON object whose values are restricted to {@code String},
 * {@code Integer}, {@code Long}, {@code Double}, and {@code Boolean}. Jackson is intentionally
 * avoided to eliminate transitive dependency fragility; a minimal hand-rolled encoder/decoder is
 * used instead.
 *
 * <p>Type preservation for integer settings: values in the range [{@code Integer.MIN_VALUE}, {@code
 * Integer.MAX_VALUE}] are decoded as {@code Integer}; larger values are decoded as {@code Long}. A
 * {@code Long} value that happens to fit in the integer range will therefore decode as {@code
 * Integer}.
 */
public final class FrameCodec {

    /** Magic number identifying Ghost4J protocol frames ("G4J1" in ASCII). */
    public static final int MAGIC = 0x47344A31;

    /** Message type: document processing request (parent→worker). */
    public static final byte REQUEST = 0x01;

    /** Message type: successful processing response (worker→parent). */
    public static final byte RESPONSE_OK = 0x02;

    /** Message type: failed processing response (worker→parent). */
    public static final byte RESPONSE_ERR = 0x03;

    /** Message type: orderly shutdown signal (parent→worker, no payload). */
    public static final byte SHUTDOWN = 0x04;

    /** Message type: liveness check (parent→worker, no payload). */
    public static final byte PING = 0x05;

    /** Message type: liveness confirmation (worker→parent, no payload). */
    public static final byte PONG = 0x06;

    /**
     * Maximum number of bytes accepted as a frame payload (256 MiB). Payloads claiming to be larger
     * are rejected with {@link ProtocolException} before any payload bytes are read.
     */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

    private FrameCodec() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code frame} to {@code out} using the Ghost4J wire protocol.
     *
     * <p>The caller is responsible for synchronisation when the stream is shared across threads.
     *
     * @param out destination stream (not closed by this method)
     * @param frame the frame to encode
     * @throws IOException on I/O error
     * @throws ProtocolException if the frame type is not recognised
     */
    public static void writeFrame(OutputStream out, Frame frame) throws IOException {
        byte type = typeOf(frame);
        byte[] payload = buildPayload(frame);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(MAGIC);
        dos.writeByte(type);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    /**
     * Reads the next frame from {@code in}.
     *
     * <p>Blocks until all header and payload bytes are available. Verifies the magic number first
     * and rejects oversized payloads before allocating any memory for them.
     *
     * @param in source stream (not closed by this method)
     * @return the decoded frame
     * @throws ProtocolException on magic mismatch, oversized payload, or unknown message type
     * @throws IOException on I/O error or truncated stream
     */
    public static Frame readFrame(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new ProtocolException(
                    String.format("Invalid magic: expected 0x%08X, got 0x%08X", MAGIC, magic));
        }
        byte type = dis.readByte();
        int length = dis.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new ProtocolException(
                    "Payload length "
                            + Integer.toUnsignedLong(length)
                            + " exceeds maximum of "
                            + MAX_PAYLOAD_BYTES
                            + " bytes");
        }
        byte[] payload = new byte[length];
        dis.readFully(payload);
        return parsePayload(type, payload);
    }

    // -------------------------------------------------------------------------
    // Frame → type byte
    // -------------------------------------------------------------------------

    private static byte typeOf(Frame frame) throws ProtocolException {
        if (frame instanceof RequestFrame) return REQUEST;
        if (frame instanceof ResponseOkFrame) return RESPONSE_OK;
        if (frame instanceof ResponseErrFrame) return RESPONSE_ERR;
        if (frame instanceof ControlFrame cf) return cf.type();
        throw new ProtocolException("Unrecognised frame type: " + frame.getClass().getName());
    }

    // -------------------------------------------------------------------------
    // Frame → payload bytes
    // -------------------------------------------------------------------------

    private static byte[] buildPayload(Frame frame) throws IOException {
        if (frame instanceof ControlFrame) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        if (frame instanceof RequestFrame req) {
            dos.writeInt(req.operationId());
            dos.writeByte(req.docType());
            byte[] settingsJson = encodeSettings(req.settings()).getBytes(StandardCharsets.UTF_8);
            dos.writeInt(settingsJson.length);
            dos.write(settingsJson);
            byte[] docBytes = req.documentBytes();
            dos.writeInt(docBytes.length);
            dos.write(docBytes);
        } else if (frame instanceof ResponseOkFrame resp) {
            dos.writeInt(resp.operationId());
            byte[] result = resp.result();
            dos.writeInt(result.length);
            dos.write(result);
        } else if (frame instanceof ResponseErrFrame err) {
            dos.writeInt(err.operationId());
            byte[] classBytes = err.errorClass().getBytes(StandardCharsets.UTF_8);
            dos.writeInt(classBytes.length);
            dos.write(classBytes);
            byte[] msgBytes = err.errorMessage().getBytes(StandardCharsets.UTF_8);
            dos.writeInt(msgBytes.length);
            dos.write(msgBytes);
        }
        dos.flush();
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Payload bytes → Frame
    // -------------------------------------------------------------------------

    private static Frame parsePayload(byte type, byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        return switch (type) {
            case REQUEST -> {
                int opId = dis.readInt();
                byte docType = dis.readByte();
                int jsonLen = dis.readInt();
                byte[] jsonBytes = new byte[jsonLen];
                dis.readFully(jsonBytes);
                Map<String, Object> settings =
                        decodeSettings(new String(jsonBytes, StandardCharsets.UTF_8));
                int docLen = dis.readInt();
                byte[] docBytes = new byte[docLen];
                dis.readFully(docBytes);
                yield new RequestFrame(opId, docType, settings, docBytes);
            }
            case RESPONSE_OK -> {
                int opId = dis.readInt();
                int resultLen = dis.readInt();
                byte[] result = new byte[resultLen];
                dis.readFully(result);
                yield new ResponseOkFrame(opId, result);
            }
            case RESPONSE_ERR -> {
                int opId = dis.readInt();
                int classLen = dis.readInt();
                byte[] classBytes = new byte[classLen];
                dis.readFully(classBytes);
                String errorClass = new String(classBytes, StandardCharsets.UTF_8);
                int msgLen = dis.readInt();
                byte[] msgBytes = new byte[msgLen];
                dis.readFully(msgBytes);
                String errorMessage = new String(msgBytes, StandardCharsets.UTF_8);
                yield new ResponseErrFrame(opId, errorClass, errorMessage);
            }
            case SHUTDOWN -> new ControlFrame(ControlFrame.SHUTDOWN);
            case PING -> new ControlFrame(ControlFrame.PING);
            case PONG -> new ControlFrame(ControlFrame.PONG);
            default ->
                    throw new ProtocolException(
                            "Unknown message type: 0x" + Integer.toHexString(type & 0xFF));
        };
    }

    // -------------------------------------------------------------------------
    // Settings JSON encoder
    // -------------------------------------------------------------------------

    private static String encodeSettings(Map<String, Object> settings) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            appendJsonString(sb, entry.getKey());
            sb.append("\":");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append('"');
                appendJsonString(sb, s);
                sb.append('"');
            } else if (val instanceof Boolean b) {
                sb.append(b ? "true" : "false");
            } else if (val instanceof Number) {
                sb.append(val);
            } else if (val == null) {
                sb.append("null");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settings JSON decoder
    // -------------------------------------------------------------------------

    private static Map<String, Object> decodeSettings(String json) throws ProtocolException {
        int[] pos = {0};
        skipWs(json, pos);
        expect(json, pos, '{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWs(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            return result;
        }
        while (pos[0] < json.length()) {
            skipWs(json, pos);
            String key = parseJsonString(json, pos);
            skipWs(json, pos);
            expect(json, pos, ':');
            skipWs(json, pos);
            Object value = parseJsonValue(json, pos);
            result.put(key, value);
            skipWs(json, pos);
            if (pos[0] >= json.length()) break;
            char c = json.charAt(pos[0]);
            if (c == ',') {
                pos[0]++;
            } else if (c == '}') {
                break;
            } else {
                throw new ProtocolException(
                        "Unexpected character in settings JSON at position "
                                + pos[0]
                                + ": '"
                                + c
                                + "'");
            }
        }
        return result;
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static void expect(String s, int[] pos, char expected) throws ProtocolException {
        if (pos[0] >= s.length() || s.charAt(pos[0]) != expected) {
            char found = pos[0] < s.length() ? s.charAt(pos[0]) : '\0';
            throw new ProtocolException(
                    "Expected '"
                            + expected
                            + "' at position "
                            + pos[0]
                            + " in settings JSON, found '"
                            + found
                            + "'");
        }
        pos[0]++;
    }

    private static String parseJsonString(String s, int[] pos) throws ProtocolException {
        expect(s, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos[0] >= s.length()) {
                    throw new ProtocolException(
                            "Unexpected end of escape sequence in settings JSON");
                }
                char esc = s.charAt(pos[0]++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos[0] + 4 > s.length()) {
                            throw new ProtocolException(
                                    "Truncated \\u escape in settings JSON at position " + pos[0]);
                        }
                        String hex = s.substring(pos[0], pos[0] + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new ProtocolException(
                                    "Invalid \\u escape in settings JSON: \\u" + hex, e);
                        }
                        pos[0] += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new ProtocolException("Unterminated string in settings JSON");
    }

    private static Object parseJsonValue(String s, int[] pos) throws ProtocolException {
        if (pos[0] >= s.length()) {
            throw new ProtocolException("Unexpected end of settings JSON");
        }
        char c = s.charAt(pos[0]);
        if (c == '"') {
            return parseJsonString(s, pos);
        }
        if (c == 't') {
            if (s.startsWith("true", pos[0])) {
                pos[0] += 4;
                return Boolean.TRUE;
            }
            throw new ProtocolException("Invalid value in settings JSON at position " + pos[0]);
        }
        if (c == 'f') {
            if (s.startsWith("false", pos[0])) {
                pos[0] += 5;
                return Boolean.FALSE;
            }
            throw new ProtocolException("Invalid value in settings JSON at position " + pos[0]);
        }
        if (c == 'n') {
            if (s.startsWith("null", pos[0])) {
                pos[0] += 4;
                return null;
            }
            throw new ProtocolException("Invalid value in settings JSON at position " + pos[0]);
        }
        return parseJsonNumber(s, pos);
    }

    private static Number parseJsonNumber(String s, int[] pos) throws ProtocolException {
        int start = pos[0];
        if (pos[0] < s.length() && s.charAt(pos[0]) == '-') {
            pos[0]++;
        }
        while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
            pos[0]++;
        }
        boolean isFloat = false;
        if (pos[0] < s.length() && s.charAt(pos[0]) == '.') {
            isFloat = true;
            pos[0]++;
            while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                pos[0]++;
            }
        }
        if (pos[0] < s.length() && (s.charAt(pos[0]) == 'e' || s.charAt(pos[0]) == 'E')) {
            isFloat = true;
            pos[0]++;
            if (pos[0] < s.length() && (s.charAt(pos[0]) == '+' || s.charAt(pos[0]) == '-')) {
                pos[0]++;
            }
            while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) {
                pos[0]++;
            }
        }
        String numStr = s.substring(start, pos[0]);
        if (numStr.isEmpty() || numStr.equals("-")) {
            throw new ProtocolException("Invalid number in settings JSON at position " + start);
        }
        try {
            if (isFloat) {
                return Double.parseDouble(numStr);
            } else {
                long l = Long.parseLong(numStr);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
        } catch (NumberFormatException e) {
            throw new ProtocolException("Invalid number in settings JSON: " + numStr, e);
        }
    }
}
