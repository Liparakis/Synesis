package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Verifies bounded, invitation-bound, one-shot local project selection. */
final class LocalProjectSelectionStoreTest {

  private static final UUID PROJECT_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PROJECT_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final byte[] DIGEST_X = digest((byte) 1);
  private static final byte[] DIGEST_Y = digest((byte) 2);

  @Test
  void validSelectionIsBoundToInvitationAndConsumedOnce() {
    MutableClock clock = new MutableClock();
    LocalProjectSelectionStore store = new LocalProjectSelectionStore(clock, 4,
        Duration.ofMinutes(1));
    LocalProjectSelectionStore.Creation creation = store.create("X", DIGEST_X,
        List.of(PROJECT_ONE, PROJECT_TWO));

    LocalProjectSelectionStore.Claim mismatch = store.claim(creation.selectionId(), PROJECT_ONE,
        DIGEST_Y, List.of(PROJECT_ONE, PROJECT_TWO));
    assertFalse(mismatch.accepted());
    assertEquals("INVITATION_MISMATCH", mismatch.error());

    LocalProjectSelectionStore.Claim accepted = store.claim(creation.selectionId(), PROJECT_ONE,
        DIGEST_X, List.of(PROJECT_ONE, PROJECT_TWO));
    assertTrue(accepted.accepted());
    assertEquals("X", accepted.invitation());

    LocalProjectSelectionStore.Claim duplicate = store.claim(creation.selectionId(), PROJECT_ONE,
        DIGEST_X, List.of(PROJECT_ONE, PROJECT_TWO));
    assertFalse(duplicate.accepted());
    assertEquals("SELECTION_CONSUMED", duplicate.error());
  }

  @Test
  void selectionRejectsUnknownProjectAndProjectThatBecameIneligible() {
    LocalProjectSelectionStore store = new LocalProjectSelectionStore();
    LocalProjectSelectionStore.Creation creation = store.create("X", DIGEST_X,
        List.of(PROJECT_ONE));
    assertEquals("PROJECT_NOT_ELIGIBLE", store.claim(creation.selectionId(), PROJECT_TWO, DIGEST_X,
        List.of(PROJECT_ONE)).error());
    assertEquals("PROJECT_UNAVAILABLE", store.claim(creation.selectionId(), PROJECT_ONE, DIGEST_X,
        List.of()).error());
    assertEquals("SELECTION_UNKNOWN", store.claim(UUID.randomUUID(), PROJECT_ONE, DIGEST_X,
        List.of(PROJECT_ONE)).error());
  }

  @Test
  void expiredAndCapacityBoundContextsFailClosed() {
    MutableClock clock = new MutableClock();
    LocalProjectSelectionStore store = new LocalProjectSelectionStore(clock, 1,
        Duration.ofSeconds(1));
    LocalProjectSelectionStore.Creation creation = store.create("X", DIGEST_X,
        List.of(PROJECT_ONE));
    assertEquals("SELECTION_CAPACITY", assertCapacity(store));
    clock.advance(Duration.ofSeconds(2));
    assertEquals("SELECTION_EXPIRED", store.claim(creation.selectionId(), PROJECT_ONE, DIGEST_X,
        List.of(PROJECT_ONE)).error());
  }

  @Test
  void concurrentClaimsAllowExactlyOneWinner() throws Exception {
    LocalProjectSelectionStore store = new LocalProjectSelectionStore();
    LocalProjectSelectionStore.Creation creation = store.create("X", DIGEST_X,
        List.of(PROJECT_ONE));
    CountDownLatch ready = new CountDownLatch(2);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Future<LocalProjectSelectionStore.Claim> first = executor.submit(() -> claim(store, creation,
          ready));
      Future<LocalProjectSelectionStore.Claim> second = executor.submit(() -> claim(store, creation,
          ready));
      assertTrue(first.get().accepted() ^ second.get().accepted());
    } finally {
      executor.shutdownNow();
    }
  }

  private static LocalProjectSelectionStore.Claim claim(LocalProjectSelectionStore store,
      LocalProjectSelectionStore.Creation creation, CountDownLatch ready) throws Exception {
    ready.countDown();
    ready.await();
    return store.claim(creation.selectionId(), PROJECT_ONE, DIGEST_X, List.of(PROJECT_ONE));
  }

  private static String assertCapacity(LocalProjectSelectionStore store) {
    try {
      store.create("Y", DIGEST_Y, List.of(PROJECT_TWO));
      return "NO_FAILURE";
    } catch (LocalProjectSelectionStore.CapacityExceededException expected) {
      return "SELECTION_CAPACITY";
    }
  }

  private static byte[] digest(byte value) {
    byte[] digest = new byte[32];
    digest[0] = value;
    return digest;
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-09-11T12:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    private void advance(Duration amount) {
      instant = instant.plus(amount);
    }
  }
}
