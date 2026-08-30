package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Deterministic repeated-discovery hard-stop fixtures.
 */
class ProcessTreeTerminatorTest {

    private static ProcessTreeTerminator terminator(Fixture fixture) {
        return new ProcessTreeTerminator(fixture);
    }

    private static ProcessTreeTerminator.AttachmentIdentity identity() {
        return new ProcessTreeTerminator.AttachmentIdentity(1L, "codex", "codex app-server", 11L, 7L);
    }

    @Test
    void discoversGrandchildSpawnedDuringGracefulShutdown() {
        Fixture fixture = new Fixture(true, false);
        ProcessTreeTerminator.Result result = terminator(fixture).terminate(identity(),
                7L,
                Duration.ofMillis(5),
                Instant.now()
                        .plusMillis(250));
        assertEquals(ProcessTreeTerminator.Outcome.FORCED, result.outcome());
        assertTrue(fixture.gracefulCalls.contains(2L));
        assertTrue(fixture.forceCalls.contains(3L));
    }

    @Test
    void reportsAProcessThatSurvivesTheDeadline() {
        Fixture fixture = new Fixture(true, true);
        ProcessTreeTerminator.Result result = terminator(fixture).terminate(identity(),
                7L,
                Duration.ofMillis(5),
                Instant.now()
                        .plusMillis(80));
        assertEquals(ProcessTreeTerminator.Outcome.DESCENDANTS_SURVIVED, result.outcome());
        assertEquals(List.of(3L), result.survivors());
    }

    @Test
    void rejectsGenerationAndPidReuse() {
        Fixture fixture = new Fixture(false, false);
        ProcessTreeTerminator terminator = terminator(fixture);
        assertEquals(ProcessTreeTerminator.Outcome.ATTACHMENT_GENERATION_MISMATCH,
                terminator.terminate(identity(), 8L, Duration.ZERO, Instant.now())
                        .outcome());
        fixture.processes.put(1L, new ProcessTreeTerminator.ObservedProcess(1L, "codex", "replacement", 99L, 0));
        assertEquals(ProcessTreeTerminator.Outcome.PROCESS_OWNERSHIP_UNPROVEN,
                terminator.terminate(identity(), 7L, Duration.ZERO, Instant.now())
                        .outcome());
    }

    @Test
    void doesNotTargetDescendantsWhenRootOwnershipWasNeverObservable() {
        Fixture fixture = new Fixture(true, false);
        fixture.processes.remove(1L);

        ProcessTreeTerminator.Result result = terminator(fixture).terminate(identity(),
                7L,
                Duration.ofMillis(5),
                Instant.now()
                        .plusMillis(50));

        assertEquals(ProcessTreeTerminator.Outcome.ROOT_ALREADY_EXITED, result.outcome());
        assertTrue(fixture.gracefulCalls.isEmpty());
        assertTrue(fixture.forceCalls.isEmpty());
    }

    /** Supplies deterministic process-tree evidence to terminator tests. */
    private static final class Fixture implements ProcessTreeTerminator.Inspector {

        private final Map<Long, ProcessTreeTerminator.ObservedProcess> processes = new LinkedHashMap<>();
        private final List<Long> gracefulCalls = new ArrayList<>();
        private final List<Long> forceCalls = new ArrayList<>();
        private final boolean spawnGrandchild;
        private final boolean preserveGrandchild;
        private boolean spawned;

        private Fixture(boolean spawnGrandchild, boolean preserveGrandchild) {
            this.spawnGrandchild = spawnGrandchild;
            this.preserveGrandchild = preserveGrandchild;
            processes.put(1L, new ProcessTreeTerminator.ObservedProcess(1L, "codex", "codex app-server", 11L, 0));
            processes.put(2L, new ProcessTreeTerminator.ObservedProcess(2L, "helper", "helper", 12L, 1));
            processes.put(3L, new ProcessTreeTerminator.ObservedProcess(3L, "grandchild", "grandchild", 13L, 2));
            if (!spawnGrandchild) {
                processes.remove(3L);
            }
        }

        @Override
        public Optional<ProcessTreeTerminator.ObservedProcess> process(long pid) {
            return Optional.ofNullable(processes.get(pid));
        }

        @Override
        public List<ProcessTreeTerminator.ObservedProcess> descendants(long pid) {
            if (spawnGrandchild && !spawned) {
                spawned = true;
                processes.put(3L, new ProcessTreeTerminator.ObservedProcess(3L, "grandchild", "grandchild", 13L, 2));
            }
            return processes.values()
                    .stream()
                    .filter(item -> item.pid() != pid)
                    .toList();
        }

        @Override
        public void graceful(ProcessTreeTerminator.ObservedProcess process) {
            gracefulCalls.add(process.pid());
        }

        @Override
        public void force(ProcessTreeTerminator.ObservedProcess process) {
            forceCalls.add(process.pid());
            if (!(preserveGrandchild && process.pid() == 3L)) {
                processes.remove(process.pid());
            }
        }

        @Override
        public boolean alive(ProcessTreeTerminator.ObservedProcess process) {
            return processes.containsKey(process.pid());
        }
    }
}
