package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.protocol.CompactAnswer;
import org.synesis.link.protocol.CompactBootstrapInvitation;

/** Tests what the host does with the joiner's answer link, and how the STUN server is chosen. */
@Timeout(60)
final class HolePunchTest {

  @Test
  void anAnswerMakesTheHostSendFromItsListeningPortToTheJoinersAddress() throws Exception {
    List<OnboardingEvent> events = new CopyOnWriteArrayList<>();
    Onboarding host = new Onboarding(Files.createTempDirectory("synesis-punch"), events::add);
    try (Onboarding.PreparedHost prepared = host.createCompactInvitation(null);
        DatagramSocket joiner = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      joiner.setSoTimeout(5_000);
      CompactBootstrapInvitation invitation =
          CompactBootstrapInvitation.fromShareLink(prepared.invitationLink());
      String answer = CompactAnswer.create(invitation, List.of(new Candidate(CandidateType.LAN,
          InetAddress.getLoopbackAddress(), joiner.getLocalPort(), 0))).shareLink();

      prepared.importAnswer(answer);

      DatagramPacket packet = new DatagramPacket(new byte[16], 16);
      joiner.receive(packet);
      assertEquals(invitation.endpoints().getFirst().port(), packet.getPort());
      assertTrue(events.stream()
          .anyMatch(event -> event.type() == OnboardingEventType.ANSWER_VERIFIED));
    }
  }

  @Test
  void anAnswerToAnotherInvitationIsRefused() throws Exception {
    Onboarding host = new Onboarding(Files.createTempDirectory("synesis-punch"), ignored -> {
    });
    try (Onboarding.PreparedHost first = host.createCompactInvitation(null);
        Onboarding.PreparedHost second = host.createCompactInvitation(null)) {
      String answerToSecond = CompactAnswer.create(
          CompactBootstrapInvitation.fromShareLink(second.invitationLink()),
          List.of(new Candidate(CandidateType.LAN, InetAddress.getLoopbackAddress(), 4000, 0)))
          .shareLink();
      for (String answer : List.of(answerToSecond, "synesis://answer/SLA3-AAo", "not a link")) {
        OnboardingFailure failure = assertThrows(OnboardingFailure.class,
            () -> first.importAnswer(answer));
        assertEquals(OnboardingFailureCode.ANSWER_INVALID, failure.code());
      }
    }
  }

  @Test
  void theStunServerDefaultsToCloudflareCanBeChangedOrTurnedOffAndIsValidated() {
    assertEquals(InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478),
        Onboarding.parseStunServer(null));
    assertEquals(InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478),
        Onboarding.parseStunServer(" "));
    assertEquals(InetSocketAddress.createUnresolved("stun.example.test", 19302),
        Onboarding.parseStunServer("stun.example.test:19302"));
    assertNull(Onboarding.parseStunServer("off"));
    for (String invalid : List.of("stun.example.test", ":3478", "stun.example.test:0",
        "stun.example.test:65536", "stun.example.test:port")) {
      assertThrows(IllegalArgumentException.class, () -> Onboarding.parseStunServer(invalid));
    }
  }
}
