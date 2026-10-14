package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.protocol.CompactBootstrapInvitation;

/** Verifies one-way compact onboarding over the existing authenticated Link path. */
@Timeout(90)
final class CompactOnboardingTest {

  @Test
  void joiningAnInvitationOfTheSameIdentityIsRefusedAsItsOwn() throws Exception {
    Path profile = Files.createTempDirectory("synesis-compact-own");
    List<OnboardingEvent> hostEvents = new CopyOnWriteArrayList<>();
    Onboarding host = new Onboarding(profile, hostEvents::add);
    try (Onboarding.PreparedHost prepared = host.createCompactInvitation(null, null, ignored -> {
    })) {
      assertNotNull(prepared);
      String invitation = hostEvents.stream()
          .filter(event -> event.type() == OnboardingEventType.SHARE_LINK)
          .map(OnboardingEvent::value)
          .findFirst()
          .orElseThrow();
      Onboarding join = new Onboarding(profile, ignored -> {
      });

      OnboardingFailure failure = org.junit.jupiter.api.Assertions.assertThrows(
          OnboardingFailure.class, () -> {
            try (Onboarding.PreparedJoin attempt = join.importInvitation(invitation)) {
              attempt.connect();
            }
          });

      org.junit.jupiter.api.Assertions.assertEquals(OnboardingFailureCode.OWN_INVITATION,
          failure.code());
    }
  }

  @Test
  void completesOneWayInviteAndExchangesDescriptorsAfterControlReady() throws Exception {
    Path hostProfile = Files.createTempDirectory("synesis-compact-host");
    Path joinProfile = Files.createTempDirectory("synesis-compact-join");
    List<OnboardingEvent> hostEvents = new CopyOnWriteArrayList<>();
    List<OnboardingEvent> joinEvents = new CopyOnWriteArrayList<>();
    Onboarding host = new Onboarding(hostProfile, hostEvents::add);
    Onboarding join = new Onboarding(joinProfile, joinEvents::add);
    CompletableFuture<Void> hostCompletion = new CompletableFuture<>();
    CompletableFuture.runAsync(() -> {
      try {
        try (Onboarding.PreparedHost prepared = host.createCompactInvitation(null, null,
            ignored -> {
            })) {
          prepared.awaitConnection();
        }
        hostCompletion.complete(null);
      } catch (Throwable failure) {
        hostCompletion.completeExceptionally(failure);
      }
    });
    String invitation = hostEvents.stream()
        .filter(event -> event.type() == OnboardingEventType.SHARE_LINK)
        .map(OnboardingEvent::value)
        .findFirst()
        .orElse(null);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (invitation == null && System.nanoTime() < deadline) {
      Thread.sleep(20);
      invitation = hostEvents.stream()
          .filter(event -> event.type() == OnboardingEventType.SHARE_LINK)
          .map(OnboardingEvent::value)
          .findFirst()
          .orElse(null);
    }
    assertNotNull(invitation);
    assertTrue(invitation.startsWith("synesis://join/SLB3-"));
    assertTrue(invitation.length() < CompactBootstrapInvitation.MAX_LINK_CHARS);
    assertFalse(hostEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.ANSWER_LINK));

    try {
      try (Onboarding.PreparedJoin prepared = join.importInvitation(invitation)) {
        prepared.connect();
      }
    } catch (OnboardingFailure failure) {
      throw new AssertionError("compact join failed: " + failure.code() + " cause="
          + String.valueOf(failure.getCause()), failure.getCause());
    }
    hostCompletion.get(45, TimeUnit.SECONDS);

    assertTrue(hostEvents.stream()
        .anyMatch(event -> event.type() == OnboardingEventType.DESCRIPTORS_EXCHANGED));
    assertTrue(joinEvents.stream()
        .anyMatch(event -> event.type() == OnboardingEventType.DESCRIPTORS_EXCHANGED));
    assertTrue(hostEvents.stream()
        .anyMatch(event -> event.type() == OnboardingEventType.CONTROL_READY
            && Boolean.parseBoolean(event.value())));
    assertTrue(joinEvents.stream()
        .anyMatch(event -> event.type() == OnboardingEventType.CONTROL_READY
            && Boolean.parseBoolean(event.value())));
    assertFalse(joinEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.ANSWER_LINK));
  }
}
