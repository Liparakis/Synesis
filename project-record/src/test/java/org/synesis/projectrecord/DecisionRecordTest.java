package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies deterministic bounded SDR1 encoding and Ed25519 authenticity. */
final class DecisionRecordTest {
    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECORD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void encodingDigestAndSignatureAreDeterministic() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionRecord first = create(signer);
        DecisionRecord second = create(signer);

        assertArrayEquals(first.encoded(), second.encoded());
        assertArrayEquals(first.digest(), second.digest());
        assertTrue(DecisionRecord.decode(first.encoded()).verify());
        assertArrayEquals(first.encoded(), DecisionRecord.decode(first.encoded()).encoded());
    }

    @Test
    void evidenceOrderIsCanonicalized() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        byte[] alternateDigest = new byte[32];
        alternateDigest[0] = 2;
        DecisionEvidence firstEvidence = new DecisionEvidence("b", "two", alternateDigest);
        DecisionEvidence secondEvidence = new DecisionEvidence("a", "one", new byte[32]);
        DecisionRecord firstRecord = DecisionRecord.create(PROJECT, RECORD, 1, null, signer.nodeId(), signer.nodeId(),
                DecisionStatus.PROPOSED, TIME, TIME, "title", "rationale", List.of(firstEvidence, secondEvidence), signer);
        DecisionRecord secondRecord = DecisionRecord.create(PROJECT, RECORD, 1, null, signer.nodeId(), signer.nodeId(),
                DecisionStatus.PROPOSED, TIME, TIME, "title", "rationale", List.of(secondEvidence, firstEvidence), signer);
        assertArrayEquals(firstRecord.encoded(), secondRecord.encoded());
    }

    @Test
    void tamperingAndBoundsAreRejected() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionRecord record = create(signer);
        byte[] tampered = record.encoded();
        tampered[tampered.length - 1] ^= 1;
        assertFalse(DecisionRecord.decode(tampered).verify());
        assertThrows(IllegalArgumentException.class, () -> DecisionRecord.create(PROJECT, RECORD, 1, null,
                signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, TIME, TIME,
                "x".repeat(DecisionRecord.MAX_TITLE_BYTES + 1), "why", List.of(evidence()), signer));
        byte[] oversized = new byte[DecisionRecord.MAX_BYTES + 1];
        assertThrows(java.io.IOException.class, () -> DecisionRecord.decode(oversized));
    }

    @Test
    void malformedUtf8AndUnknownStatusAreRejected() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        byte[] bytes = create(signer).encoded();
        bytes[5 + 16 + 16 + 8 + 1 + 2] = (byte) 0xC3;
        bytes[5 + 16 + 16 + 8 + 1 + 3] = (byte) 0x28;
        assertThrows(java.io.IOException.class, () -> DecisionRecord.decode(bytes));
    }

    private static DecisionRecord create(Ed25519Signer signer) throws Exception {
        return DecisionRecord.create(PROJECT, RECORD, 1, null, signer.nodeId(), signer.nodeId(),
                DecisionStatus.PROPOSED, TIME, TIME, "Use the bounded path", "It keeps the proof small",
                List.of(evidence()), signer);
    }

    private static DecisionEvidence evidence() { return new DecisionEvidence("test", "vector-1", new byte[32]); }
}
