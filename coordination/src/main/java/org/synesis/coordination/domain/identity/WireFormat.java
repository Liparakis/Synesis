package org.synesis.coordination.domain.identity;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;

/**
 * Binary primitives shared by the coordination wire formats: UUIDs, the encode envelope, and length-prefixed fields.
 *
 * <p>An instance is the rule for one kind of length-prefixed field: how many bytes a reader accepts, whether an empty
 * field is valid, and what each failure says. Writers check their own bound only when {@link #checkingWrites} was
 * called, because each format decides whether an oversized value is rejected on the way out or only by its reader.
 * A failed check surfaces as {@link CoordinationException}, which an {@link #encode} envelope reports as an
 * invariant error.</p>
 */
public final class WireFormat {

    private final int readMaxBytes;
    private final boolean allowEmpty;
    private final String readBoundMessage;
    private final String truncatedMessage;
    private final int writeMaxBytes;
    private final String writeBoundMessage;

    private WireFormat(int readMaxBytes,
            boolean allowEmpty,
            String readBoundMessage,
            String truncatedMessage,
            int writeMaxBytes,
            String writeBoundMessage) {
        this.readMaxBytes = readMaxBytes;
        this.allowEmpty = allowEmpty;
        this.readBoundMessage = readBoundMessage;
        this.truncatedMessage = truncatedMessage;
        this.writeMaxBytes = writeMaxBytes;
        this.writeBoundMessage = writeBoundMessage;
    }

    /**
     * Writes the fields that follow the magic number of one encoded form.
     */
    @FunctionalInterface
    public interface Fields {

        /**
         * Writes the fields.
         *
         * @param out destination positioned after the magic number
         * @throws IOException when a field is rejected
         */
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * Creates the rule for a field whose writer does not check the length.
     *
     * @param maxBytes         longest accepted field in bytes
     * @param allowEmpty       whether a zero-length field is valid
     * @param boundMessage     failure message for a length outside the bound
     * @param truncatedMessage failure message for input that ends inside the field
     * @return the field rule
     */
    public static WireFormat bounded(int maxBytes, boolean allowEmpty, String boundMessage, String truncatedMessage) {
        return new WireFormat(maxBytes, allowEmpty, boundMessage, truncatedMessage, Integer.MAX_VALUE, null);
    }

    /**
     * Returns this rule with a writer that rejects fields longer than a bound of its own.
     *
     * @param maxBytes     longest field the writer accepts in bytes
     * @param boundMessage failure message for a longer field
     * @return the field rule
     */
    public WireFormat checkingWrites(int maxBytes, String boundMessage) {
        return new WireFormat(readMaxBytes, allowEmpty, readBoundMessage, truncatedMessage, maxBytes, boundMessage);
    }

    /**
     * Encodes one form: the magic number followed by its fields.
     *
     * @param magic  magic number
     * @param fields writer of the fields
     * @return the encoded bytes
     * @throws CoordinationInvariantError when a field writer rejects its value
     */
    public static byte[] encode(int magic, Fields fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(magic);
            fields.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Writes a UUID as its two 64-bit halves.
     *
     * @param out destination
     * @param id  UUID
     * @throws IOException when writing fails
     */
    public static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    /**
     * Reads a UUID written by {@link #writeUuid}.
     *
     * @param in source
     * @return the UUID
     * @throws IOException when the input ends early
     */
    public static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    /**
     * Writes UTF-8 text as a length-prefixed field.
     *
     * @param out   destination
     * @param value text
     * @throws IOException when the text exceeds the write bound or writing fails
     */
    public void writeText(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads UTF-8 text written by {@link #writeText}.
     *
     * @param in source
     * @return the text
     * @throws IOException when the length is out of bounds or the input ends inside the field
     */
    public String readText(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    /**
     * Writes bytes as a length-prefixed field.
     *
     * @param out   destination
     * @param value bytes
     * @throws IOException when the bytes exceed the write bound or writing fails
     */
    public void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > writeMaxBytes) {
            throw new CoordinationException(writeBoundMessage);
        }
        out.writeInt(value.length);
        out.write(value);
    }

    /**
     * Reads bytes written by {@link #writeBytes}.
     *
     * @param in source
     * @return the bytes
     * @throws IOException when the length is out of bounds or the input ends inside the field
     */
    public byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < (allowEmpty ? 0 : 1) || length > readMaxBytes) {
            throw new CoordinationException(readBoundMessage);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException(truncatedMessage);
        }
        return bytes;
    }
}
