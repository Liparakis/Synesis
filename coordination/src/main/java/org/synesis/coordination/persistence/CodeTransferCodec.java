package org.synesis.coordination.persistence;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Wire form of {@link CodeTransferMessage}, version 1: {@code "SCX1"}, a version byte, a kind byte and the fields
 * of that kind, big-endian. Every length is checked before allocation, every field against its bound (by the
 * message's own constructor), and no byte may be left over. The layout is in {@code docs/protocol/WIRE_FORMAT.md}.
 */
public final class CodeTransferCodec {

    private static final int MAGIC = 0x53435831;
    private static final int VERSION = 1;

    private static final int WANT = 1;
    private static final int CHUNK = 2;
    private static final int FETCH = 3;
    private static final int PIECE = 16;
    private static final int CHUNK_DATA = 17;
    private static final int UNAVAILABLE = 18;
    private static final int FETCHED = 19;

    private static final int SHA256_BYTES = 32;
    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern PIECE_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}");

    private CodeTransferCodec() {
    }

    /**
     * Tells whether text is a full commit ID: 40 (SHA-1) or 64 (SHA-256) lowercase hexadecimal digits.
     *
     * @param value candidate text
     * @return true for a full commit ID
     */
    public static boolean isCommitId(String value) {
        return value != null && COMMIT_ID.matcher(value).matches();
    }

    /**
     * Tells whether a payload is addressed to this protocol.
     *
     * @param payload candidate payload
     * @return true when it starts with the magic number
     */
    public static boolean looksLike(byte[] payload) {
        return payload != null && payload.length >= Integer.BYTES
                && (((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16) | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff)) == MAGIC;
    }

    /**
     * Encodes a message.
     *
     * @param message the message
     * @return the bytes, at most {@link CodeTransferMessage#MAX_BYTES}, which every message within its bounds fits
     */
    public static byte[] encode(CodeTransferMessage message) {
        return WireFormat.encode(MAGIC, out -> {
            out.writeByte(VERSION);
            switch (message) {
                case CodeTransferMessage.Want want -> {
                    out.writeByte(WANT);
                    WireFormat.writeUuid(out, want.project());
                    writeText(out, want.wanted());
                    out.writeShort(want.have().size());
                    for (String have : want.have()) {
                        writeText(out, have);
                    }
                }
                case CodeTransferMessage.Chunk chunk -> {
                    out.writeByte(CHUNK);
                    WireFormat.writeUuid(out, chunk.project());
                    writeText(out, chunk.pieceId());
                    out.writeInt(chunk.index());
                }
                case CodeTransferMessage.Fetch fetch -> {
                    out.writeByte(FETCH);
                    WireFormat.writeUuid(out, fetch.project());
                    writeText(out, fetch.wanted());
                }
                case CodeTransferMessage.Fetched fetched -> {
                    out.writeByte(FETCHED);
                    writeText(out, fetched.commit());
                }
                case CodeTransferMessage.Piece piece -> {
                    out.writeByte(PIECE);
                    writeText(out, piece.pieceId());
                    out.writeLong(piece.totalBytes());
                    out.writeInt(piece.chunkCount());
                    writeText(out, piece.tip());
                    writeText(out, piece.target());
                    writeText(out, piece.branch());
                }
                case CodeTransferMessage.ChunkData data -> {
                    out.writeByte(CHUNK_DATA);
                    out.writeInt(data.index());
                    out.writeInt(data.bytes().length);
                    out.write(data.bytes());
                    out.write(data.sha256());
                }
                case CodeTransferMessage.Unavailable unavailable -> {
                    out.writeByte(UNAVAILABLE);
                    writeText(out, unavailable.code());
                }
            }
        });
    }

    /**
     * Decodes and strictly validates one message.
     *
     * @param bytes the encoded message
     * @return the message
     * @throws CoordinationException {@code CODE_TRANSFER_MESSAGE_INVALID} for a wrong magic, version or kind, a
     *                               field out of its bound, truncation, or a byte left over
     */
    public static CodeTransferMessage decode(byte[] bytes) throws CoordinationException {
        if (bytes == null || bytes.length < 6 || bytes.length > CodeTransferMessage.MAX_BYTES) {
            throw invalid(null);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw invalid(null);
            }
            CodeTransferMessage message = switch (in.readUnsignedByte()) {
                case WANT -> new CodeTransferMessage.Want(WireFormat.readUuid(in), readText(in), readHaves(in));
                case CHUNK -> new CodeTransferMessage.Chunk(WireFormat.readUuid(in), readText(in), in.readInt());
                case PIECE -> new CodeTransferMessage.Piece(readText(in), in.readLong(), in.readInt(), readText(in),
                        readText(in), readText(in));
                case CHUNK_DATA -> new CodeTransferMessage.ChunkData(in.readInt(), readChunk(in),
                        exactly(in, SHA256_BYTES));
                case UNAVAILABLE -> new CodeTransferMessage.Unavailable(readText(in));
                case FETCH -> new CodeTransferMessage.Fetch(WireFormat.readUuid(in), readText(in));
                case FETCHED -> new CodeTransferMessage.Fetched(readText(in));
                default -> throw invalid(null);
            };
            if (in.available() != 0) {
                throw invalid(null);
            }
            return message;
        } catch (CoordinationException invalid) {
            throw invalid;
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid(malformed);
        }
    }

    /** Refuses a field outside its bound; each message's constructor calls it. */
    static void require(boolean withinBounds) {
        if (!withinBounds) {
            throw new IllegalArgumentException("code transfer message field out of bounds");
        }
    }

    /**
     * Tells whether text can be the short name of a branch a peer reports: none (empty), or a name of letters,
     * digits, {@code . _ / -} that starts with a letter or digit and that Git's ref-name rules admit as far as a
     * pattern can tell. A receiver still asks Git ({@code check-ref-format}) before it creates the branch.
     *
     * @param value candidate text
     * @return true when it is empty or such a name
     */
    public static boolean isBranchName(String value) {
        return value != null && (value.isEmpty() || BRANCH.matcher(value).matches() && !value.contains("..")
                && !value.contains("//") && !value.endsWith("/") && !value.endsWith(".") && !value.endsWith(".lock"));
    }

    static boolean isPieceId(String value) {
        return value != null && PIECE_ID.matcher(value).matches();
    }

    static boolean fits(String value, int maxBytes) {
        if (value == null) {
            return false;
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return length >= 1 && length <= maxBytes;
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        return new String(exactly(in, in.readUnsignedByte()), StandardCharsets.UTF_8);
    }

    private static List<String> readHaves(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > CodeTransferMessage.MAX_HAVES) {
            throw invalid(null);
        }
        List<String> haves = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            haves.add(readText(in));
        }
        return List.copyOf(haves);
    }

    private static byte[] readChunk(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > CodeTransferMessage.CHUNK_BYTES) {
            throw invalid(null);
        }
        return exactly(in, length);
    }

    private static byte[] exactly(DataInputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw invalid(null);
        }
        return bytes;
    }

    private static CoordinationException invalid(Throwable cause) {
        return cause == null ? new CoordinationException("CODE_TRANSFER_MESSAGE_INVALID")
                : new CoordinationException("CODE_TRANSFER_MESSAGE_INVALID", cause);
    }
}
