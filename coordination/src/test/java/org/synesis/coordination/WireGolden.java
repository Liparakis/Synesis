package org.synesis.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Golden-byte helpers: a fixed hex constant must decode to a fixed value and re-encode to the same bytes.
 */
public final class WireGolden {

    private WireGolden() {
    }

    /**
     * Decodes one wire form.
     *
     * @param <T> decoded type
     */
    @FunctionalInterface
    public interface Decoder<T> {

        /**
         * Decodes bytes.
         *
         * @param encoded wire bytes
         * @return decoded value
         * @throws Exception when the bytes are rejected
         */
        T decode(byte[] encoded) throws Exception;
    }

    /**
     * Encodes one wire form.
     *
     * @param <T> encoded type
     */
    @FunctionalInterface
    public interface Encoder<T> {

        /**
         * Encodes a value.
         *
         * @param value value to encode
         * @return wire bytes
         */
        byte[] encode(T value);
    }

    /**
     * Pins one wire form.
     *
     * <p>The constant decodes to {@code value}, {@code value} encodes to the constant, and re-encoding the decoded
     * value yields the constant again. {@code edges} records how the decoder treats one appended byte and a missing
     * last byte.
     *
     * @param <T>     value type
     * @param name    form name, shown in failures
     * @param hex     golden bytes as lowercase hex
     * @param edges   expected {@link #edges} outcome
     * @param value   value the golden bytes decode to
     * @param encoder current encoder
     * @param decoder current decoder
     * @throws Exception when a decoder rejects the golden bytes
     */
    public static <T> void pin(String name,
            String hex,
            String edges,
            T value,
            Encoder<T> encoder,
            Decoder<T> decoder) throws Exception {
        assertEquals(hex, hex(encoder.encode(value)), name + " encode");
        T decoded = decoder.decode(bytes(hex));
        assertEquals(value, decoded, name + " decode");
        assertEquals(hex, hex(encoder.encode(decoded)), name + " re-encode");
        assertEquals(edges, edges(decoder, bytes(hex)), name + " edges");
    }

    /**
     * Describes how a decoder treats one appended byte and a missing last byte.
     *
     * @param decoder decoder under test
     * @param golden  valid bytes
     * @return {@code trailing=<outcome> | truncated=<outcome>}
     */
    public static String edges(Decoder<?> decoder, byte[] golden) {
        byte[] trailing = Arrays.copyOf(golden, golden.length + 1);
        byte[] truncated = Arrays.copyOf(golden, golden.length - 1);
        return "trailing=" + outcome(decoder, trailing) + " | truncated=" + outcome(decoder, truncated);
    }

    /**
     * Runs a decoder and names what happened.
     *
     * @param decoder decoder
     * @param input   bytes
     * @return {@code accepted}, or the exception type, message and cause type
     */
    public static String outcome(Decoder<?> decoder, byte[] input) {
        try {
            decoder.decode(input);
            return "accepted";
        } catch (Exception failure) {
            String cause = failure.getCause() == null ? "" : " <- " + failure.getCause().getClass().getSimpleName();
            return failure.getClass().getSimpleName() + ": " + failure.getMessage() + cause;
        }
    }

    /**
     * Formats bytes.
     *
     * @param bytes bytes
     * @return lowercase hex
     */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Parses hex.
     *
     * @param hex lowercase or uppercase hex
     * @return bytes
     */
    public static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Fixed UUID {@code 12345678-9abc-def0-0fed-cba9876543NN}.
     *
     * @param n low byte
     * @return UUID
     */
    public static UUID id(int n) {
        return new UUID(0x1234_5678_9abc_def0L, 0x0fed_cba9_8765_4300L | n);
    }
}
