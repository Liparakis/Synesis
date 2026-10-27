package org.synesis.workspace.application.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.project.ProjectIdentity;

/** Verifies the post-authentication project-context application boundary. */
final class ProjectContextExchangeTest {

  @Test
  void requestAndAcknowledgementRoundTripPortableIdentity() throws Exception {
    ProjectIdentity identity = new ProjectIdentity(UUID.randomUUID(),
        Instant.parse("2026-09-18T10:11:12.123456789Z"));

    ProjectContextExchange.Context request = ProjectContextExchange.decode(
        ProjectContextExchange.request(identity));
    ProjectContextExchange.Context acknowledgement = ProjectContextExchange.decode(
        ProjectContextExchange.acknowledge(identity));

    assertEquals(ProjectContextExchange.Kind.REQUEST, request.kind());
    assertEquals(ProjectContextExchange.Kind.ACKNOWLEDGE, acknowledgement.kind());
    assertTrue(identity.matches(request.identity()));
    assertTrue(identity.matches(acknowledgement.identity()));
    assertArrayEquals(ProjectContextExchange.request(identity),
        ProjectContextExchange.request(identity));
  }

  @Test
  void malformedOrTrailingContextFailsClosed() {
    ProjectIdentity identity = new ProjectIdentity(UUID.randomUUID(), Instant.now());
    byte[] encoded = ProjectContextExchange.request(identity);
    byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
    trailing[trailing.length - 1] = 1;

    assertThrows(IOException.class, () -> ProjectContextExchange.decode(trailing));
    assertThrows(IOException.class, () -> ProjectContextExchange.decode(new byte[] {1, 2, 3}));
  }

  @Test
  void mismatchedContextIsRejectedBeforeApplicationDelegate() throws Exception {
    ProjectIdentity expected = new ProjectIdentity(UUID.randomUUID(), Instant.now());
    ProjectIdentity wrong = new ProjectIdentity(UUID.randomUUID(), expected.createdAt());
    AtomicBoolean delegated = new AtomicBoolean();
    var handler = ProjectContextExchange.withResponder(expected, (remote, payload) -> {
      delegated.set(true);
      return java.util.concurrent.CompletableFuture.completedFuture(new byte[0]);
    });

    byte[] response = handler.handle("sl1-peer", ProjectContextExchange.request(wrong))
        .toCompletableFuture().join();

    assertEquals(ProjectContextExchange.Kind.REJECT,
        ProjectContextExchange.decode(response).kind());
    assertFalse(delegated.get());
    assertThrows(java.util.concurrent.CompletionException.class,
        () -> handler.handle("sl1-peer", new byte[] {7}).toCompletableFuture().join());

    handler.handle("sl1-peer", ProjectContextExchange.request(expected)).toCompletableFuture()
        .join();
    handler.handle("sl1-peer", new byte[] {7}).toCompletableFuture().join();
    assertTrue(delegated.get());
  }

  @Test
  void aSilentFirstAttemptIsAskedAgainSoOneMissedWaitDoesNotLoseTheJoin() throws Exception {
    ProjectIdentity identity = new ProjectIdentity(UUID.randomUUID(), Instant.parse("2026-09-18T10:11:12Z"));
    AtomicInteger asked = new AtomicInteger();

    ProjectContextExchange.require(request -> asked.incrementAndGet() < 3
        ? new CompletableFuture<byte[]>()
        : CompletableFuture.completedFuture(ProjectContextExchange.acknowledge(identity)),
        identity, Duration.ofMillis(50), 3);

    assertEquals(3, asked.get());
  }

  @Test
  void aPeerSilentForEveryAttemptIsRefused() {
    ProjectIdentity identity = new ProjectIdentity(UUID.randomUUID(), Instant.parse("2026-09-18T10:11:12Z"));
    AtomicInteger asked = new AtomicInteger();

    assertThrows(ProjectContextExchange.ProjectContextException.class, () -> ProjectContextExchange.require(
        request -> {
          asked.incrementAndGet();
          return new CompletableFuture<byte[]>();
        }, identity, Duration.ofMillis(20), 3));
    assertEquals(3, asked.get());
  }
}
