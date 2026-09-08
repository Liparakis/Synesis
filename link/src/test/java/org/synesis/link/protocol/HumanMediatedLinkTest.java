package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** Verifies the two copy/paste traversal link representations. */
final class HumanMediatedLinkTest {

    @Test
    void roundTripsSlo1AndSla2WithExactOfferBinding() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-09-08T08:00:00Z");
        Instant expires = issued.plusSeconds(60);
        UUID sessionId = UUID.randomUUID();
        CandidateDescriptor hostDescriptor = descriptor(host, 4401, issued, expires);
        CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402, issued, expires);
        byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
        SessionInvitation invitation = SessionInvitation.create(host, sessionId, ProtocolVersion.V1, issued,
                expires, capability, hostDescriptor);
        TraversalOffer offer = TraversalOffer.create(host, null, sessionId, digest(invitation.encoded()), issued,
                expires, new byte[TraversalOffer.NONCE_BYTES], hostDescriptor);

        String invitationLink = TraversalInvitation.create(invitation, offer).shareLink();
        TraversalInvitation parsedInvitation = TraversalInvitation.fromShareLink(invitationLink);
        assertTrue(invitationLink.length() <= TraversalInvitation.MAX_LINK_CHARS);
        assertTrue(parsedInvitation.verifyAt(issued.plusSeconds(1), TraversalOffer.DEFAULT_CLOCK_SKEW,
                joiner.nodeId()));
        assertArrayEquals(invitation.encoded(), parsedInvitation.invitation().encoded());
        assertArrayEquals(offer.encoded(), parsedInvitation.offer().encoded());

        TraversalAnswer answer = TraversalAnswer.create(joiner, parsedInvitation.offer(), joinerDescriptor,
                new byte[TraversalAnswer.NONCE_BYTES]);
        TraversalAnswer parsedAnswer = TraversalAnswer.fromShareLink(answer.shareLink());
        assertTrue(answer.shareLink().length() <= TraversalAnswer.MAX_LINK_CHARS);
        assertTrue(parsedAnswer.verifyAt(issued.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW,
                parsedInvitation.offer()));
        assertTrue(parsedAnswer.shareLink().startsWith("synesis://answer/SLA2-"));
    }

    @Test
    void rejectsTamperedOrMismatchedHumanLink() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-09-08T08:00:00Z");
        Instant expires = issued.plusSeconds(60);
        UUID sessionId = UUID.randomUUID();
        CandidateDescriptor descriptor = descriptor(host, 4401, issued, expires);
        SessionInvitation invitation = SessionInvitation.create(host, sessionId, ProtocolVersion.V1, issued,
                expires, new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
        TraversalOffer offer = TraversalOffer.create(host, null, sessionId, digest(invitation.encoded()), issued,
                expires, new byte[TraversalOffer.NONCE_BYTES], descriptor);
        String link = TraversalInvitation.create(invitation, offer).shareLink();
        byte[] tamperedBytes = Base64.getUrlDecoder().decode(link.substring(link.indexOf("SLO1-") + 5));
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        String tampered = "synesis://join/SLO1-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedBytes);
        assertFalse(TraversalInvitation.fromShareLink(tampered).verifyAt(issued.plusSeconds(1),
                TraversalOffer.DEFAULT_CLOCK_SKEW, NodeIdentity.generate().nodeId()));
    }

    @Test
    void answerBindsToOneOfferAndExpires() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-09-08T08:00:00Z");
        Instant expires = issued.plusSeconds(60);
        CandidateDescriptor hostDescriptor = descriptor(host, 4401, issued, expires);
        CandidateDescriptor joinerDescriptor = descriptor(joiner, 4402, issued, expires);
        UUID firstSession = UUID.randomUUID();
        SessionInvitation firstInvitation = SessionInvitation.create(host, firstSession, ProtocolVersion.V1,
                issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], hostDescriptor);
        TraversalOffer firstOffer = TraversalOffer.create(host, null, firstSession,
                digest(firstInvitation.encoded()), issued, expires, new byte[TraversalOffer.NONCE_BYTES],
                hostDescriptor);
        TraversalAnswer answer = TraversalAnswer.create(joiner, firstOffer, joinerDescriptor,
                new byte[TraversalAnswer.NONCE_BYTES]);

        UUID secondSession = UUID.randomUUID();
        SessionInvitation secondInvitation = SessionInvitation.create(host, secondSession, ProtocolVersion.V1,
                issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], hostDescriptor);
        TraversalOffer secondOffer = TraversalOffer.create(host, null, secondSession,
                digest(secondInvitation.encoded()), issued, expires, new byte[TraversalOffer.NONCE_BYTES],
                hostDescriptor);
        assertTrue(answer.verifyAt(issued.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW, firstOffer));
        assertFalse(answer.verifyAt(issued.plusSeconds(1), TraversalAnswer.DEFAULT_CLOCK_SKEW, secondOffer));
        assertFalse(answer.verifyAt(expires, TraversalAnswer.DEFAULT_CLOCK_SKEW, firstOffer));

        byte[] tampered = answer.encoded();
        tampered[tampered.length - 1] ^= 1;
        assertFalse(TraversalAnswer.decode(tampered).verifyAt(issued.plusSeconds(1),
                TraversalAnswer.DEFAULT_CLOCK_SKEW, firstOffer));
    }

    private static CandidateDescriptor descriptor(NodeIdentity identity, int port, Instant issued, Instant expires)
            throws Exception {
        return CandidateDescriptor.create(identity, issued, expires,
                List.of(new Candidate(CandidateType.MANUAL, java.net.InetAddress.getByName("198.51.100.1"), port, 1)));
    }

    private static byte[] digest(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
