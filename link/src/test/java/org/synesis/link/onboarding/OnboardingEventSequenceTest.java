package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Pins the order of {@link OnboardingEventType}s that each onboarding flow emits. */
@Timeout(120)
final class OnboardingEventSequenceTest {

  private static final List<OnboardingEventType> HOST_PREPARED = List.of(
      OnboardingEventType.IDENTITY_CREATED, OnboardingEventType.SESSION_CREATED,
      OnboardingEventType.LISTENER_READY, OnboardingEventType.CANDIDATES_GATHERED,
      OnboardingEventType.DESCRIPTOR_CREATED, OnboardingEventType.INVITE_CREATED,
      OnboardingEventType.SHARE_LINK);

  private static final List<OnboardingEventType> SESSION_FACTS = List.of(
      OnboardingEventType.PEER_CONNECTED, OnboardingEventType.PEER_IDENTITY_VERIFIED,
      OnboardingEventType.CONTROL_READY, OnboardingEventType.LIVENESS);

  private final List<OnboardingEvent> hostEvents = new CopyOnWriteArrayList<>();
  private final List<OnboardingEvent> joinEvents = new CopyOnWriteArrayList<>();

  @Test
  void directHostAndJoinEmitTheirEventsInOrder() throws Exception {
    Onboarding host = onboarding(hostEvents);
    CompletableFuture<Void> hostDone = CompletableFuture.runAsync(() -> {
      try {
        host.host(null, null, _ -> {
        });
      } catch (OnboardingFailure failure) {
        throw new IllegalStateException(failure);
      }
    });
    String link = awaitShareLink();
    assertNotNull(link);
    onboarding(joinEvents).join(link, null, _ -> {
    });
    hostDone.get(60, TimeUnit.SECONDS);

    assertEquals(sequence(List.of(HOST_PREPARED, SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))),
        types(hostEvents));
    assertEquals(sequence(List.of(List.of(OnboardingEventType.INVITE_PARSED,
        OnboardingEventType.INVITE_VERIFIED, OnboardingEventType.IDENTITY_CREATED,
        OnboardingEventType.HOST_IDENTITY_PINNED, OnboardingEventType.LOCAL_DESCRIPTOR_CREATED,
        OnboardingEventType.CANDIDATES_GATHERED, OnboardingEventType.PATH_SELECTED),
        SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))), types(joinEvents));
  }

  @Test
  void mediatedHostAndJoinEmitTheirEventsInOrder() throws Exception {
    try (Onboarding.PreparedHost host = onboarding(hostEvents).createInvitation(null, null,
        _ -> {
        });
        Onboarding.PreparedJoin join = onboarding(joinEvents).importInvitation(
            host.invitationLink(), null, _ -> {
            })) {
      CompletableFuture<Void> hostDone = CompletableFuture.runAsync(() -> {
        try {
          host.importAnswer(join.answerLink());
        } catch (OnboardingFailure failure) {
          throw new IllegalStateException(failure);
        }
      });
      join.connect();
      hostDone.get(60, TimeUnit.SECONDS);
    }

    assertEquals(sequence(List.of(HOST_PREPARED,
        List.of(OnboardingEventType.ANSWER_VERIFIED, OnboardingEventType.TRAVERSAL_STARTED),
        SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))), types(hostEvents));
    assertEquals(sequence(List.of(List.of(OnboardingEventType.IDENTITY_CREATED,
        OnboardingEventType.INVITE_PARSED, OnboardingEventType.INVITE_VERIFIED,
        OnboardingEventType.HOST_IDENTITY_PINNED, OnboardingEventType.LOCAL_DESCRIPTOR_CREATED,
        OnboardingEventType.CANDIDATES_GATHERED, OnboardingEventType.ANSWER_LINK,
        OnboardingEventType.TRAVERSAL_STARTED, OnboardingEventType.PATH_SELECTED),
        SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))), types(joinEvents));
  }

  @Test
  void compactHostAndJoinEmitTheirEventsInOrder() throws Exception {
    Onboarding host = onboarding(hostEvents);
    CompletableFuture<Void> hostDone = CompletableFuture.runAsync(() -> {
      try (Onboarding.PreparedHost prepared = host.createCompactInvitation(null)) {
        prepared.awaitConnection();
      } catch (OnboardingFailure failure) {
        throw new IllegalStateException(failure);
      }
    });
    String link = awaitShareLink();
    assertNotNull(link);
    try (Onboarding.PreparedJoin join = onboarding(joinEvents).importInvitation(link, null,
        _ -> {
        })) {
      join.connect();
    }
    hostDone.get(60, TimeUnit.SECONDS);

    assertEquals(sequence(List.of(HOST_PREPARED, List.of(OnboardingEventType.DESCRIPTORS_EXCHANGED),
        SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))), types(hostEvents));
    assertEquals(sequence(List.of(List.of(OnboardingEventType.INVITE_PARSED,
        OnboardingEventType.INVITE_VERIFIED, OnboardingEventType.IDENTITY_CREATED,
        OnboardingEventType.HOST_IDENTITY_PINNED, OnboardingEventType.LOCAL_DESCRIPTOR_CREATED,
        OnboardingEventType.CANDIDATES_GATHERED, OnboardingEventType.TRAVERSAL_STARTED,
        OnboardingEventType.DESCRIPTORS_EXCHANGED, OnboardingEventType.PATH_SELECTED),
        SESSION_FACTS, List.of(OnboardingEventType.SESSION_CLOSED))), types(joinEvents));
  }

  private static Onboarding onboarding(List<OnboardingEvent> events) throws Exception {
    return new Onboarding(Files.createTempDirectory("synesis-event-sequence"), events::add);
  }

  private String awaitShareLink() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      for (OnboardingEvent event : hostEvents) {
        if (event.type() == OnboardingEventType.SHARE_LINK) {
          return event.value();
        }
      }
      Thread.sleep(20);
    }
    return null;
  }

  private static List<OnboardingEventType> types(List<OnboardingEvent> events) {
    return events.stream().map(OnboardingEvent::type).toList();
  }

  private static List<OnboardingEventType> sequence(List<List<OnboardingEventType>> parts) {
    return parts.stream().flatMap(List::stream).toList();
  }
}
