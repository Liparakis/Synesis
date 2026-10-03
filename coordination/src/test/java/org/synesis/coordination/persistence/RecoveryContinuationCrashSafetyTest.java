package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;

/**
 * Exercises every durable prefix of a recovery continuation append.
 */
final class RecoveryContinuationCrashSafetyTest {

    private static void installFailure(PredictionEventStore.AppendBoundary boundary) {
        PredictionEventStore.setTestFaultInjector(reached -> {
            if (reached == boundary) {
                throw new IOException("SIMULATED_PROCESS_LOSS_" + boundary.name());
            }
        });
    }

    private static ContinuationResult continueLane(Scenario scenario) throws Exception {
        return new WorkIntentService(new PredictionEventStore(scenario.root(), scenario.project()),
                NodeIdentity.generate()).continueEligibleFromRecovery(scenario.continuation());
    }

    private static Scenario prepare(Path root) throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(root, project);
        WorkIntentService service = new WorkIntentService(store, identity);
        WorkIntent owner = new WorkIntent(UUID.randomUUID(),
                project,
                "agt-source",
                "codex",
                UUID.randomUUID(),
                "Implement the task tracker",
                "All focused checks pass",
                "base",
                List.of(ResourceSelector.pathSubtree("src")),
                1,
                WorkIntent.Status.ANNOUNCED);
        assertTrue(service.announce(owner).acquired());
        service.suspend(owner.participant());
        String reference = root.resolve("recovery").toAbsolutePath() + "#hash";
        service.holdRecovery(owner.participant(), reference);
        UUID targetIntentId = UUID.nameUUIDFromBytes(("target:" + owner.intentId()).getBytes(StandardCharsets.UTF_8));
        UUID grantId = UUID.nameUUIDFromBytes(("grant:" + owner.intentId()).getBytes(StandardCharsets.UTF_8));
        WorkIntent target = new WorkIntent(targetIntentId,
                project,
                "agt-successor",
                "codex",
                owner.taskId(),
                owner.goal(),
                owner.acceptance(),
                owner.baseCommit(),
                owner.selectors(),
                owner.version() + 1,
                owner.workGroupId(),
                owner.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                owner.role(),
                owner.reviewTargetSelectors(),
                owner.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(grantId,
                owner.intentId(),
                target,
                owner.participant(),
                target.participant(),
                owner.version(),
                reference);
        return new Scenario(root, project, owner, continuation);
    }

    private static LaneContinuation alternateContinuation(Scenario scenario) {
        WorkIntent source = scenario.owner();
        WorkIntent original = scenario.continuation().targetIntent();
        String participant = "agt-other-successor";
        UUID targetIntentId = UUID.nameUUIDFromBytes(("alternate-target:"
                + source.intentId()).getBytes(StandardCharsets.UTF_8));
        WorkIntent target = new WorkIntent(targetIntentId,
                source.projectId(),
                participant,
                original.provider(),
                source.taskId(),
                source.goal(),
                source.acceptance(),
                source.baseCommit(),
                source.selectors(),
                source.version() + 1,
                source.workGroupId(),
                source.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                source.role(),
                source.reviewTargetSelectors(),
                source.knownDependencies());
        UUID grantId = UUID.nameUUIDFromBytes(("alternate-grant:"
                + source.intentId()).getBytes(StandardCharsets.UTF_8));
        return new LaneContinuation(grantId,
                source.intentId(),
                target,
                source.participant(),
                participant,
                source.version(),
                scenario.continuation().snapshotReference());
    }

    private static void assertNoSuccessor(PredictionEventStore store, WorkIntent owner) {
        assertTrue(store.collaborationProjection().successorIntent(owner.intentId()).isEmpty());
        assertTrue(store.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.intentId().equals(owner.intentId())));
    }

    private static void assertSingleSuccessor(PredictionEventStore store, Scenario scenario) {
        assertEquals(scenario.continuation().targetIntent().intentId(),
                store.collaborationProjection().successorIntent(scenario.owner().intentId()).orElseThrow());
        assertEquals(1,
                store.collaborationProjection().activeIntents().stream()
                        .filter(intent -> intent.taskId().equals(scenario.owner().taskId())).count());
        assertEquals(Participant.State.DETACHED,
                store.collaborationProjection().participantState(scenario.owner().participant()).orElseThrow());
    }

    private static int continuationEvents(PredictionEventStore store) {
        return (int) store.events().stream()
                .filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).count();
    }

    private static void assertNoTemporaryFiles(Path root) throws IOException {
        Path events = root.resolve("events");
        try (var files = Files.list(events)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    @Test
    void crashBeforeDurableMoveLeavesSourceEligibleAndRetryCommitsOnce(@TempDir Path temp) throws Exception {
        Scenario scenario = prepare(temp.resolve("before-move"));
        installFailure(PredictionEventStore.AppendBoundary.BEFORE_DURABLE_MOVE);
        try {
            assertThrows(IOException.class, () -> continueLane(scenario));
        } finally {
            PredictionEventStore.clearTestFaultInjector();
        }

        PredictionEventStore restarted = new PredictionEventStore(scenario.root(), scenario.project());
        assertNoSuccessor(restarted, scenario.owner());
        assertEquals(Participant.State.RECOVERY_HELD,
                restarted.collaborationProjection().participantState(scenario.owner().participant()).orElseThrow());
        assertEquals(0, continuationEvents(restarted));
        assertNoTemporaryFiles(scenario.root());

        ContinuationResult result = assertDoesNotThrow(() -> continueLane(scenario));
        assertEquals("COMMITTED", result.status());
        assertSingleSuccessor(new PredictionEventStore(scenario.root(), scenario.project()), scenario);
    }

    @Test
    void crashAfterDurableMoveReplaysOneSuccessorAndRetryIsIdempotent(@TempDir Path temp) throws Exception {
        Scenario scenario = prepare(temp.resolve("after-move"));
        installFailure(PredictionEventStore.AppendBoundary.AFTER_DURABLE_MOVE);
        try {
            assertThrows(IOException.class, () -> continueLane(scenario));
        } finally {
            PredictionEventStore.clearTestFaultInjector();
        }

        PredictionEventStore restarted = new PredictionEventStore(scenario.root(), scenario.project());
        assertSingleSuccessor(restarted, scenario);
        assertEquals(1, continuationEvents(restarted));
        assertFalse(restarted.workGroupProjection().grantAvailable(scenario.continuation().grantId()));

        ContinuationResult retry = assertDoesNotThrow(() -> continueLane(scenario));
        assertEquals("COMMITTED", retry.status());
        assertEquals(1, continuationEvents(new PredictionEventStore(scenario.root(), scenario.project())));

        LaneContinuation competing = alternateContinuation(scenario);
        ContinuationResult losingRetry = new WorkIntentService(new PredictionEventStore(scenario.root(),
                scenario.project()), NodeIdentity.generate()).continueEligibleFromRecovery(competing);
        assertEquals("ALREADY_COMMITTED", losingRetry.status());
        assertEquals(scenario.continuation().targetIntent().intentId(), losingRetry.successorIntentId());
    }

    @Test
    void crashAfterProjectionModelsLostResponseWithoutDuplicateAuthority(@TempDir Path temp) throws Exception {
        Scenario scenario = prepare(temp.resolve("after-projection"));
        installFailure(PredictionEventStore.AppendBoundary.AFTER_PROJECTION);
        try {
            assertThrows(IOException.class, () -> continueLane(scenario));
        } finally {
            PredictionEventStore.clearTestFaultInjector();
        }

        PredictionEventStore restarted = new PredictionEventStore(scenario.root(), scenario.project());
        assertSingleSuccessor(restarted, scenario);
        assertEquals(1, continuationEvents(restarted));
        ContinuationResult retry = new WorkIntentService(restarted,
                NodeIdentity.generate()).continueEligibleFromRecovery(scenario.continuation());
        assertEquals("COMMITTED", retry.status());
        assertEquals(1, continuationEvents(new PredictionEventStore(scenario.root(), scenario.project())));
    }

    @Test
    @Timeout(120)
    void abruptProcessLossAtDurableBoundariesReplaysOneOwnershipOutcome(@TempDir Path temp) throws Exception {
        for (PredictionEventStore.AppendBoundary boundary : List.of(PredictionEventStore.AppendBoundary.BEFORE_DURABLE_MOVE,
                PredictionEventStore.AppendBoundary.AFTER_DURABLE_MOVE,
                PredictionEventStore.AppendBoundary.AFTER_PROJECTION)) {
            Scenario scenario = prepare(temp.resolve(boundary.name()));
            String continuation = Base64.getEncoder()
                    .encodeToString(CollaborationCodec.encodeContinuation(scenario.continuation()));
            Process process = new ProcessBuilder(javaExecutable(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrashWorker.class.getName(),
                    scenario.root().toString(),
                    scenario.project().toString(),
                    continuation,
                    boundary.name()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "crash worker timed out at " + boundary);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(86, process.exitValue(), output);

            PredictionEventStore restarted = new PredictionEventStore(scenario.root(), scenario.project());
            if (boundary == PredictionEventStore.AppendBoundary.BEFORE_DURABLE_MOVE) {
                assertNoSuccessor(restarted, scenario.owner());
                assertEquals(Participant.State.RECOVERY_HELD,
                        restarted.collaborationProjection().participantState(scenario.owner().participant())
                                .orElseThrow());
                assertEquals(0, continuationEvents(restarted));
                assertNoTemporaryFiles(scenario.root());
                assertEquals("COMMITTED", continueLane(scenario).status());
            } else {
                assertSingleSuccessor(restarted, scenario);
                assertEquals(1, continuationEvents(restarted));
                assertEquals("COMMITTED", continueLane(scenario).status());
            }
            assertSingleSuccessor(new PredictionEventStore(scenario.root(), scenario.project()), scenario);
            assertEquals(1, continuationEvents(new PredictionEventStore(scenario.root(), scenario.project())));
        }
    }

    /**
     * Child JVM used to terminate the current process at a durable event boundary.
     */
    public static final class CrashWorker {

        private CrashWorker() {
        }

        /**
         * Replays one encoded continuation in an isolated JVM.
         *
         * @param arguments event root, project ID, encoded continuation, and crash boundary
         * @throws Exception persistence or validation failure
         */
        static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[0]);
            UUID project = UUID.fromString(arguments[1]);
            LaneContinuation continuation = CollaborationCodec.decodeContinuation(Base64.getDecoder()
                    .decode(arguments[2]));
            String crashBoundary = arguments[3];
            PredictionEventStore.setTestFaultInjector(boundary -> {
                if (boundary.name().equals(crashBoundary)) {
                    Runtime.getRuntime().halt(86);
                }
            });
            new WorkIntentService(new PredictionEventStore(root, project),
                    NodeIdentity.generate()).continueEligibleFromRecovery(continuation);
            throw new IllegalStateException("CRASH_BOUNDARY_NOT_REACHED");
        }
    }

    private record Scenario(Path root, UUID project, WorkIntent owner, LaneContinuation continuation) {

    }
}
