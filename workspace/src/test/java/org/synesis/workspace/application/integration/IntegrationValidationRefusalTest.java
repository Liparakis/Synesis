package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.test.PortableTestCommand;
import org.synesis.workspace.test.TestGit;

/**
 * The integration pump with real Git when the project's validation command fails: which snapshot is refused, how
 * many repairs a lane has, and when the operator is asked instead.
 */
class IntegrationValidationRefusalTest {

    private static final List<String> FAILS = List.of("git", "rev-parse", "--verify", "refs/heads/branch-that-does-not-exist");
    private static final List<String> PASSES = List.of("git", "rev-parse", "--verify", "HEAD");

    record Lane(UUID id, UUID task, String participant) {
    }

    /** A Git project initialized by the service, with the lanes and snapshots a test publishes into it. */
    static final class Project {

        final Path root;
        final ProjectApplicationService service = new ProjectApplicationService();
        final ProjectLocation location;
        final NodeIdentity identity;
        final String initialHead;
        final UUID group = UUID.randomUUID();

        Project(Path root) throws Exception {
            this.root = root;
            git("init");
            git("config", "user.name", "Test");
            git("config", "user.email", "test@example.invalid");
            Files.writeString(root.resolve("README.md"), "base\n");
            git("add", "README.md");
            git("commit", "-m", "base");
            location = service.init(root).location();
            identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            initialHead = head();
        }

        String git(String... args) throws IOException {
            return TestGit.output(root, args);
        }

        String head() throws IOException {
            return git("rev-parse", "HEAD");
        }

        boolean headHas(String path) {
            try {
                git("cat-file", "-e", "HEAD:" + path);
                return true;
            } catch (IOException absent) {
                return false;
            }
        }

        boolean isAncestorOfHead(String commit) {
            try {
                git("merge-base", "--is-ancestor", commit, "HEAD");
                return true;
            } catch (IOException notAncestor) {
                return false;
            }
        }

        PredictionEventStore store() throws Exception {
            return new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId());
        }

        void declare(List<String> argv) throws Exception {
            declare(argv, 120);
        }

        void declare(List<String> argv, int timeoutSeconds) throws Exception {
            service.setValidation(root, new ProjectCommandSpec(argv, ".", timeoutSeconds), false);
        }

        Lane announce(String participant, String... paths) throws Exception {
            Lane lane = new Lane(UUID.randomUUID(), UUID.randomUUID(), participant);
            assertTrue(new WorkIntentService(store(), identity).announce(new WorkIntent(lane.id(), location.projectId(), participant, "codex", lane.task(), participant,
                    "integrated", head(), java.util.Arrays.stream(paths).map(ResourceSelector::pathExact).toList(), 1, group, WorkIntent.Status.ANNOUNCED)).acquired());
            return lane;
        }

        /** Publishes the lane's snapshot for one claim epoch: one commit that adds {@code path} on the current head. */
        String publish(Lane lane, String snapshotId, long epoch, String path, String content) throws Exception {
            return publish(lane, snapshotId, epoch, Map.of(path, content));
        }

        String publish(Lane lane, String snapshotId, long epoch, String path, String content, List<String> capabilityDependencies) throws Exception {
            return publish(lane, snapshotId, epoch, Map.of(path, content), capabilityDependencies);
        }

        /** As above for a snapshot that changes several files, all of them claimed by the lane. */
        String publish(Lane lane, String snapshotId, long epoch, Map<String, String> files) throws Exception {
            return publish(lane, snapshotId, epoch, files, List.of());
        }

        String publish(Lane lane, String snapshotId, long epoch, Map<String, String> files, List<String> capabilityDependencies) throws Exception {
            String base = head();
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.createDirectories(root.resolve(file.getKey()).getParent());
                Files.writeString(root.resolve(file.getKey()), file.getValue());
                git("add", file.getKey());
            }
            List<String> paths = files.keySet().stream().sorted().toList();
            git("commit", "-m", snapshotId);
            String commit = head();
            git("reset", "--hard", base);
            String preparedRef = "refs/synesis/prepared/cmp_" + snapshotId;
            String snapshotRef = "refs/synesis/snapshots/" + snapshotId;
            git("update-ref", preparedRef, commit);
            git("update-ref", snapshotRef, commit);
            String tree = git("rev-parse", commit + "^{tree}");
            SnapshotProvenance provenance = new SnapshotProvenance(group, lane.id(), WorkIntent.defaultAuthorityLineage(lane.id()), lane.participant(), "binding", epoch,
                    List.of(), List.of(), paths.stream().map(path -> "PATH_EXACT:" + path).toList(), snapshotRef, tree, "manifest");
            TaskSnapshotPayload snapshot = new TaskSnapshotPayload(lane.task(), snapshotId, identity.nodeId(), "sup", "worker", "session", base, commit, paths, capabilityDependencies,
                    "snapshot", provenance, false);
            CompletionPreparedPayload prepared = new CompletionPreparedPayload(lane.task(), "cmp_" + snapshotId, lane.id(), epoch, base, preparedRef, tree, snapshot.changedPaths());
            PredictionEventStore store = store();
            store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, identity.nodeId(), prepared.encode(), identity);
            store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
            return commit;
        }

        AgentResponse pump() throws Exception {
            return new IntegrationOrchestrationService().orchestrateIntegration(root, store(), identity);
        }

        AgentResponse pumpAskedByOperator() throws Exception {
            return new IntegrationOrchestrationService().orchestrateIntegration(root, store(), identity, true);
        }

        long count(PredictionEventType type) throws Exception {
            return store().events().stream().filter(event -> event.type() == type).count();
        }

        TaskCompletionState state(String snapshotId) throws Exception {
            return store().taskCompletionProjection().snapshotState(snapshotId).orElseThrow();
        }

        java.util.Optional<OperatorRequest> openRequest() throws Exception {
            return store().operatorRequestProjection().open(OperatorRequest.INTEGRATION_VALIDATION_FAILING);
        }

        ValidationRefusalPayload refusal(String snapshotId) throws Exception {
            return store().taskCompletionProjection().validationRefusal(snapshotId).orElseThrow();
        }
    }

    private static Map<?, ?> result(AgentResponse response) {
        return (Map<?, ?>) response.result();
    }

    private static void assertRefused(AgentResponse response, String snapshotId) {
        assertEquals(AgentStatus.BLOCKED, response.status(), response.toString());
        assertEquals(AgentReason.INTEGRATION_FAILED, response.reason(), response.toString());
        assertEquals(AgentNextAction.RETRY, response.nextAction(), response.toString());
        assertEquals("integration", result(response).get("phase"));
        assertEquals(snapshotId, result(response).get("refusedSnapshotId"));
        assertTrue(result(response).containsKey("validation"), response.toString());
    }

    private static void assertOperatorAsked(AgentResponse response) {
        assertEquals(AgentStatus.BLOCKED, response.status(), response.toString());
        assertEquals(AgentReason.OPERATOR_REQUIRED, response.reason(), response.toString());
        assertEquals(AgentNextAction.REQUEST_HUMAN_HELP, response.nextAction(), response.toString());
        assertEquals(OperatorRequest.INTEGRATION_VALIDATION_FAILING, ((Map<?, ?>) result(response).get("operatorRequest")).get("condition"));
    }

    @Test
    void onlyTheSnapshotAddedLastToTheAttemptIsRefusedAndTheOtherIntegratesOnTheNextPass(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        // Fails exactly while the last snapshot's file is in the tree: b.txt differs from the initial head.
        project.declare(List.of("git", "diff", "--quiet", project.initialHead, "HEAD", "--", "src/b.txt"));
        Lane first = project.announce("agt_first", "src/a.txt");
        Lane second = project.announce("agt_second", "src/b.txt");
        project.publish(first, "snap_1_first", 1, "src/a.txt", "a\n");
        project.publish(second, "snap_2_second", 1, "src/b.txt", "b\n");
        String head = project.head();

        AgentResponse refused = project.pump();

        assertRefused(refused, "snap_2_second");
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, project.state("snap_2_second"));
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1_first"));
        assertEquals(head, project.head(), "the control branch did not move");
        assertFalse(project.headHas("src/b.txt"));
        assertFalse(project.headHas("src/a.txt"));

        AgentResponse integrated = project.pump();

        assertEquals(AgentStatus.COMPLETED, integrated.status(), integrated.toString());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1_first"));
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, project.state("snap_2_second"));
        assertTrue(project.headHas("src/a.txt"));
        assertFalse(project.headHas("src/b.txt"), "the control branch never held the refused content");
    }

    @Test
    void afterTwoRefusalsOfOneLaneTheThirdFailureRefusesNothingAndAsksTheOperator(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PortableTestCommand.fixture("fail", "the failure line", "3"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        assertRefused(project.pump(), "snap_1");
        project.publish(lane, "snap_2", 2, "src/f.txt", "two\n");
        assertRefused(project.pump(), "snap_2");
        project.publish(lane, "snap_3", 3, "src/f.txt", "three\n");

        AgentResponse asked = project.pump();

        assertOperatorAsked(asked);
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_3"), "the snapshot stays eligible");
        assertEquals(2, project.store().taskCompletionProjection().validationRefusals(lane.task(), WorkIntent.defaultAuthorityLineage(lane.id())));
        OperatorRequest request = project.openRequest().orElseThrow();
        assertTrue(request.evidence().contains("the failure line"), request.evidence());
        assertTrue(request.summary().contains("2 repairs"), request.summary());

        long attempts = project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED);
        AgentResponse again = project.pump();

        assertOperatorAsked(again);
        assertEquals(attempts, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED), "an agent's pass starts no attempt while the request is open");

        project.declare(PASSES);
        AgentResponse integrated = project.pumpAskedByOperator();

        assertEquals(AgentStatus.COMPLETED, integrated.status(), integrated.toString());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_3"));
        assertTrue(project.openRequest().isEmpty(), "the request closes when the command passes");
        assertTrue(project.headHas("src/f.txt"));
    }

    @Test
    void anOperatorRecheckThatFailsAgainKeepsTheRequestAndRefusesNothing(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(FAILS);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        assertRefused(project.pump(), "snap_1");
        project.publish(lane, "snap_2", 2, "src/f.txt", "two\n");
        assertRefused(project.pump(), "snap_2");
        project.publish(lane, "snap_3", 3, "src/f.txt", "three\n");
        assertOperatorAsked(project.pump());
        long attempts = project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED);

        AgentResponse rechecked = project.pumpAskedByOperator();

        assertOperatorAsked(rechecked);
        assertEquals(attempts + 1, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED), "the operator's pass runs the command");
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_3"));
        assertEquals(1, project.store().operatorRequestProjection().open().size(), "one request, not one per recheck");
    }

    @Test
    void aCommandThatCannotBeStartedRefusesNothingRaisesTheRequestAndClearingItIntegrates(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("synesis-no-such-executable-0f3a"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        assertOperatorAsked(project.pump());

        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1"));
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_VALIDATION_REFUSED));
        assertTrue(project.openRequest().isPresent());

        project.service.clearValidation(root);
        AgentResponse integrated = project.pump();

        assertEquals(AgentStatus.COMPLETED, integrated.status(), integrated.toString());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        assertTrue(project.openRequest().isEmpty(), "the request closes when the project declares no command");
    }

    @Test
    void aCommandThatTimesOutRefusesNothingAndRaisesTheRequest(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PortableTestCommand.fixture("partial", "x", "1", "30"), 1);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        assertOperatorAsked(project.pump());

        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1"));
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_VALIDATION_REFUSED));
        assertTrue(project.openRequest().orElseThrow().summary().contains("no verdict"), project.openRequest().orElseThrow().summary());
        assertFalse(project.headHas("src/f.txt"));
    }

    @Test
    void theRequestClosesOncePassesObserveThatNoLaneIsLeftAndNothingWaits(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("synesis-no-such-executable-0f3a"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        assertOperatorAsked(project.pump());
        new WorkIntentService(project.store(), project.identity).release(lane.id(), lane.participant());
        assertTrue(project.store().collaborationProjection().activeIntents().isEmpty(), "no lane is left");

        int passes = 0;
        while (project.openRequest().isPresent() && passes < 3) {
            project.pump();
            passes++;
        }

        assertTrue(project.openRequest().isEmpty(), "still open after " + passes + " passes; snapshot state " + project.state("snap_1"));
    }

    @Test
    void aProviderWhoseLanesWaitIsHeldWhileTheValidationRequestIsOpen(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("synesis-no-such-executable-0f3a"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        assertFalse(new EventLogObserver(project.location).observe().heldByOperator("codex"), "nothing stops integration yet");

        assertOperatorAsked(project.pump());

        var observation = new EventLogObserver(project.location).observe();
        assertTrue(observation.heldByOperator("codex"));
        assertFalse(observation.heldByOperator("claude"), "a provider with no lane is not held");
    }

    @Test
    void anAttemptLeftStartedByADeadProcessIsEndedAsInterruptedAndTheWorkIntegrates(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        startAttempt(project, "att_dead", "snap_1", project.head());
        assertEquals(TaskCompletionState.INTEGRATING, project.state("snap_1"));

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        IntegrationAttemptPayload ended = project.store().events().stream().filter(event -> event.type() == PredictionEventType.INTEGRATION_ATTEMPT_FAILED)
                .map(event -> decode(event.payload())).filter(payload -> payload.attemptId().equals("att_dead")).findFirst().orElseThrow();
        assertEquals("pending", ended.status());
        assertEquals("INTERRUPTED", ended.failureReason());
    }

    @Test
    void anAttemptThatNeverRecordedACommitIsInterruptedWhereverTheHeadHasMovedAndTheWorkIntegratesOnTheNewHead(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        startAttempt(project, "att_other_head", "snap_1", project.head());
        Files.writeString(root.resolve("unrelated.txt"), "moved\n");
        project.git("add", "unrelated.txt");
        project.git("commit", "-m", "unrelated commit on the control branch");

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertEquals("INTERRUPTED", endedReason(project, "att_other_head"));
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        assertTrue(project.headHas("src/f.txt"));
        assertTrue(project.headHas("unrelated.txt"));
    }

    @Test
    void anAttemptWhoseRecordedCommitIsNotInTheHeadsHistoryIsInterrupted(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        String snapshotCommit = project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        startAttempt(project, "att_never_advanced", "snap_1", project.head());
        recordCommit(project, "att_never_advanced", "snap_1", project.head(), snapshotCommit);
        assertFalse(project.isAncestorOfHead(snapshotCommit));

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertEquals("INTERRUPTED", endedReason(project, "att_never_advanced"));
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
    }

    @Test
    void anAttemptWhoseRecordedCommitIsTheHeadIsCompletedByTheRecoveryAndNotInterrupted(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        String snapshotCommit = project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        String expected = project.head();
        startAttempt(project, "att_advanced", "snap_1", expected);
        recordCommit(project, "att_advanced", "snap_1", expected, snapshotCommit);
        project.git("merge", "--ff-only", snapshotCommit);

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertEquals(Boolean.TRUE, result(response).get("recovered"), response.toString());
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_ATTEMPT_FAILED));
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
    }

    @Test
    void anAttemptWhoseRecordedCommitIsBehindTheHeadKeepsWaiting(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        String snapshotCommit = project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        String expected = project.head();
        startAttempt(project, "att_behind", "snap_1", expected);
        recordCommit(project, "att_behind", "snap_1", expected, snapshotCommit);
        project.git("merge", "--ff-only", snapshotCommit);
        Files.writeString(root.resolve("unrelated.txt"), "moved\n");
        project.git("add", "unrelated.txt");
        project.git("commit", "-m", "unrelated commit after the attempt");
        assertTrue(project.isAncestorOfHead(snapshotCommit));

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.WAITING, response.status(), response.toString());
        assertEquals(AgentReason.INTEGRATION_PENDING, response.reason(), response.toString());
        assertEquals(0, project.count(PredictionEventType.INTEGRATION_ATTEMPT_FAILED));
        assertEquals(TaskCompletionState.INTEGRATING, project.state("snap_1"));
    }

    private static String endedReason(Project project, String attemptId) throws Exception {
        return project.store().events().stream().filter(event -> event.type() == PredictionEventType.INTEGRATION_ATTEMPT_FAILED)
                .map(event -> decode(event.payload())).filter(payload -> payload.attemptId().equals(attemptId)).findFirst().orElseThrow().failureReason();
    }

    private static void recordCommit(Project project, String attemptId, String snapshotId, String expectedHead, String commit) throws Exception {
        IntegrationAttemptPayload created = new IntegrationAttemptPayload(attemptId, project.location.projectId(), List.of(snapshotId), expectedHead, commit, "started", "");
        project.store().append(UUID.randomUUID(), PredictionEventType.INTEGRATION_COMMIT_CREATED, project.identity.nodeId(), created.encode(), project.identity);
    }

    @Test
    void theCommandRunsOnTheCommitTheControlBranchThenPointsAt(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("git", "rev-parse", "HEAD"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        String candidate = ((Map<?, ?>) result(response).get("validation")).get("stdout").toString().trim();
        assertEquals(project.head(), candidate);
        assertNotEquals(project.initialHead, candidate);
    }

    @Test
    void aRefusalNamesTheCommitTheFailingCommandRanOnAndThatCommitNeverEntersTheControlBranch(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        // Prints the candidate commit, then fails on the second argument.
        project.declare(List.of("git", "rev-parse", "HEAD", "no-such-revision-0f3a"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        String head = project.head();

        assertRefused(project.pump(), "snap_1");

        ValidationRefusalPayload refusal = project.refusal("snap_1");
        Matcher printed = Pattern.compile("\\b[0-9a-f]{40}\\b").matcher(refusal.output());
        assertTrue(printed.find(), refusal.output());
        assertEquals(printed.group(), refusal.candidateCommit());
        assertNotEquals(head, refusal.candidateCommit());
        assertFalse(project.isAncestorOfHead(refusal.candidateCommit()));
        assertEquals(head, project.head());
        assertNotEquals(0, refusal.exitCode(), "a non-zero exit is the verdict");
        assertEquals("non_zero_exit", refusal.outcome());
        assertEquals(List.of("git", "rev-parse", "HEAD", "no-such-revision-0f3a"), ProviderJson.parse(refusal.command()));
        assertEquals(".", refusal.workingDirectory());
    }

    @Test
    void readyWorkStillIntegratesAfterTheValidationCommandWasSet(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertTrue(project.headHas("src/f.txt"));
        assertEquals("", project.git("status", "--porcelain").trim());
    }

    /** Two refusals of one lane, then a third snapshot whose failure reaches the operator: the request is open when this returns. */
    private static void openTheRequestAfterTwoRepairs(Project project, Lane lane, String path) throws Exception {
        project.publish(lane, "snap_1", 1, path, "one\n");
        assertRefused(project.pump(), "snap_1");
        project.publish(lane, "snap_2", 2, path, "two\n");
        assertRefused(project.pump(), "snap_2");
        project.publish(lane, "snap_3", 3, path, "three\n");
        assertOperatorAsked(project.pump());
    }

    @Test
    void anOperatorRecheckThatFailsWithOtherOutputReplacesTheRequestWithOneThatCarriesIt(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PortableTestCommand.fixture("fail", "the first failure", "3"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        openTheRequestAfterTwoRepairs(project, lane, "src/f.txt");
        OperatorRequest first = project.openRequest().orElseThrow();
        assertTrue(first.evidence().contains("the first failure"), first.evidence());
        project.declare(PortableTestCommand.fixture("fail", "a different failure", "3"));

        assertOperatorAsked(project.pumpAskedByOperator());

        OperatorRequest second = project.openRequest().orElseThrow();
        assertNotEquals(first.id(), second.id());
        assertTrue(second.evidence().contains("a different failure"), second.evidence());
        assertFalse(second.evidence().contains("the first failure"), second.evidence());
        assertEquals(1, project.store().operatorRequestProjection().open().size());
        OperatorRequest closed = project.store().operatorRequestProjection().all().stream().filter(request -> request.id().equals(first.id())).findFirst().orElseThrow();
        assertEquals(OperatorRequest.State.CLOSED, closed.state());
    }

    @Test
    void aRecheckThatFailsTheSameWayLeavesExactlyOneOpenRequestWithTheSameOutput(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PortableTestCommand.fixture("fail", "the same failure", "3"));
        Lane lane = project.announce("agt_lane", "src/f.txt");
        openTheRequestAfterTwoRepairs(project, lane, "src/f.txt");

        assertOperatorAsked(project.pumpAskedByOperator());
        assertOperatorAsked(project.pumpAskedByOperator());

        assertEquals(1, project.store().operatorRequestProjection().open().size());
        assertTrue(project.openRequest().orElseThrow().evidence().contains("the same failure"));
    }

    @Test
    void aLaneOutOfRepairsIsNotJoinedByTheNextPublisherWhenTheOperatorRechecks(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(FAILS);
        Lane exhausted = project.announce("agt_b", "src/b.txt");
        openTheRequestAfterTwoRepairs(project, exhausted, "src/b.txt");
        Lane later = project.announce("agt_c", "src/c.txt");
        project.publish(later, "snap_c1", 1, "src/c.txt", "c\n");
        assertEquals(2, project.count(PredictionEventType.INTEGRATION_VALIDATION_REFUSED));

        AgentResponse rechecked = project.pumpAskedByOperator();

        assertOperatorAsked(rechecked);
        assertEquals(2, project.count(PredictionEventType.INTEGRATION_VALIDATION_REFUSED), "nobody is refused for a failure the exhausted lane keeps in every attempt");
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_c1"));
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_3"));
        assertTrue(project.openRequest().isPresent());
        assertEquals(1, project.store().operatorRequestProjection().open().size());
    }

    @Test
    void anAgentsPassStartsNoAttemptWhileTheRequestIsOpenEvenForWorkClassifiedFirst(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(FAILS);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        openTheRequestAfterTwoRepairs(project, lane, "src/f.txt");
        Lane other = project.announce("agt_other", "src/other.txt");
        project.publish(other, "snap_other", 1, "src/other.txt", "other\n");
        long attempts = project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED);

        AgentResponse first = project.pump();
        AgentResponse second = project.pump();

        assertOperatorAsked(first);
        assertOperatorAsked(second);
        assertEquals(attempts, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_other"));
    }

    @Test
    void anAttemptLeftStartedTwiceByADeadProcessAsksTheOperatorAndStartsNoThirdAttempt(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(PASSES);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        startAttempt(project, "att_dead_1", "snap_1", project.head());
        endAttemptAsInterrupted(project, "att_dead_1", "snap_1", project.head());
        startAttempt(project, "att_dead_2", "snap_1", project.head());
        long started = project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED);

        AgentResponse response = project.pump();

        assertOperatorAsked(response);
        assertEquals("INTERRUPTED", endedReason(project, "att_dead_2"));
        assertTrue(project.openRequest().orElseThrow().summary().contains("died twice"), project.openRequest().orElseThrow().summary());
        assertEquals(started, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED), "no third attempt");
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_1"));
        assertFalse(project.headHas("src/f.txt"));

        assertOperatorAsked(project.pump());
        assertEquals(started, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
    }

    @Test
    void anAttemptLeftStartedTwiceInAProjectWithoutACommandIsSimplyMadeAgain(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        Lane lane = project.announce("agt_lane", "src/f.txt");
        project.publish(lane, "snap_1", 1, "src/f.txt", "one\n");
        startAttempt(project, "att_dead_1", "snap_1", project.head());
        endAttemptAsInterrupted(project, "att_dead_1", "snap_1", project.head());
        startAttempt(project, "att_dead_2", "snap_1", project.head());

        AgentResponse response = project.pump();

        assertEquals(AgentStatus.COMPLETED, response.status(), response.toString());
        assertEquals("INTERRUPTED", endedReason(project, "att_dead_2"));
        assertTrue(project.openRequest().isEmpty());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_1"));
        assertEquals(3, project.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
    }

    @Test
    void aConsumerOfAnOwnerWhoseSnapshotWasRefusedBecomesReadyOnceTheOwnersRepairIntegrates(@TempDir Path root) throws Exception {
        Project project = new Project(root);
        project.declare(List.of("git", "grep", "-q", "good", "HEAD", "--", "src/fix.txt"));
        Lane owner = project.announce("agt_owner", "src/api.txt", "src/fix.txt");
        Lane consumer = project.announce("agt_consumer", "tests/api.txt");
        String base = project.head();
        String ownerCommit = project.publish(owner, "snap_owner_1", 1, Map.of("src/api.txt", "api\n", "src/fix.txt", "bad\n"));
        bindCapability(project, owner, base, ownerCommit);
        project.publish(consumer, "snap_consumer", 1, "tests/api.txt", "test\n", List.of(CAPABILITY.value()));

        assertRefused(project.pump(), "snap_owner_1");
        assertEquals(TaskCompletionState.VALIDATION_REFUSED, project.state("snap_owner_1"));
        assertEquals(AgentStatus.WAITING, project.pump().status(), "the consumer waits while the owner has no snapshot that carries its content");
        assertEquals(TaskCompletionState.INTEGRATION_PENDING, project.state("snap_consumer"));
        project.publish(owner, "snap_owner_2", 2, Map.of("src/api.txt", "api\n", "src/fix.txt", "good\n"));

        assertEquals(AgentStatus.COMPLETED, project.pump().status());
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_owner_2"));
        AgentResponse consumerPass = project.pump();

        assertEquals(AgentStatus.COMPLETED, consumerPass.status(), "the consumer still waits on the refused snapshot: " + consumerPass);
        assertEquals(TaskCompletionState.INTEGRATED, project.state("snap_consumer"));
        assertTrue(project.headHas("tests/api.txt"));
    }

    private static final CapabilityRequestHandle CAPABILITY = CapabilityRequestHandle.parse("req_123456789012");

    /** Records a validated implementation of the owner's lane, published at {@code ownerCommit}, for the consumer's snapshot to depend on. */
    private static void bindCapability(Project project, Lane owner, String base, String ownerCommit) throws Exception {
        PredictionEventStore store = project.store();
        UUID lineage = WorkIntent.defaultAuthorityLineage(owner.id());
        String node = project.identity.nodeId();
        CapabilityContract contract = new CapabilityContract("Task", "TaskTracker", List.of("API"), List.of("owner tests"));
        CapabilityRequestPayload request = new CapabilityRequestPayload(CAPABILITY, "task-tracker-api", node, "sup", "dependent", node, "sup", "owner", lineage, contract,
                CapabilityLifecycleState.AWAITING_OWNER, null, 0);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, node, request.encode(), project.identity);
        CapabilityRequestPayload accepted = new CapabilityRequestPayload(CAPABILITY, "task-tracker-api", node, "sup", "dependent", node, "sup", "owner", lineage, contract,
                CapabilityLifecycleState.ACCEPTED, null, store.capabilityRequestProjection().findByHandle(CAPABILITY).orElseThrow().contractRevision());
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, node, accepted.encode(), project.identity);
        ImplementationEventPayload published = new ImplementationEventPayload(CAPABILITY, lineage, 1, base, ownerCommit, List.of("src/api.txt"), "owner", "", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED, node, published.encode(), project.identity);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_VALIDATION_STARTED, node, published.encode(), project.identity);
        ImplementationEventPayload validated = new ImplementationEventPayload(CAPABILITY, lineage, 1, base, ownerCommit, List.of(), "validated", "accepted", "", List.of(), "");
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED, node, validated.encode(), project.identity);
    }

    private static void endAttemptAsInterrupted(Project project, String attemptId, String snapshotId, String expectedHead) throws Exception {
        IntegrationAttemptPayload ended = new IntegrationAttemptPayload(attemptId, project.location.projectId(), List.of(snapshotId), expectedHead, "", "pending", "INTERRUPTED");
        project.store().append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, project.identity.nodeId(), ended.encode(), project.identity);
    }

    private static void startAttempt(Project project, String attemptId, String snapshotId, String expectedHead) throws Exception {
        IntegrationAttemptPayload started = new IntegrationAttemptPayload(attemptId, project.location.projectId(), List.of(snapshotId), expectedHead, "", "started", "");
        project.store().append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, project.identity.nodeId(), started.encode(), project.identity);
    }

    private static IntegrationAttemptPayload decode(byte[] payload) {
        try {
            return IntegrationAttemptPayload.decode(payload);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
