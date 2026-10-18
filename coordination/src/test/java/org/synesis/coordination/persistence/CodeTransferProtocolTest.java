package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Code over Link, version 1: exact bytes of every message and strict rejection of everything else.
 */
final class CodeTransferProtocolTest {

    private static final UUID PROJECT = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final String HEADER = "5343583101";
    private static final String PROJECT_HEX = "00112233445566778899aabbccddeeff";
    private static final String COMMIT = "a".repeat(40);
    private static final String COMMIT_256 = "c".repeat(64);
    private static final String PIECE = "b".repeat(64);
    private static final byte[] SHA256_OF_010203 = HexFormat.of()
            .parseHex("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

    /** Message and its exact bytes, in the order of the kinds. */
    private static Map<CodeTransferMessage, String> vectors() {
        Map<CodeTransferMessage, String> vectors = new LinkedHashMap<>();
        vectors.put(new CodeTransferMessage.Want(PROJECT, "HEAD", List.of(COMMIT, COMMIT_256)),
                HEADER + "01" + PROJECT_HEX + "04" + ascii("HEAD") + "0002" + "28" + ascii(COMMIT) + "40" + ascii(COMMIT_256));
        vectors.put(new CodeTransferMessage.Want(PROJECT, "refs/synesis/snapshots/snap_1", List.of()),
                HEADER + "01" + PROJECT_HEX + "1d" + ascii("refs/synesis/snapshots/snap_1") + "0000");
        vectors.put(new CodeTransferMessage.Chunk(PROJECT, PIECE, 3),
                HEADER + "02" + PROJECT_HEX + "40" + ascii(PIECE) + "00000003");
        vectors.put(new CodeTransferMessage.Piece(PIECE, 262_145, 2, COMMIT, COMMIT_256),
                HEADER + "10" + "40" + ascii(PIECE) + "0000000000040001" + "00000002" + "28" + ascii(COMMIT) + "40" + ascii(COMMIT_256));
        vectors.put(new CodeTransferMessage.Piece(PIECE, 0, 0, COMMIT, COMMIT),
                HEADER + "10" + "40" + ascii(PIECE) + "0000000000000000" + "00000000" + "28" + ascii(COMMIT) + "28" + ascii(COMMIT));
        vectors.put(CodeTransferMessage.ChunkData.of(1, new byte[]{1, 2, 3}),
                HEADER + "11" + "00000001" + "00000003" + "010203" + HexFormat.of().formatHex(SHA256_OF_010203));
        vectors.put(new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_SERVED),
                HEADER + "12" + "0f" + ascii("CODE_NOT_SERVED"));
        return vectors;
    }

    private static String ascii(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static void rejects(byte[] bytes) {
        CoordinationException failure = assertThrows(CoordinationException.class, () -> CodeTransferCodec.decode(bytes));
        assertEquals("CODE_TRANSFER_MESSAGE_INVALID", failure.code());
    }

    @Test
    void everyMessageKeepsItsExactBytesAndRoundTrips() throws Exception {
        for (Map.Entry<CodeTransferMessage, String> vector : vectors().entrySet()) {
            byte[] encoded = CodeTransferCodec.encode(vector.getKey());
            assertEquals(vector.getValue(), HexFormat.of().formatHex(encoded));
            assertTrue(CodeTransferCodec.looksLike(encoded));
            assertFalse(ReplicationCodec.looksLike(encoded));
            CodeTransferMessage decoded = CodeTransferCodec.decode(hex(vector.getValue()));
            assertEquals(vector.getKey().getClass(), decoded.getClass());
            assertArrayEquals(encoded, CodeTransferCodec.encode(decoded));
        }
        assertFalse(CodeTransferCodec.looksLike(hex("53524c3101")));
        assertFalse(CodeTransferCodec.looksLike(null));
    }

    @Test
    void aChunkKnowsWhetherItsBytesStillMatchTheirHash() {
        assertTrue(CodeTransferMessage.ChunkData.of(0, new byte[]{1, 2, 3}).intact());
        assertFalse(new CodeTransferMessage.ChunkData(0, new byte[]{1, 2, 4}, SHA256_OF_010203).intact());
    }

    @Test
    void aMessageCutShortOrLongerThanItsFieldsIsRejected() {
        for (String vector : vectors().values()) {
            byte[] bytes = hex(vector);
            for (int length = 0; length < bytes.length; length++) {
                rejects(Arrays.copyOf(bytes, length));
            }
            rejects(Arrays.copyOf(bytes, bytes.length + 1));
        }
    }

    @Test
    void aWrongMagicVersionOrKindIsRejected() {
        rejects(hex("53435832" + "01" + "12" + "01" + "41"));
        rejects(hex("53435831" + "02" + "12" + "01" + "41"));
        rejects(hex(HEADER + "00"));
        rejects(hex(HEADER + "03" + PROJECT_HEX));
        rejects(hex(HEADER + "13" + "01" + "41"));
        rejects(hex(HEADER + "ff"));
    }

    @Test
    void everyFieldOutsideItsBoundIsRejected() {
        String want = HEADER + "01" + PROJECT_HEX;
        rejects(hex(want + "00" + "0000"));
        rejects(hex(want + "04" + ascii("HEAD") + "0001" + "27" + ascii("a".repeat(39))));
        rejects(hex(want + "04" + ascii("HEAD") + "0001" + "28" + ascii("A".repeat(40))));
        rejects(hex(want + "04" + ascii("HEAD") + "0101" + ("28" + ascii(COMMIT)).repeat(257)));
        rejects(hex(want + "04" + ascii("HEAD") + "ffff"));
        rejects(hex(HEADER + "02" + PROJECT_HEX + "40" + ascii("B".repeat(64)) + "00000000"));
        rejects(hex(HEADER + "02" + PROJECT_HEX + "28" + ascii(COMMIT) + "00000000"));
        rejects(hex(HEADER + "02" + PROJECT_HEX + "40" + ascii(PIECE) + "80000000"));
        String piece = HEADER + "10" + "40" + ascii(PIECE);
        String tips = "28" + ascii(COMMIT) + "28" + ascii(COMMIT);
        rejects(hex(piece + "0000000000040001" + "00000001" + tips));
        rejects(hex(piece + "0000000000040000" + "00000002" + tips));
        rejects(hex(piece + "ffffffffffffffff" + "00000000" + tips));
        rejects(hex(piece + "0000000000000001" + "00000001" + "28" + ascii(COMMIT) + "04" + ascii("HEAD")));
        String data = HEADER + "11" + "00000000";
        rejects(hex(data + "00000000" + "00".repeat(32)));
        rejects(hex(data + String.format("%08x", CodeTransferMessage.CHUNK_BYTES + 1) + "01"));
        rejects(hex(data + "ffffffff" + "01"));
        rejects(hex(HEADER + "11" + "ffffffff" + "00000001" + "01" + "00".repeat(32)));
        rejects(hex(HEADER + "12" + "00"));
        rejects(hex(HEADER + "12" + "41" + "41".repeat(65)));
        rejects(new byte[CodeTransferMessage.MAX_BYTES + 1]);
        rejects(null);
    }

    @Test
    void whatCannotBeSentIsRefusedBeforeItIsEncoded() {
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Want(PROJECT, "", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Want(PROJECT, "x".repeat(256), List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Want(PROJECT, "HEAD", List.of("main"))));
        assertThrows(IllegalArgumentException.class, () -> CodeTransferCodec.encode(new CodeTransferMessage.Want(
                PROJECT, "HEAD", java.util.Collections.nCopies(CodeTransferMessage.MAX_HAVES + 1, COMMIT))));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Chunk(PROJECT, "../x", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Piece(PIECE, 10, 2, COMMIT, COMMIT)));
        assertThrows(IllegalArgumentException.class, () -> CodeTransferCodec.encode(
                CodeTransferMessage.ChunkData.of(0, new byte[CodeTransferMessage.CHUNK_BYTES + 1])));
        assertThrows(IllegalArgumentException.class,
                () -> CodeTransferCodec.encode(new CodeTransferMessage.Unavailable("X".repeat(65))));
    }

    @Test
    void aFullChunkFitsOneLinkStream() {
        byte[] full = CodeTransferCodec.encode(CodeTransferMessage.ChunkData.of(0, new byte[CodeTransferMessage.CHUNK_BYTES]));
        assertTrue(full.length <= CodeTransferMessage.MAX_BYTES);
    }
}
