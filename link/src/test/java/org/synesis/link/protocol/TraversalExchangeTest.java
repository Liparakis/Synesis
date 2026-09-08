package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.candidate.TraversalCoordinator;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.session.ReplayGuard;

/** Verifies authenticated, bounded traversal offer/answer records. */
final class TraversalExchangeTest {

    private static final Instant ISSUED = Instant.parse("2026-09-08T08:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(30);

    @Test
    void offerAndAnswerRoundTripAndBindBothDurableIdentities() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        UUID sessionId = UUID.randomUUID();
        CandidateDescriptor initiatorDescriptor = descriptor(initiator, 4401);
        CandidateDescriptor responderDescriptor = descriptor(responder, 4402);
        byte[] invitationDigest = digest("invitation");

        TraversalOffer offer = TraversalOffer.create(initiator, responder.nodeId(), sessionId,
                invitationDigest, ISSUED, EXPIRES, bytes(1), initiatorDescriptor);
        TraversalOffer decodedOffer = TraversalOffer.decode(offer.encoded());
        assertTrue(decodedOffer.verifyAt(ISSUED.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
                responder.nodeId()));

        TraversalAnswer answer = TraversalAnswer.create(responder, decodedOffer, responderDescriptor,
                bytes(2));
        TraversalAnswer decodedAnswer = TraversalAnswer.decode(answer.encoded());
        assertTrue(decodedAnswer.verifyAt(ISSUED.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW,
                decodedOffer));
        assertArrayEquals(answer.encoded(), decodedAnswer.encoded());

        TraversalCoordinator coordinator = new TraversalCoordinator(initiator, responder.nodeId(), sessionId, 2);
        coordinator.accept(decodedOffer, decodedAnswer, ISSUED.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW);
        List<CandidatePair> pairs = CandidatePairs.generate(initiatorDescriptor.candidates(),
                responderDescriptor.candidates(), 2);
        List<TraversalCoordinator.Attempt> plan = coordinator.plan(pairs);
        assertTrue(plan.size() == 1);
        String attempt = plan.getFirst().identifier();
        assertTrue(coordinator.recordAuthenticated(attempt, responder.nodeId()));
        Optional<String> winner = coordinator.selectWinner();
        assertTrue(winner.isPresent());
        assertTrue(coordinator.isEstablished());
    }

    @Test
    void rejectsWrongPeerTamperingExpiryAndReplayedExchange() throws Exception {
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        TraversalOffer offer = TraversalOffer.create(initiator, responder.nodeId(), UUID.randomUUID(),
                digest("invitation"), ISSUED, EXPIRES, bytes(3), descriptor(initiator, 4401));

        assertFalse(offer.verifyAt(ISSUED.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
                NodeIdentity.generate().nodeId()));
        byte[] tampered = offer.encoded();
        tampered[12] ^= 1;
        assertFalse(TraversalOffer.decode(tampered).verifyAt(ISSUED.plusSeconds(1),
                TraversalOffer.DEFAULT_CLOCK_SKEW, responder.nodeId()));
        assertFalse(offer.verifyAt(EXPIRES, TraversalOffer.DEFAULT_CLOCK_SKEW, responder.nodeId()));

        String fingerprint = HexFormat.of().formatHex(digest(offer.encoded()));
        ReplayGuard guard = new ReplayGuard(1);
        assertTrue(guard.accept(fingerprint));
        assertFalse(guard.accept(fingerprint));
        assertThrows(IllegalStateException.class, () -> guard.accept("second"));
    }

    private static CandidateDescriptor descriptor(NodeIdentity identity, int port)
            throws Exception {
        return CandidateDescriptor.create(identity, ISSUED, EXPIRES,
                List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"),
                        port, 1)));
    }

    private static byte[] bytes(int first) {
        byte[] value = new byte[32];
        value[0] = (byte) first;
        return value;
    }

    private static byte[] digest(String value) throws Exception {
        return digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
