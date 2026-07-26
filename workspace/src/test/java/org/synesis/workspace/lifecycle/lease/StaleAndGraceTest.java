package org.synesis.workspace.lifecycle.lease;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaleAndGraceTest {

    @Test
    void evaluatesSuspectedStaleAndAbandonmentEligibleStates() {
        long start = System.currentTimeMillis();
        SessionProcessIdentity identity = new SessionProcessIdentity(99999L, "java", "java -jar synesis.jar", start, "nonce123");
        SessionLeaseRecord record = new SessionLeaseRecord(
                1, "proj-1", "codex", "conn-1", "w-1", "s-1",
                identity, "0.1.0-SNAPSHOT", start, start, SessionLeaseState.ACTIVE
        );

        // Process missing, heartbeat recent -> SUSPECTED_STALE
        ProcessInspector deadInspector = pid -> java.util.Optional.empty();
        SessionLeaseService service = new SessionLeaseService(new SessionLeaseStore(), deadInspector);

        Clock clockStale = Clock.fixed(Instant.ofEpochMilli(start + 180000L), ZoneId.of("UTC")); // 3 min later
        SessionLeasePolicy policyStale = new SessionLeasePolicy(clockStale, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));

        SessionLeaseState state1 = service.evaluateLiveness(record, policyStale);
        assertEquals(SessionLeaseState.SUSPECTED_STALE, state1);

        // Process missing, heartbeat beyond grace period (6 min) -> ABANDONMENT_ELIGIBLE
        Clock clockGrace = Clock.fixed(Instant.ofEpochMilli(start + 360000L), ZoneId.of("UTC")); // 6 min later
        SessionLeasePolicy policyGrace = new SessionLeasePolicy(clockGrace, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));

        SessionLeaseState state2 = service.evaluateLiveness(record, policyGrace);
        assertEquals(SessionLeaseState.ABANDONMENT_ELIGIBLE, state2);
    }
}
