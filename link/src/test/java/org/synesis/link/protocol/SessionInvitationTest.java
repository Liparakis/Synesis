package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** Verifies bounded signed invitation encoding and bearer-link behavior. */
final class SessionInvitationTest {
    @Test
    void roundTripsAndVerifiesTheExactShareLink() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-07-20T10:00:00Z");
        Instant expires = issued.plusSeconds(600);
        CandidateDescriptor descriptor = CandidateDescriptor.create(host, issued, expires,
                List.of(new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.1"), 4433, 1)));
        byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
        capability[0] = 7;
        SessionInvitation invitation = SessionInvitation.create(host, UUID.randomUUID(), ProtocolVersion.V1,
                issued, expires, capability, descriptor);

        SessionInvitation decoded = SessionInvitation.fromShareLink(invitation.shareLink());
        assertTrue(decoded.verifyAt(issued.plusSeconds(1), SessionInvitation.DEFAULT_LIFETIME));
        assertArrayEquals(invitation.encoded(), decoded.encoded());
        assertTrue(invitation.shareLink().startsWith("synesis://join/SYN1-"));
    }

    @Test
    void rejectsTamperingAndExpiryBeforeUse() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-07-20T10:00:00Z");
        Instant expires = issued.plusSeconds(600);
        CandidateDescriptor descriptor = CandidateDescriptor.create(host, issued, expires,
                List.of(new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.1"), 4433, 1)));
        SessionInvitation invitation = SessionInvitation.create(host, UUID.randomUUID(), ProtocolVersion.V1,
                issued, expires, new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
        byte[] bytes = invitation.encoded();
        bytes[bytes.length - 1] ^= 1;
        assertFalse(SessionInvitation.decode(bytes).verifyAt(issued.plusSeconds(1), SessionInvitation.DEFAULT_LIFETIME));
        assertFalse(invitation.verifyAt(expires, SessionInvitation.DEFAULT_LIFETIME));
        assertThrows(java.io.IOException.class, () -> SessionInvitation.fromShareLink("synesis://join/nope"));
    }

    @Test
    void rejectsUnsupportedVersionMissingFieldsAndOversizedInputs() throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        Instant issued = Instant.parse("2026-07-20T10:00:00Z");
        CandidateDescriptor descriptor = CandidateDescriptor.create(host, issued, issued.plusSeconds(600),
                List.of(new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.1"), 4433, 1)));
        SessionInvitation invitation = SessionInvitation.create(host, UUID.randomUUID(), ProtocolVersion.V1,
                issued, issued.plusSeconds(600), new byte[SessionInvitation.CAPABILITY_BYTES], descriptor);
        byte[] unsupported = invitation.encoded();
        unsupported[4] = 2;
        assertThrows(java.io.IOException.class, () -> SessionInvitation.decode(unsupported));
        byte[] missing = invitation.encoded();
        missing[39] = 0;
        missing[40] = 0;
        assertThrows(java.io.IOException.class, () -> SessionInvitation.decode(missing));
        assertThrows(java.io.IOException.class, () -> SessionInvitation.decode(
                new byte[SessionInvitation.MAX_BYTES + 1]));
    }
}
