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
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The replication protocol, version 1: exact bytes of every message and strict rejection of everything else.
 */
final class ReplicationProtocolTest {

    private static final UUID PROJECT = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final String HEADER = "53524c3101";
    private static final String PROJECT_HEX = "00112233445566778899aabbccddeeff";

    /** Message and its exact bytes, in the order of the kinds. */
    private static Map<ReplicationMessage, String> vectors() {
        Map<ReplicationMessage, String> vectors = new LinkedHashMap<>();
        vectors.put(new ReplicationMessage.Propose(PROJECT, new byte[]{1, 2, 3}),
                HEADER + "01" + PROJECT_HEX + "00000003" + "010203");
        vectors.put(new ReplicationMessage.Pull(PROJECT, 5, 10, 1000),
                HEADER + "02" + PROJECT_HEX + "0000000000000005" + "0000000a" + "000003e8");
        vectors.put(new ReplicationMessage.Notice(PROJECT, 7), HEADER + "03" + PROJECT_HEX + "0000000000000007");
        vectors.put(new ReplicationMessage.Outcome(new ProposalOutcome.Accepted(9)), HEADER + "10" + "0000000000000009");
        vectors.put(new ReplicationMessage.Outcome(new ProposalOutcome.HeadMoved(4)), HEADER + "11" + "0000000000000004");
        vectors.put(new ReplicationMessage.Outcome(new ProposalOutcome.Refused("OVERLAPPING_CLAIM")),
                HEADER + "12" + "11" + HexFormat.of().formatHex("OVERLAPPING_CLAIM".getBytes(StandardCharsets.UTF_8)));
        vectors.put(new ReplicationMessage.Outcome(new ProposalOutcome.Unreachable()), HEADER + "13");
        vectors.put(new ReplicationMessage.Outcome(new ProposalOutcome.NoAnswer()), HEADER + "14");
        vectors.put(new ReplicationMessage.Entries(List.of(new byte[]{1, 2}, new byte[]{3})),
                HEADER + "15" + "0002" + "00000002" + "0102" + "00000001" + "03");
        vectors.put(new ReplicationMessage.Noticed(), HEADER + "16");
        return vectors;
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static void assertEquivalent(ReplicationMessage expected, ReplicationMessage actual) {
        // Records holding arrays compare by identity, so compare the bytes through the codec.
        assertArrayEquals(ReplicationCodec.encode(expected), ReplicationCodec.encode(actual));
        assertEquals(expected.getClass(), actual.getClass());
    }

    private static void rejects(byte[] bytes) {
        CoordinationException failure = assertThrows(CoordinationException.class, () -> ReplicationCodec.decode(bytes));
        assertEquals("REPLICATION_MESSAGE_INVALID", failure.code());
    }

    @Test
    void everyMessageKeepsItsExactBytesAndRoundTrips() throws Exception {
        for (Map.Entry<ReplicationMessage, String> vector : vectors().entrySet()) {
            byte[] encoded = ReplicationCodec.encode(vector.getKey());
            assertEquals(vector.getValue(), HexFormat.of().formatHex(encoded));
            assertTrue(ReplicationCodec.looksLike(encoded));
            assertEquivalent(vector.getKey(), ReplicationCodec.decode(hex(vector.getValue())));
        }
        assertFalse(ReplicationCodec.looksLike(new byte[]{'S', 'R', 'P', '1'}));
        assertFalse(ReplicationCodec.looksLike(null));
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
        rejects(hex("53524c3200" + "16"));
        rejects(hex("53524c3102" + "16"));
        rejects(hex("53524c3100" + "16"));
        rejects(hex(HEADER + "00"));
        rejects(hex(HEADER + "04" + PROJECT_HEX));
        rejects(hex(HEADER + "0f"));
        rejects(hex(HEADER + "17"));
        rejects(hex(HEADER + "ff"));
    }

    @Test
    void everyFieldOutsideItsBoundIsRejected() {
        String pull = HEADER + "02" + PROJECT_HEX;
        rejects(hex(pull + "ffffffffffffffff" + "0000000a" + "000003e8"));
        rejects(hex(pull + "0000000000000005" + "00000000" + "000003e8"));
        rejects(hex(pull + "0000000000000005" + "00000401" + "000003e8"));
        rejects(hex(pull + "0000000000000005" + "0000000a" + "00000000"));
        rejects(hex(pull + "0000000000000005" + "0000000a" + "ffffffff"));
        rejects(hex(pull + "0000000000000005" + "0000000a" + String.format("%08x", ReplicationMessage.MAX_BYTES + 1)));
        rejects(hex(HEADER + "03" + PROJECT_HEX + "8000000000000000"));
        rejects(hex(HEADER + "10" + "ffffffffffffffff"));
        rejects(hex(HEADER + "11" + "ffffffffffffffff"));
        String propose = HEADER + "01" + PROJECT_HEX;
        rejects(hex(propose + "00000000"));
        rejects(hex(propose + "ffffffff" + "01"));
        rejects(hex(propose + String.format("%08x", ReplicationMessage.MAX_ENTRY_BYTES + 1) + "01"));
        rejects(hex(propose + "00000004" + "010203"));
        rejects(hex(HEADER + "12" + "00"));
        rejects(hex(HEADER + "12" + "41" + "41".repeat(65)));
        rejects(hex(HEADER + "12" + "05" + "41414141"));
        rejects(hex(HEADER + "15" + String.format("%04x", ReplicationMessage.MAX_ENTRIES + 1)));
        rejects(hex(HEADER + "15" + "0001" + "00000000"));
        rejects(new byte[ReplicationMessage.MAX_BYTES + 1]);
        rejects(null);
    }

    @Test
    void whatCannotBeSentIsRefusedBeforeItIsEncoded() {
        assertThrows(IllegalArgumentException.class, () -> ReplicationCodec.encode(
                new ReplicationMessage.Propose(PROJECT, new byte[ReplicationMessage.MAX_ENTRY_BYTES + 1])));
        assertThrows(IllegalArgumentException.class,
                () -> ReplicationCodec.encode(new ReplicationMessage.Propose(PROJECT, new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> ReplicationCodec.encode(
                new ReplicationMessage.Outcome(new ProposalOutcome.Refused("X".repeat(65)))));
        assertThrows(IllegalArgumentException.class, () -> ReplicationCodec.encode(
                new ReplicationMessage.Outcome(new ProposalOutcome.Refused(""))));
        List<byte[]> big = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new byte[ReplicationMessage.MAX_ENTRY_BYTES]).toList();
        assertThrows(IllegalArgumentException.class, () -> ReplicationCodec.encode(
                new ReplicationMessage.Entries(java.util.stream.Stream.concat(big.stream(), big.stream()).toList())));
    }

    @Test
    void theMessageBoundIsTheLinkApplicationPayloadBoundAndFarAboveAControlFrame() {
        assertEquals(512 * 1024, ReplicationMessage.MAX_BYTES);
        assertTrue(ReplicationMessage.MAX_BYTES > 4096);
        // A full page of 256 KiB plus one entry larger than the soft bound still fits one message.
        assertTrue(256 * 1024 + 2 * ReplicationMessage.MAX_ENTRY_BYTES + 1024 < ReplicationMessage.MAX_BYTES);
    }

    @Test
    void aTransportThatDidNotSendIsUnreachableAndOneThatMayHaveSentHasNoAnswer() throws Exception {
        ExchangeLeaderChannel notSent = new ExchangeLeaderChannel(PROJECT, request -> {
            throw new java.net.ConnectException("refused");
        });
        ExchangeLeaderChannel lost = new ExchangeLeaderChannel(PROJECT, request -> {
            throw new java.io.IOException("reset");
        });
        ExchangeLeaderChannel garbage = new ExchangeLeaderChannel(PROJECT, request -> new byte[]{1, 2, 3});
        ExchangeLeaderChannel wrongKind = new ExchangeLeaderChannel(PROJECT,
                request -> ReplicationCodec.encode(new ReplicationMessage.Noticed()));

        assertEquals(new ProposalOutcome.Unreachable(), notSent.propose(new byte[]{1}));
        assertEquals(new ProposalOutcome.NoAnswer(), lost.propose(new byte[]{1}));
        assertEquals(new ProposalOutcome.NoAnswer(), garbage.propose(new byte[]{1}));
        assertEquals(new ProposalOutcome.NoAnswer(), wrongKind.propose(new byte[]{1}));
        assertEquals("LEADER_UNREACHABLE", assertThrows(CoordinationException.class, () -> notSent.pull(0, 1, 1)).code());
        assertEquals("LEADER_UNREACHABLE", assertThrows(CoordinationException.class, () -> lost.pull(0, 1, 1)).code());
        ExchangeLeaderChannel refusing = new ExchangeLeaderChannel(PROJECT, request -> ReplicationCodec.encode(
                new ReplicationMessage.Outcome(new ProposalOutcome.Refused("PROPOSER_NOT_A_MEMBER"))));
        assertEquals("PROPOSER_NOT_A_MEMBER", assertThrows(CoordinationException.class, () -> refusing.pull(0, 1, 1)).code());
        Function<byte[], byte[]> entries = request -> ReplicationCodec.encode(
                new ReplicationMessage.Entries(List.of(new byte[]{9})));
        assertEquals(1, new ExchangeLeaderChannel(PROJECT, entries::apply).pull(0, 1, 1).size());
    }
}
