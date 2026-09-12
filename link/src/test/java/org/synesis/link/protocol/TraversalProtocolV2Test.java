package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies the explicit v2 return-target wire contract and its binding rules.
 */
final class TraversalProtocolV2Test {

  private static final Instant ISSUED = Instant.parse("2026-09-11T08:00:00Z");
  private static final Instant EXPIRES = ISSUED.plusSeconds(60);
  private static final UUID PROJECT_ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PROJECT_TWO = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static CandidateDescriptor descriptor(NodeIdentity identity, int port) throws Exception {
    return CandidateDescriptor.create(identity, ISSUED, EXPIRES,
        List.of(new Candidate(CandidateType.MANUAL,
            InetAddress.getByName("198.51.100.1"), port, 1)));
  }

  private static byte[] digest(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static SessionInvitation invitation(NodeIdentity host, UUID session,
      CandidateDescriptor descriptor) throws Exception {
    return SessionInvitation.create(host, session, ProtocolVersion.V1, ISSUED, EXPIRES,
        new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
  }

  private static TraversalOffer offer(NodeIdentity host, UUID session,
      SessionInvitation invitation, CandidateDescriptor descriptor, UUID projectId) throws Exception {
    return TraversalOffer.createV2(host, null, session, digest(invitation.encoded()), ISSUED,
        EXPIRES, new byte[TraversalOffer.NONCE_BYTES], descriptor, ReturnTarget.of(projectId));
  }

  private static TraversalAnswer answer(NodeIdentity joiner, TraversalOffer offer,
      CandidateDescriptor descriptor) throws Exception {
    return TraversalAnswer.create(joiner, offer, descriptor,
        new byte[TraversalAnswer.NONCE_BYTES]);
  }

  private static int indexOf(byte[] value, byte[] needle) {
    for (int start = 0; start <= value.length - needle.length; start++) {
      if (Arrays.equals(value, start, start + needle.length, needle, 0, needle.length)) {
        return start;
      }
    }
    return -1;
  }

  private static byte[] replace(byte[] value, byte[] oldValue, byte[] newValue) {
    byte[] copy = value.clone();
    int offset = indexOf(copy, oldValue);
    assertTrue(offset >= 0, "expected target bytes in signed payload");
    System.arraycopy(newValue, 0, copy, offset, newValue.length);
    return copy;
  }

  @Test
  void roundTripsV2AndCopiesTargetIntoSla2() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity joiner = NodeIdentity.generate();
    CandidateDescriptor hostDescriptor = descriptor(host, 4401);
    CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402);
    UUID session = UUID.randomUUID();
    SessionInvitation invitation = invitation(host, session, hostDescriptor);
    TraversalOffer offer = offer(host, session, invitation, hostDescriptor, PROJECT_ONE);
    TraversalInvitation slo1 = TraversalInvitation.createV2(invitation, offer);
    TraversalInvitation parsedSlo1 = TraversalInvitation.decode(slo1.encoded());
    TraversalAnswer answer = answer(joiner, parsedSlo1.offer(), joinerDescriptor);
    TraversalAnswer parsedAnswer = TraversalAnswer.decode(answer.encoded());

    assertEquals(TraversalInvitation.FORMAT_VERSION_V2, parsedSlo1.formatVersion());
    assertEquals(TraversalOffer.FORMAT_VERSION_V2, parsedSlo1.offer().formatVersion());
    assertEquals(TraversalAnswer.FORMAT_VERSION_V2, parsedAnswer.formatVersion());
    assertEquals(ReturnTarget.of(PROJECT_ONE), parsedSlo1.returnTarget().orElseThrow());
    assertEquals(ReturnTarget.of(PROJECT_ONE), parsedAnswer.returnTarget().orElseThrow());
    assertTrue(parsedSlo1.verifyAt(ISSUED.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
        joiner.nodeId()));
    assertTrue(parsedAnswer.verifyAt(ISSUED.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW,
        parsedSlo1.offer()));
    assertArrayEquals(slo1.encoded(), parsedSlo1.encoded());
    assertArrayEquals(answer.encoded(), parsedAnswer.encoded());
  }

  @Test
  void targetIsCoveredByBothSignatures() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity joiner = NodeIdentity.generate();
    CandidateDescriptor hostDescriptor = descriptor(host, 4401);
    CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402);
    UUID session = UUID.randomUUID();
    SessionInvitation invitation = invitation(host, session, hostDescriptor);
    TraversalOffer offer = offer(host, session, invitation, hostDescriptor, PROJECT_ONE);
    TraversalAnswer answer = answer(joiner, offer, joinerDescriptor);
    ReturnTarget replacement = ReturnTarget.of(PROJECT_TWO);

    TraversalOffer changedOffer = TraversalOffer.decode(
        replace(offer.encoded(), ReturnTarget.of(PROJECT_ONE).encoded(), replacement.encoded()));
    TraversalAnswer changedAnswer = TraversalAnswer.decode(
        replace(answer.encoded(), ReturnTarget.of(PROJECT_ONE).encoded(), replacement.encoded()));

    assertFalse(changedOffer.verifyAt(ISSUED.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
        joiner.nodeId()));
    assertFalse(changedAnswer.verifyAt(ISSUED.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW,
        offer));
  }

  @Test
  void v2RejectsMissingTruncatedTrailingDowngradedAndFutureFormats() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CandidateDescriptor hostDescriptor = descriptor(host, 4401);
    UUID session = UUID.randomUUID();
    SessionInvitation invitation = invitation(host, session, hostDescriptor);
    TraversalOffer offer = offer(host, session, invitation, hostDescriptor, PROJECT_ONE);

    byte[] target = ReturnTarget.of(PROJECT_ONE).encoded();
    byte[] missingWithPlaceholder = replace(offer.encoded(), target, new byte[target.length]);
    final byte[] missing = Arrays.copyOf(missingWithPlaceholder,
        missingWithPlaceholder.length - target.length);
    assertThrows(java.io.IOException.class, () -> TraversalOffer.decode(missing));

    byte[] trailing = Arrays.copyOf(offer.encoded(), offer.encoded().length + 1);
    trailing[trailing.length - 1] = 9;
    assertThrows(java.io.IOException.class, () -> TraversalOffer.decode(trailing));

    byte[] downgraded = offer.encoded();
    downgraded[4] = (byte) TraversalOffer.FORMAT_VERSION_V1;
    assertThrows(java.io.IOException.class, () -> TraversalOffer.decode(downgraded));

    byte[] future = offer.encoded();
    future[4] = 3;
    assertThrows(java.io.IOException.class, () -> TraversalOffer.decode(future));

    byte[] unsupportedTarget = offer.encoded();
    int targetOffset = indexOf(unsupportedTarget, target);
    unsupportedTarget[targetOffset] = 2;
    assertThrows(java.io.IOException.class, () -> TraversalOffer.decode(unsupportedTarget));

    NodeIdentity joiner = NodeIdentity.generate();
    TraversalAnswer answer = answer(joiner, offer, descriptor(joiner, 4402));
    byte[] answerTrailing = Arrays.copyOf(answer.encoded(), answer.encoded().length + 1);
    assertThrows(java.io.IOException.class, () -> TraversalAnswer.decode(answerTrailing));
    byte[] answerFuture = answer.encoded();
    answerFuture[4] = 3;
    assertThrows(java.io.IOException.class, () -> TraversalAnswer.decode(answerFuture));
  }

  @Test
  void targetCannotCrossInvitationOrOfferChains() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity joiner = NodeIdentity.generate();
    CandidateDescriptor hostDescriptor = descriptor(host, 4401);
    CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402);
    UUID firstSession = UUID.randomUUID();
    UUID secondSession = UUID.randomUUID();
    SessionInvitation firstInvitation = invitation(host, firstSession, hostDescriptor);
    SessionInvitation secondInvitation = invitation(host, secondSession, hostDescriptor);
    TraversalOffer firstOffer = offer(host, firstSession, firstInvitation, hostDescriptor, PROJECT_ONE);
    TraversalOffer secondOffer = offer(host, secondSession, secondInvitation, hostDescriptor, PROJECT_ONE);

    assertThrows(IllegalArgumentException.class,
        () -> TraversalInvitation.createV2(secondInvitation, firstOffer));
    TraversalAnswer firstAnswer = answer(joiner, firstOffer, joinerDescriptor);
    assertFalse(firstAnswer.verifyAt(ISSUED.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW,
        secondOffer));

    TraversalOffer projectTwoOffer = offer(host, secondSession, secondInvitation, hostDescriptor,
        PROJECT_TWO);
    TraversalAnswer projectTwoAnswer = answer(joiner, projectTwoOffer, joinerDescriptor);
    TraversalAnswer transplantedTarget = TraversalAnswer.decode(
        replace(firstAnswer.encoded(), ReturnTarget.of(PROJECT_ONE).encoded(),
            ReturnTarget.of(PROJECT_TWO).encoded()));
    assertFalse(transplantedTarget.verifyAt(ISSUED.plusSeconds(1),
        TraversalAnswer.DEFAULT_CLOCK_SKEW, firstOffer));
    assertFalse(projectTwoAnswer.verifyAt(ISSUED.plusSeconds(1),
        TraversalAnswer.DEFAULT_CLOCK_SKEW, firstOffer));
  }

  @Test
  void v1RemainsASeparateLegacyFormat() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity joiner = NodeIdentity.generate();
    CandidateDescriptor hostDescriptor = descriptor(host, 4401);
    CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402);
    UUID session = UUID.randomUUID();
    SessionInvitation invitation = invitation(host, session, hostDescriptor);
    TraversalOffer offer = TraversalOffer.create(host, null, session, digest(invitation.encoded()),
        ISSUED, EXPIRES, new byte[TraversalOffer.NONCE_BYTES], hostDescriptor);
    TraversalInvitation slo1 = TraversalInvitation.create(invitation, offer);
    TraversalAnswer answer = answer(joiner, offer, joinerDescriptor);

    assertEquals(TraversalInvitation.FORMAT_VERSION_V1,
        TraversalInvitation.decode(slo1.encoded()).formatVersion());
    assertTrue(TraversalInvitation.decode(slo1.encoded()).returnTarget().isEmpty());
    assertEquals(TraversalAnswer.FORMAT_VERSION_V1,
        TraversalAnswer.decode(answer.encoded()).formatVersion());
    assertTrue(TraversalAnswer.decode(answer.encoded()).returnTarget().isEmpty());
    assertTrue(answer.verifyAt(ISSUED.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW, offer));
  }
}
