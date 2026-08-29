package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/**
 * Tests canonical identity, signing, and immutable envelope round trips.
 */
class LifecycleControlRequestEnvelopeTest {

    private static LifecycleControlRequestEnvelope request(long deadline) {
        return new LifecycleControlRequestEnvelope(UUID.randomUUID(), "host-test",
                new LifecycleControlRequestEnvelope.AuthorityContext("project",
                        "codex",
                        "connection",
                        "session",
                        "fingerprint",
                        1,
                        "agt_participant",
                        UUID.randomUUID()
                                .toString(),
                        1,
                        "C:/worktree",
                        "C:/worktree",
                        "C:/git",
                        "synesis/codex/session",
                        "a".repeat(40),
                        "supervisor",
                        "worker"), LifecycleControlRequestEnvelope.Operation.START, 0L, null, null,
                true, "initial input", deadline, Map.of("mode", "test"));
    }

    @Test
    void signedRoundTripPreservesDigestAndSemanticDeadline() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        LifecycleControlRequestEnvelope request = request(Instant.now()
                .plusSeconds(30)
                .toEpochMilli());
        LifecycleControlRequestEnvelope.SignedEnvelope signed = request.sign(identity);

        LifecycleControlRequestEnvelope.SignedEnvelope decoded =
                LifecycleControlRequestEnvelope.SignedEnvelope.decode(signed.encoded());

        assertTrue(decoded.verify());
        assertEquals(request.digest(),
                decoded.request()
                        .digest());
        assertEquals(request.callerDeadlineEpochMillis(),
                decoded.request()
                        .callerDeadlineEpochMillis());
        assertEquals(request.options(),
                decoded.request()
                        .options());
    }

    @Test
    void changedDeadlineChangesDigest() {
        LifecycleControlRequestEnvelope first = request(Instant.now()
                .plusSeconds(30)
                .toEpochMilli());
        LifecycleControlRequestEnvelope second = new LifecycleControlRequestEnvelope(first.requestId(),
                first.hostInstanceId(), first.authority(), first.operation(), first.expectedLifecycleRevision(),
                first.expectedThreadId(), first.expectedTurnId(), first.continuation(), first.input(),
                first.callerDeadlineEpochMillis() + 1_000L, first.options());

        org.junit.jupiter.api.Assertions.assertNotEquals(first.digest(), second.digest());
    }
}
