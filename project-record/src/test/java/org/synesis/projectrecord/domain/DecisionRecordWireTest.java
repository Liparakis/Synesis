package org.synesis.projectrecord.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact SDR2 bytes of one signed record per record type. The vectors were produced once
 * by the encoder; signatures are random, so they are embedded rather than regenerated.
 */
public final class DecisionRecordWireTest {

  private static final String OWNER =
      "sl1-3fa8bed0e5c5e15f0ae530f7ca705452e5e150a5c6a03d0b49374f706f1c89d4";
  /** Signed DECISION revision one, shared with the store layout test. */
  public static final String DECISION = "53445232021111111111111111111111111111111122222222222222222222222222222222000000"
      + "000000000001000044736c312d336661386265643065356335653135663061653533306637636137"
      + "30353435326535653135306135633661303364306234393337346637303666316338396434004473"
      + "6c312d33666138626564306535633565313566306165353330663763613730353435326535653135"
      + "306135633661303364306234393337346637303666316338396434010000019b76daa87b0000019b"
      + "76daac630032cea7cf81ceaecf83ceb720ceb1cf83cf86ceb1cebbcebfcf8dcf8220ceb4ceb9ceb1"
      + "ceb4cf81cebfcebcceaecf8220e29c930041ce91ceb9cf84ceb9cebfcebbcf8cceb3ceb7cf83ceb7"
      + "3a20cebcceb9cebacf81ceae20ceb1cf80cf8cceb4ceb5ceb9cebeceb72c206e61c3af7665206361"
      + "66c3a9020003646f6300084144522d30303838a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4"
      + "b5b6b7b8b9babbbcbdbebf0004746573740009766563746f722dc3a9000102030405060708090a0b"
      + "0c0d0e0f101112131415161718191a1b1c1d1e1f002c302a300506032b65700321001c74e6f313d5"
      + "46c8ac565144729a88892a63b709240909517d49706df48a0c500040b4fa4de608e839de9dd2595f"
      + "c4c44e8ec7ffffe868c0600bd2458116ce099ca3404cce46222a599f99a1da82b9ff9d684d4145f6"
      + "d3fecb057767e36d1360930f";
  /** Digest of {@link #DECISION}. */
  public static final String DECISION_DIGEST =
      "5d00301520ae8d54c35dd212bb909cfdc7418a420e6951a32852d0d92f7c454d";
  private static final String CONSTRAINT = "53445232021111111111111111111111111111111144444444444444444444444444444444010000"
      + "00000000000201000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f00"
      + "44736c312d3366613862656430653563356531356630616535333066376361373035343532653565"
      + "31353061356336613033643062343933373466373036663163383964340044736c312d3366613862"
      + "65643065356335653135663061653533306637636137303534353265356531353061356336613033"
      + "64306234393337346637303666316338396434000000019b76daa87b0000019b76dab04b0016cea0"
      + "ceb5cf81ceb9cebfcf81ceb9cf83cebccf8ccf820015426c6f636b2067656e657261746564207061"
      + "74687301000a636f6e73747261696e74000c73636f70653d7372632f2a2aa0a1a2a3a4a5a6a7a8a9"
      + "aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf01010200067372632f2a2a000a646f63732f"
      + "c3a92e6d640133333333333333333333333333333333002c302a300506032b65700321001c74e6f3"
      + "13d546c8ac565144729a88892a63b709240909517d49706df48a0c5000409ed164b791896b73e87e"
      + "f60fab8fded570d33b1b05bb6dd7fcc63bf94f3c08e9e51e43cdfc2ae14664d7d6078a285bf73adc"
      + "a23b9c7dd725203d4fefbea66308";
  private static final String CONSTRAINT_DIGEST =
      "a8996c6d3df7414834013771d34757e54b0d92671dc51e5983c3f09d798c135f";
  /** Project of {@link #DECISION}. */
  public static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00.123Z");

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static byte[] counting(int start) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (start + index);
    }
    return value;
  }

  @Test
  void decisionVectorDecodesVerifiesAndReencodesIdentically() throws Exception {
    DecisionRecord record = DecisionRecord.decode(bytes(DECISION));

    assertTrue(record.verify());
    assertEquals(PROJECT, record.projectId());
    assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), record.recordId());
    assertEquals(DecisionRecord.RecordType.DECISION, record.recordType());
    assertEquals(1, record.revision());
    assertNull(record.previousDigest());
    assertEquals(OWNER, record.ownerNodeId());
    assertEquals(OWNER, record.authorNodeId());
    assertEquals(DecisionStatus.ACCEPTED, record.status());
    assertEquals(CREATED, record.createdAt());
    assertEquals(CREATED.plusSeconds(1), record.updatedAt());
    assertEquals("Χρήση ασφαλούς διαδρομής ✓", record.title());
    assertEquals("Αιτιολόγηση: μικρή απόδειξη, naïve café", record.rationale());
    assertEquals(List.of(new DecisionEvidence("doc", "ADR-0088", counting(0xA0)),
        new DecisionEvidence("test", "vector-é", counting(0))), record.evidence());
    assertNull(record.constraintPayload());
    assertArrayEquals(bytes(DECISION), record.encoded());
    assertEquals(DECISION_DIGEST, record.digestHex());
  }

  @Test
  void constraintVectorDecodesVerifiesAndReencodesIdentically() throws Exception {
    DecisionRecord record = DecisionRecord.decode(bytes(CONSTRAINT));

    assertTrue(record.verify());
    assertEquals(UUID.fromString("44444444-4444-4444-4444-444444444444"), record.recordId());
    assertEquals(DecisionRecord.RecordType.PROJECT_CONSTRAINT, record.recordType());
    assertEquals(2, record.revision());
    assertArrayEquals(counting(0), record.previousDigest());
    assertEquals(OWNER, record.ownerNodeId());
    assertEquals(DecisionStatus.PROPOSED, record.status());
    assertEquals(CREATED.plusSeconds(2), record.updatedAt());
    assertEquals("Περιορισμός", record.title());
    assertEquals("Block generated paths", record.rationale());
    assertEquals(List.of(new DecisionEvidence("constraint", "scope=src/**", counting(0xA0))),
        record.evidence());
    DecisionRecord.ConstraintPayload payload = record.constraintPayload();
    assertEquals(ProjectConstraint.Effect.WARN, payload.effect());
    assertEquals(ProjectConstraint.ConstraintStatus.INACTIVE, payload.status());
    assertEquals(List.of("src/**", "docs/é.md"), payload.scopes());
    assertEquals(List.of(UUID.fromString("33333333-3333-3333-3333-333333333333")),
        payload.supersedes());
    assertArrayEquals(bytes(CONSTRAINT), record.encoded());
    assertEquals(CONSTRAINT_DIGEST, record.digestHex());
  }

  @Test
  void enumOrdinalsOnTheWireAreFixed() {
    assertEquals(List.of("PROPOSED", "ACCEPTED", "REJECTED", "SUPERSEDED"), names(
        DecisionStatus.values()));
    assertEquals(List.of("DECISION", "PROJECT_CONSTRAINT"), names(
        DecisionRecord.RecordType.values()));
    assertEquals(List.of("BLOCK", "WARN"), names(ProjectConstraint.Effect.values()));
    assertEquals(List.of("ACTIVE", "INACTIVE", "SUPERSEDED"), names(
        ProjectConstraint.ConstraintStatus.values()));
  }

  @Test
  void trailingBytesAndEveryTruncationAreRejectedAsIoException() {
    for (String vector : List.of(DECISION, CONSTRAINT)) {
      byte[] encoded = bytes(vector);
      assertThrows(IOException.class, () -> DecisionRecord.decode(Arrays.copyOf(encoded,
          encoded.length + 1)));
      for (int length = 0; length < encoded.length; length++) {
        byte[] truncated = Arrays.copyOf(encoded, length);
        assertThrows(IOException.class, () -> DecisionRecord.decode(truncated));
      }
    }
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
