package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** Verifies the compact first-contact invitation wire boundary. */
final class CompactBootstrapInvitationTest {

  private static final Instant ISSUED = Instant.ofEpochSecond(1_750_000_000L);
  private static final Instant EXPIRES = ISSUED.plus(Duration.ofMinutes(10));

  @Test
  void minimizedSlb2Ipv4RetainsAuthenticationAndHitsSizeBudget() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    byte[] capability = new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES];
    new Random(17).nextBytes(capability);
    Candidate endpoint = new Candidate(CandidateType.SERVER_REFLEXIVE,
        InetAddress.getByName("198.51.100.11"), 45_679, 4);

    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES, capability, endpoint);
    CompactBootstrapInvitation parsed = CompactBootstrapInvitation.fromShareLink(
        invitation.shareLink());

    assertTrue(invitation.shareLink().startsWith("synesis://join/SLB2-"));
    assertTrue(parsed.verifyAt(ISSUED.plusSeconds(30), Duration.ofMinutes(2)));
    assertArrayEquals(invitation.encoded(), parsed.encoded());
    assertEquals(CompactBootstrapInvitation.sessionIdForMinimized(host.publicKeyEncoded(),
        capability, ISSUED, EXPIRES), parsed.sessionId());
    assertEquals(12 + 32 + 4 + 2 + 4 + 64 + 2, parsed.encoded().length);
    assertEquals(180, parsed.shareLink().length());
    assertEquals(CandidateType.MANUAL, parsed.endpoint().type());
    assertEquals(0, parsed.endpoint().priority());
  }

  @Test
  void minimizedSlb2Ipv6UsesPayloadLengthAsCanonicalFamily() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES,
        new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        new Candidate(CandidateType.IPV6, InetAddress.getByName("2001:db8::11"), 4_434, 0));

    assertEquals(132, invitation.encoded().length);
    assertEquals(196, invitation.shareLink().length());
    CompactBootstrapInvitation parsed = CompactBootstrapInvitation.fromShareLink(
        invitation.shareLink());
    assertTrue(parsed.verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));
    assertEquals(CandidateType.IPV6, parsed.endpoint().type());
    assertEquals(invitation.shareLink(), parsed.shareLink());
  }

  @Test
  void minimizedSecurityFieldsRejectTamperingAndMalformedLayout() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES,
        new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.7"), 4434, 0));
    byte[] tamperedCapability = invitation.encoded();
    tamperedCapability[6] ^= 1;
    CompactBootstrapInvitation parsedCapability = CompactBootstrapInvitation.decodeMinimized(
        tamperedCapability);
    assertFalse(parsedCapability.verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));

    byte[] truncated = java.util.Arrays.copyOf(invitation.encoded(), invitation.encoded().length - 1);
    byte[] trailing = java.util.Arrays.copyOf(invitation.encoded(), invitation.encoded().length + 1);
    assertThrows(java.io.IOException.class,
        () -> CompactBootstrapInvitation.decodeMinimized(truncated));
    assertThrows(java.io.IOException.class,
        () -> CompactBootstrapInvitation.decodeMinimized(trailing));
    assertThrows(java.io.IOException.class,
        () -> CompactBootstrapInvitation.fromShareLink(invitation.shareLink()
            .replace("SLB2-", "SLB3-")));
    assertThrows(IllegalArgumentException.class,
        () -> CompactBootstrapInvitation.createMinimized(host, new ProtocolVersion(2, 0), ISSUED,
            EXPIRES, new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
            new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.7"), 4434, 0)));
  }

  @Test
  void tamperingAndExpiryFailClosed() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES, new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.4"), 4433, 0));
    byte[] tampered = invitation.encoded();
    tampered[tampered.length - 1] ^= 1;
    CompactBootstrapInvitation parsed = CompactBootstrapInvitation.decodeMinimized(tampered);

    assertFalse(parsed.verifyAt(ISSUED.plusSeconds(1), Duration.ofMinutes(2)));
    assertFalse(invitation.verifyAt(EXPIRES, Duration.ZERO));
    assertFalse(invitation.verifyAt(ISSUED.minusSeconds(121), Duration.ofMinutes(2)));
  }

  @Test
  void rejectsTrailingBytesAndInvalidLifetime() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES, new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.5"), 4433, 0));
    byte[] trailing = java.util.Arrays.copyOf(invitation.encoded(), invitation.encoded().length + 1);
    trailing[trailing.length - 1] = 1;

    assertThrows(java.io.IOException.class, () -> CompactBootstrapInvitation.decodeMinimized(trailing));
    assertThrows(IllegalArgumentException.class,
        () -> CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, ISSUED,
            ISSUED.plus(CompactBootstrapInvitation.MAX_LIFETIME).plusSeconds(1),
            new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
            new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.5"), 4433, 0)));
  }

  @Test
  void rejectsUnsafeEndpoint() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    assertThrows(IllegalArgumentException.class,
        () -> CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, ISSUED, EXPIRES,
            new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
            new Candidate(CandidateType.RELAY, InetAddress.getByName("203.0.113.6"), 4433, 0)));
    assertThrows(IllegalArgumentException.class,
        () -> CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, ISSUED, EXPIRES,
            new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
            new Candidate(CandidateType.MANUAL, InetAddress.getByName("0.0.0.0"), 4433, 0)));
    assertThrows(IllegalArgumentException.class,
        () -> CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, ISSUED, EXPIRES,
            new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
            new Candidate(CandidateType.IPV6, InetAddress.getByName("fe80::1"), 4433, 0)));
  }

  @Test
  void selectsServerReflexiveBeforeLowerPriorityLocalEndpoint() throws Exception {
    Candidate lan = new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.10"), 4433,
        0);
    Candidate reflexive = new Candidate(CandidateType.SERVER_REFLEXIVE,
        InetAddress.getByName("198.51.100.10"), 4433, 99);

    assertEquals(reflexive, CompactBootstrapInvitation.selectEndpoint(List.of(lan, reflexive)));
  }

  @Test
  void retiredSlb1LinkIsRejectedLikeAnyUnknownPrefix() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(host,
        ProtocolVersion.V1, ISSUED, EXPIRES,
        new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.8"), 4434, 0));
    String slb1 = invitation.shareLink().replace("SLB2-", "SLB1-");

    assertFalse(CompactBootstrapInvitation.isShareLink(slb1));
    assertEquals("unsupported compact invitation link format", assertThrows(
        java.io.IOException.class, () -> CompactBootstrapInvitation.fromShareLink(slb1))
        .getMessage());
  }
}
