package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests durable-before-mutation idempotency and restart ambiguity. */
class LifecycleIdempotencyLedgerTest {

    @TempDir
    Path temp;

    @Test
    void duplicateAndConflictAreResolvedByCanonicalDigest() throws Exception {
        Path file = temp.resolve("ledger.json");
        LifecycleIdempotencyLedger ledger = new LifecycleIdempotencyLedger(file);
        UUID requestId = UUID.randomUUID();
        LifecycleControlRequestEnvelope request = request(requestId, "input");

        assertEquals(LifecycleIdempotencyLedger.Disposition.NEW, ledger.prepare(request, 0L).disposition());
        assertEquals(LifecycleIdempotencyLedger.Disposition.IN_PROGRESS, ledger.prepare(request, 0L).disposition());
        assertThrows(LifecycleIdempotencyLedger.IdempotencyConflictException.class,
                () -> ledger.prepare(request(requestId, "different"), 0L));
    }

    @Test
    void restartTurnsInProgressStateChangingEntryAmbiguous() throws Exception {
        Path file = temp.resolve("ledger.json");
        LifecycleIdempotencyLedger first = new LifecycleIdempotencyLedger(file);
        LifecycleControlRequestEnvelope request = request(UUID.randomUUID(), "input");
        first.prepare(request, 0L);

        LifecycleIdempotencyLedger restarted = new LifecycleIdempotencyLedger(file);
        assertEquals(LifecycleIdempotencyLedger.State.AMBIGUOUS,
                restarted.find(request.requestId()).orElseThrow().state());
    }

    @Test
    void initialDurabilityFailureRemovesUncommittedEntry() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        LifecycleIdempotencyLedger ledger = new LifecycleIdempotencyLedger(temp.resolve("unused.json"), entries -> {
            called.set(true);
            throw new java.io.IOException("disk full");
        });
        LifecycleControlRequestEnvelope request = request(UUID.randomUUID(), "input");
        assertThrows(java.io.IOException.class, () -> ledger.prepare(request, 0L));
        assertEquals(true, called.get());
        assertEquals(false, ledger.find(request.requestId()).isPresent());
    }

    @Test
    void successfulWriteCallbackWithoutCommittedFileFailsVerification() throws Exception {
        Path file = temp.resolve("missing-after-success.json");
        LifecycleIdempotencyLedger ledger = new LifecycleIdempotencyLedger(file, entries -> {
            // Simulate a broken store that reports success without committing
            // a readable durable representation.
        });
        LifecycleControlRequestEnvelope request = request(UUID.randomUUID(), "input");

        assertEquals(LifecycleIdempotencyLedger.Disposition.NEW, ledger.prepare(request, 0L).disposition());
        assertEquals(false, ledger.verifyCommitted(request.requestId(), request.digest(), 0L));
    }

    private static LifecycleControlRequestEnvelope request(UUID id, String input) {
        return new LifecycleControlRequestEnvelope(id, "host", new LifecycleControlRequestEnvelope.AuthorityContext(
                "project", "codex", "connection", "session", "fingerprint", 1, "agt_participant",
                UUID.randomUUID().toString(), 1L, "worktree", "worktree", "git", "branch", "a".repeat(40),
                "supervisor", "worker"), LifecycleControlRequestEnvelope.Operation.START, 0L, null, null, true,
                input, Instant.now().plusSeconds(60).toEpochMilli(), Map.of());
    }
}
