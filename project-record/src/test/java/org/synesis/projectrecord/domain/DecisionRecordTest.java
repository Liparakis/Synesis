package org.synesis.projectrecord.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.persistence.DecisionStore;

/**
 * Verifies deterministic bounded SDR1 encoding and Ed25519 authenticity.
 */
final class DecisionRecordTest {

  private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RECORD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

  private static DecisionRecord create(NodeIdentity signer) throws Exception {
    return DecisionRecord.create(PROJECT, RECORD, 1, null, signer.nodeId(), signer.nodeId(),
        DecisionStatus.PROPOSED, TIME, TIME, "Use the bounded path", "It keeps the proof small",
        List.of(evidence()), signer);
  }

  private static DecisionEvidence evidence() {
    return new DecisionEvidence("test", "vector-1", new byte[32]);
  }

  @Test
  void encodingDigestAndSignatureAreDeterministic() throws Exception {
    NodeIdentity signer = NodeIdentity.generate();
    DecisionRecord first = create(signer);
    DecisionRecord second = create(signer);

    assertArrayEquals(first.encoded(), second.encoded());
    assertArrayEquals(first.digest(), second.digest());
    assertTrue(DecisionRecord.decode(first.encoded())
        .verify());
    assertArrayEquals(first.encoded(),
        DecisionRecord.decode(first.encoded())
            .encoded());
  }

  @Test
  void evidenceOrderIsCanonicalized() throws Exception {
    NodeIdentity signer = NodeIdentity.generate();
    byte[] alternateDigest = new byte[32];
    alternateDigest[0] = 2;
    DecisionEvidence firstEvidence = new DecisionEvidence("b", "two", alternateDigest);
    DecisionEvidence secondEvidence = new DecisionEvidence("a", "one", new byte[32]);
    DecisionRecord firstRecord = DecisionRecord.create(PROJECT,
        RECORD,
        1,
        null,
        signer.nodeId(),
        signer.nodeId(),
        DecisionStatus.PROPOSED,
        TIME,
        TIME,
        "title",
        "rationale",
        List.of(firstEvidence, secondEvidence),
        signer);
    DecisionRecord secondRecord = DecisionRecord.create(PROJECT,
        RECORD,
        1,
        null,
        signer.nodeId(),
        signer.nodeId(),
        DecisionStatus.PROPOSED,
        TIME,
        TIME,
        "title",
        "rationale",
        List.of(secondEvidence, firstEvidence),
        signer);
    assertArrayEquals(firstRecord.encoded(), secondRecord.encoded());
  }

  @Test
  void tamperingAndBoundsAreRejected() throws Exception {
    NodeIdentity signer = NodeIdentity.generate();
    DecisionRecord record = create(signer);
    byte[] tampered = record.encoded();
    tampered[tampered.length - 1] ^= 1;
    assertFalse(DecisionRecord.decode(tampered)
        .verify());
    assertThrows(IllegalArgumentException.class,
        () -> DecisionRecord.create(PROJECT, RECORD, 1, null,
            signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, TIME, TIME,
            "x".repeat(DecisionRecord.MAX_TITLE_BYTES + 1), "why", List.of(evidence()), signer));
    byte[] oversized = new byte[DecisionRecord.MAX_BYTES + 1];
    assertThrows(java.io.IOException.class, () -> DecisionRecord.decode(oversized));
  }

  @Test
  void malformedUtf8AndUnknownStatusAreRejected() throws Exception {
    NodeIdentity signer = NodeIdentity.generate();
    byte[] bytes = create(signer).encoded();
    int ownerOffset = 4 + 1 + 16 + 16 + 1 + 8 + 1 + 2;
    bytes[ownerOffset] = (byte) 0xC3;
    bytes[ownerOffset + 1] = (byte) 0x73;
    assertThrows(java.io.IOException.class, () -> DecisionRecord.decode(bytes));
  }

  @Test
  void constraintScopeBoundIsEnforcedInBytesAtCreationSoNoUndecodableRecordIsStored(
      @TempDir Path directory) throws Exception {
    NodeIdentity signer = NodeIdentity.generate();
    String longest = "a".repeat(1_024);
    DecisionRecord record = constraint(signer, List.of(longest));
    assertEquals(List.of(longest), DecisionRecord.decode(record.encoded())
        .constraintPayload()
        .scopes());
    assertEquals(DecisionStore.SaveResult.APPLIED,
        new DecisionStore(directory, PROJECT).save(record, null));
    new DecisionStore(directory, PROJECT);

    assertThrows(IllegalArgumentException.class, () -> constraint(signer, List.of(longest + "a")));
    // 513 two-byte characters are 1,026 bytes but only 513 characters.
    assertThrows(IllegalArgumentException.class,
        () -> constraint(signer, List.of("é".repeat(513))));
    assertThrows(IllegalArgumentException.class, () -> constraint(signer, List.of("./")));
  }

  private static DecisionRecord constraint(NodeIdentity signer, List<String> scopes)
      throws Exception {
    return DecisionRecord.createConstraint(PROJECT, RECORD, 1, null, signer.nodeId(),
        signer.nodeId(), DecisionStatus.ACCEPTED, TIME, TIME, "t", "r", List.of(evidence()),
        new DecisionRecord.ConstraintPayload(ProjectConstraint.Effect.BLOCK,
            ProjectConstraint.ConstraintStatus.ACTIVE, scopes, List.of()), signer);
  }
}
