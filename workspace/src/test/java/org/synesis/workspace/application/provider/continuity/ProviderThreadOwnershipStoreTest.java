package org.synesis.workspace.application.provider.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies durable uniqueness and lawful release of provider-thread ownership.
 */
final class ProviderThreadOwnershipStoreTest {

  private static boolean attempt(ProviderThreadOwnershipStore store, String binding,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    try {
      start.await(5, TimeUnit.SECONDS);
      store.acquire("project", "codex", "thread-x", binding);
      return true;
    } catch (Exception expectedForLoser) {
      return false;
    }
  }

  @Test
  void sameBindingCanReadItsOwnerButAnotherBindingConflicts() throws Exception {
    Path directory = Files.createTempDirectory("synesis-provider-thread-owner-");
    ProviderThreadOwnershipStore store = new ProviderThreadOwnershipStore(directory);

    var owner = store.acquire("project", "codex", "thread-x", "binding-a");

    assertEquals("binding-a", owner.bindingSessionId());
    assertEquals(owner, store.acquire("project", "codex", "thread-x", "binding-a"));
    assertThrows(java.io.IOException.class,
        () -> store.acquire("project", "codex", "thread-x", "binding-b"));
    assertEquals("thread-x",
        store.findByBinding("codex", "binding-a")
            .orElseThrow()
            .providerThreadId());
  }

  @Test
  void concurrentBindingsHaveExactlyOneDurableWinner() throws Exception {
    Path directory = Files.createTempDirectory("synesis-provider-thread-race-");
    var first = new ProviderThreadOwnershipStore(directory);
    var second = new ProviderThreadOwnershipStore(directory);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      futures.add(executor.submit(() -> attempt(first, "binding-a", ready, start)));
      futures.add(executor.submit(() -> attempt(second, "binding-b", ready, start)));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      int winners = 0;
      for (var future : futures) {
        if (future.get(5, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertEquals(1, winners);
    }
    var durable = first.find("codex", "thread-x")
        .orElseThrow();
    assertEquals(ProviderThreadOwnershipRecord.Status.ACTIVE, durable.status());
  }

  @Test
  void releaseLeavesNonReusableTombstone() throws Exception {
    Path directory = Files.createTempDirectory("synesis-provider-thread-release-");
    var store = new ProviderThreadOwnershipStore(directory);
    store.acquire("project", "codex", "thread-x", "binding-a");

    var released = store.release("codex", "thread-x", "binding-a");

    assertEquals(ProviderThreadOwnershipRecord.Status.RELEASED, released.status());
    assertThrows(java.io.IOException.class,
        () -> store.acquire("project", "codex", "thread-x", "binding-b"));
  }

  @Test
  void persistenceReadyIsDurableAndIdempotentWithoutChangingOwnership() throws Exception {
    Path directory = Files.createTempDirectory("synesis-provider-thread-persistence-");
    var store = new ProviderThreadOwnershipStore(directory);
    var owner = store.acquire("project", "codex", "thread-x", "binding-a");

    var persisted = store.markPersistenceReady("project", "codex", "thread-x", "binding-a");
    var repeated = store.markPersistenceReady("project", "codex", "thread-x", "binding-a");

    assertTrue(persisted.persistenceReady());
    assertEquals(persisted, repeated);
    assertEquals(owner.projectId(), repeated.projectId());
    assertEquals(owner.providerThreadId(), repeated.providerThreadId());
    assertEquals(owner.bindingSessionId(), repeated.bindingSessionId());
    assertTrue(new ProviderThreadOwnershipStore(directory).find("codex", "thread-x")
        .orElseThrow()
        .persistenceReady());
  }

  @Test
  void wrongPersistenceCompletionCannotMarkAnotherOwnerReady() throws Exception {
    Path directory = Files.createTempDirectory("synesis-provider-thread-persistence-scope-");
    var store = new ProviderThreadOwnershipStore(directory);
    store.acquire("project", "codex", "thread-x", "binding-a");

    assertThrows(java.io.IOException.class,
        () -> store.markPersistenceReady("project", "codex", "thread-other", "binding-a"));
    assertThrows(java.io.IOException.class,
        () -> store.markPersistenceReady("project", "codex", "thread-x", "binding-b"));
    assertTrue(!store.find("codex", "thread-x")
        .orElseThrow()
        .persistenceReady());
  }
}
