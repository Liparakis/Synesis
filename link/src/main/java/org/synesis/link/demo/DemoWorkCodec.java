package org.synesis.link.demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded codec for the demo application stream; it does not authenticate peers. */
public final class DemoWorkCodec {
    /** Maximum unprefixed application frame size. */
    public static final int MAX_FRAME_BYTES = 4_096;
    private static final int MAGIC = 0x53445731;
    private static final int VERSION = 1;
    private static final int REQUEST = 1;
    private static final int RESULT = 2;

    private DemoWorkCodec() { }

    /**
     * Encodes one bounded request deterministically.
     * @param request request to encode
     * @return complete unprefixed frame
     */
    public static byte[] encodeRequest(DemoWorkRequest request) {
        return encode(REQUEST, request.requestId(), request.operation(), null);
    }

    /**
     * Encodes one bounded result deterministically.
     * @param result result to encode
     * @return complete unprefixed frame
     */
    public static byte[] encodeResult(DemoWorkResult result) {
        return encode(RESULT, result.requestId(), result.message(), result.status());
    }

    /**
     * Decodes and validates one request frame.
     * @param encoded complete unprefixed frame
     * @return decoded request
     */
    public static DemoWorkRequest decodeRequest(byte[] encoded) {
        Parsed parsed = decode(encoded, REQUEST);
        return new DemoWorkRequest(parsed.id, parsed.text);
    }

    /**
     * Decodes and validates one result frame.
     * @param encoded complete unprefixed frame
     * @return decoded result
     */
    public static DemoWorkResult decodeResult(byte[] encoded) {
        Parsed parsed = decode(encoded, RESULT);
        return new DemoWorkResult(parsed.id, parsed.status, parsed.text);
    }

    private static byte[] encode(int kind, UUID id, String text, DemoWorkStatus status) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeInt(MAGIC);
                data.writeByte(VERSION);
                data.writeByte(kind);
                data.writeLong(id.getMostSignificantBits());
                data.writeLong(id.getLeastSignificantBits());
                if (status != null) data.writeByte(status.ordinal());
                data.writeShort(bytes.length);
                data.write(bytes);
            }
            if (output.size() > MAX_FRAME_BYTES) throw new IllegalArgumentException("demo frame is oversized");
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static Parsed decode(byte[] encoded, int expectedKind) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("invalid demo frame bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
                    || input.readUnsignedByte() != expectedKind) {
                throw new IllegalArgumentException("invalid demo frame header");
            }
            UUID id = new UUID(input.readLong(), input.readLong());
            DemoWorkStatus status = expectedKind == RESULT ? readStatus(input) : null;
            int length = input.readUnsignedShort();
            if (length > input.available()) throw new IllegalArgumentException("truncated demo frame");
            byte[] text = input.readNBytes(length);
            if (input.available() != 0) throw new IllegalArgumentException("trailing demo frame bytes");
            return new Parsed(id, decodeUtf8(text), status);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException value) throw value;
            throw new IllegalArgumentException("malformed demo frame", exception);
        }
    }

    private static DemoWorkStatus readStatus(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        DemoWorkStatus[] statuses = DemoWorkStatus.values();
        if (ordinal >= statuses.length) throw new IllegalArgumentException("unknown demo status");
        return statuses[ordinal];
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid UTF-8", exception);
        }
    }

    private record Parsed(UUID id, String text, DemoWorkStatus status) { }
}
