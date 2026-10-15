package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** Tests the answer link the joiner gives the host's user. */
final class CompactAnswerTest {

  private static CompactBootstrapInvitation invitation() throws Exception {
    Instant issued = Instant.now().minusSeconds(1);
    return CompactBootstrapInvitation.createMinimized(NodeIdentity.generate(), ProtocolVersion.V1,
        issued, issued.plus(Duration.ofMinutes(10)),
        new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.1"), 4401,
            0)));
  }

  @Test
  void carriesTheInvitationsSessionAndTheJoinersEndpointsMostPromisingFirst() throws Exception {
    CompactBootstrapInvitation invitation = invitation();
    Candidate lan = new Candidate(CandidateType.LAN, InetAddress.getByName("192.168.1.20"), 5000, 0);
    Candidate reflexive = new Candidate(CandidateType.SERVER_REFLEXIVE,
        InetAddress.getByName("203.0.113.9"), 61000, 0);

    CompactAnswer answer = CompactAnswer.create(invitation, List.of(lan, reflexive));
    CompactAnswer parsed = CompactAnswer.fromShareLink(answer.shareLink());

    assertTrue(answer.shareLink().startsWith("synesis://answer/SLA3-"));
    assertTrue(CompactAnswer.isShareLink(answer.shareLink()));
    assertEquals(invitation.sessionId(), parsed.sessionId());
    assertEquals(List.of(reflexive.address(), lan.address()),
        parsed.endpoints().stream().map(Candidate::address).toList());
    assertEquals(List.of(61000, 5000), parsed.endpoints().stream().map(Candidate::port).toList());
    assertEquals(2 + 16 + 1 + 7 + 7, answer.encoded().length);
  }

  @Test
  void rejectsAMalformedTruncatedOrPaddedAnswer() throws Exception {
    byte[] encoded = CompactAnswer.create(invitation(), List.of(new Candidate(CandidateType.LAN,
        InetAddress.getByName("192.168.1.20"), 5000, 0))).encoded();
    byte[] noEndpoints = encoded.clone();
    noEndpoints[18] = 0;
    for (byte[] bad : List.of(Arrays.copyOf(encoded, encoded.length - 1),
        Arrays.copyOf(encoded, encoded.length + 1), noEndpoints)) {
      assertThrows(IOException.class, () -> CompactAnswer.decode(bad));
    }
    String link = CompactAnswer.create(invitation(), List.of(new Candidate(CandidateType.LAN,
        InetAddress.getByName("192.168.1.20"), 5000, 0))).shareLink();
    assertFalse(CompactAnswer.isShareLink(link.replace("SLA3-", "SLA2-")));
    assertThrows(IOException.class,
        () -> CompactAnswer.fromShareLink(link.replace("SLA3-", "SLA2-")));
    assertThrows(IOException.class,
        () -> CompactAnswer.fromShareLink(link.replace("answer", "join")));
  }
}
