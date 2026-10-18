package org.synesis.coordination.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.synesis.link.transport.control.ApplicationStreamCodec;

/**
 * What two computers of one project say to move code over Link (ADR-0098 step 3, "Code over Link").
 * {@link CodeTransferCodec} is the wire form.
 *
 * <p>Every message checks its fields against the wire bounds when it is made, so a piece ID or a commit ID that a
 * receiver builds a file name, a ref name or a Git argument from is always well formed.</p>
 *
 * <p>A {@link Want} names one thing the asker wants; the holder resolves it itself, cuts the history between the
 * asker's haves and it into pieces, and answers the next {@link Piece} or why it will not ({@link Unavailable}).
 * The asker then takes the piece one {@link Chunk} per exchange, so every message fits one Link stream.</p>
 */
public sealed interface CodeTransferMessage {

    /**
     * Largest encoded message. It is the Link application payload bound, so any message fits one stream.
     */
    int MAX_BYTES = ApplicationStreamCodec.MAX_PAYLOAD_BYTES;

    /**
     * Size of every chunk of a piece but its last, which holds the rest.
     */
    int CHUNK_BYTES = 256 * 1024;

    /**
     * Most commits one {@link Want} names as already held.
     */
    int MAX_HAVES = 256;

    /**
     * Longest wanted name in UTF-8 bytes.
     */
    int MAX_NAME_BYTES = 255;

    /**
     * Longest refusal code in UTF-8 bytes.
     */
    int MAX_CODE_BYTES = 64;

    /**
     * The wanted name is not something this computer serves, or a commit no entry of its log names.
     */
    String NOT_SERVED = "CODE_NOT_SERVED";

    /**
     * The wanted name is served, but this computer does not hold its commit.
     */
    String NOT_HELD = "CODE_NOT_HELD";

    /**
     * The piece is not, or no longer, kept, or the chunk lies outside it; asking again prepares it again.
     */
    String PIECE_UNKNOWN = "CODE_PIECE_UNKNOWN";

    /**
     * The holder could not prepare or read the piece.
     */
    String PIECE_FAILED = "CODE_PIECE_FAILED";

    /**
     * What arrived is not the piece that was announced: a chunk that fails its hash, or a bundle Git refuses.
     */
    String PIECE_CORRUPT = "CODE_PIECE_CORRUPT";

    /**
     * What a requester sends; the holder answers each with a {@link Piece}, a {@link ChunkData} or an
     * {@link Unavailable}.
     */
    sealed interface Request extends CodeTransferMessage {

    }

    /**
     * Asks for the next piece towards a wanted commit.
     *
     * @param project the project
     * @param wanted  a snapshot or prepared ref ({@code refs/synesis/snapshots/<id>}, {@code refs/synesis/prepared/<id>}),
     *                {@code HEAD} for the head of the holder's checked-out branch, or a commit ID
     * @param have    commit IDs the requester already holds
     */
    record Want(UUID project, String wanted, List<String> have) implements Request {

        /**
         * Checks the bounds.
         *
         * @throws IllegalArgumentException when a field is out of its bound
         */
        public Want {
            have = List.copyOf(have);
            CodeTransferCodec.require(project != null && CodeTransferCodec.fits(wanted, MAX_NAME_BYTES)
                    && have.size() <= MAX_HAVES && have.stream().allMatch(CodeTransferCodec::isCommitId));
        }
    }

    /**
     * Asks for one chunk of an announced piece.
     *
     * @param project the project
     * @param pieceId the piece, as {@link Piece#pieceId()} named it
     * @param index   the chunk, from zero
     */
    record Chunk(UUID project, String pieceId, int index) implements Request {

        /**
         * Checks the bounds.
         *
         * @throws IllegalArgumentException when a field is out of its bound
         */
        public Chunk {
            CodeTransferCodec.require(project != null && CodeTransferCodec.isPieceId(pieceId) && index >= 0);
        }
    }

    /**
     * The next piece: a Git bundle holding the history from the requester's haves to {@code tip}.
     *
     * @param pieceId    64 lowercase hexadecimal digits naming the piece
     * @param totalBytes the bundle's size; zero when the requester's haves already contain {@code target}
     * @param chunkCount the number of chunks, {@code totalBytes} divided by {@link #CHUNK_BYTES} rounded up
     * @param tip        the commit the bundle brings
     * @param target     the commit the wanted name resolved to; the transfer is done when {@code tip} equals it
     */
    record Piece(String pieceId, long totalBytes, int chunkCount, String tip, String target) implements CodeTransferMessage {

        /**
         * Checks the bounds.
         *
         * @throws IllegalArgumentException when a field is out of its bound
         */
        public Piece {
            CodeTransferCodec.require(CodeTransferCodec.isPieceId(pieceId) && totalBytes >= 0
                    && chunkCount == (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES
                    && CodeTransferCodec.isCommitId(tip) && CodeTransferCodec.isCommitId(target));
        }
    }

    /**
     * One chunk of a piece and its SHA-256.
     *
     * @param index  the chunk, from zero
     * @param bytes  the chunk's bytes
     * @param sha256 the SHA-256 of {@code bytes}, 32 bytes
     */
    record ChunkData(int index, byte[] bytes, byte[] sha256) implements CodeTransferMessage {

        /**
         * Checks the bounds; whether the bytes match the hash is {@link #intact()}.
         *
         * @throws IllegalArgumentException when a field is out of its bound
         */
        public ChunkData {
            CodeTransferCodec.require(index >= 0 && bytes.length >= 1 && bytes.length <= CHUNK_BYTES
                    && sha256.length == 32);
        }

        /**
         * Creates a chunk with the hash of its bytes.
         *
         * @param index the chunk
         * @param bytes the chunk's bytes
         * @return the chunk
         */
        public static ChunkData of(int index, byte[] bytes) {
            return new ChunkData(index, bytes, digest(bytes));
        }

        /**
         * Tells whether the bytes still match the hash they were sent with.
         *
         * @return true when they do
         */
        public boolean intact() {
            return MessageDigest.isEqual(digest(bytes), sha256);
        }

        private static byte[] digest(byte[] bytes) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (NoSuchAlgorithmException required) {
                throw new IllegalStateException("every Java runtime provides SHA-256", required);
            }
        }
    }

    /**
     * Why the holder will not answer a request with what it asked for.
     *
     * @param code one of the codes of this interface
     */
    record Unavailable(String code) implements CodeTransferMessage {

        /**
         * Checks the bounds.
         *
         * @throws IllegalArgumentException when the code is empty or too long
         */
        public Unavailable {
            CodeTransferCodec.require(CodeTransferCodec.fits(code, MAX_CODE_BYTES));
        }
    }
}
