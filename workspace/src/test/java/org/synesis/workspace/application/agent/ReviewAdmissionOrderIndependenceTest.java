package org.synesis.workspace.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.TestGit;

/** Verifies review direction is independent of participant arrival order. */
final class ReviewAdmissionOrderIndependenceTest {

    /** The producer and reviewer must have the same semantic workflow in both orders. */
    @Test
    void producerFirstAndReviewerFirstHaveEquivalentReviewDirection(@TempDir Path temp) throws Exception {
        ReviewRun producerFirst = run(temp.resolve("producer-first"), false);
        ReviewRun reviewerFirst = run(temp.resolve("reviewer-first"), true);

        assertEquals(producerFirst.semanticDirection(), reviewerFirst.semanticDirection());
        assertEquals(producerFirst.producerIntentId(), producerFirst.grant().targetIntentId());
        assertEquals(reviewerFirst.producerIntentId(), reviewerFirst.grant().targetIntentId());
        assertEquals(producerFirst.reviewerParticipant(), producerFirst.grant().targetParticipant());
        assertEquals(reviewerFirst.reviewerParticipant(), reviewerFirst.grant().targetParticipant());
    }

    /** A producer must not receive review semantics merely because it arrived second. */
    @Test
    void reviewerFirstNeverGrantsProducerReviewSemantics(@TempDir Path temp) throws Exception {
        ReviewRun run = run(temp.resolve("wrong-peer"), true);

        assertFalse(run.producerBeforeMutation().toJson().contains("REVIEW_ADMISSION_REQUIRED"),
                run.producerBeforeMutation().toJson());
        assertEquals(run.producerIntentId().toString(), run.reviewRequestIntentId());
        assertEquals(run.reviewerParticipant(), run.grant().targetParticipant());
        assertEquals(run.producerIntentId(), run.grant().targetIntentId());
        assertEquals(AgentReason.SNAPSHOT_PUBLICATION_REQUIRED, run.producerAfterGrant().reason());
        assertEquals(AgentNextAction.FINISH_LANE, run.producerAfterGrant().nextAction());
    }

    /** A reviewer admitted before its producer must wait instead of completing its lane. */
    @Test
    @SuppressWarnings("unchecked")
    void reviewerFirstWaitsForProducerBeforeNoChangeCompletion(@TempDir Path temp) throws Exception {
        Path project = initializeProject(temp.resolve("reviewer-before-producer"));
        installProviders();
        AgentSessionServiceFixture sessions = establishSessions(project, "reviewer-before-producer");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        ClaimResult reviewer = collaboration.announce(project, "antigravity", "reviewer-before-producer-reviewer",
                "Review the producer source", "Inspect the producer snapshot",
                List.of(ResourceSelector.pathExact("tests/todo_test.py")), null,
                WorkIntent.CompletionMode.NO_CHANGE_ALLOWED, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/todo.py")));
        assertTrue(reviewer.acquired());

        AgentNextActionService next = new AgentNextActionService();
        AgentResponse waiting = next.getNextAction(new AgentNextActionService.NextActionRequest(
                project, "antigravity", "reviewer-before-producer-reviewer"));
        assertEquals(AgentNextAction.WAIT, waiting.nextAction(), waiting.toJson());
        assertEquals(AgentReason.VALIDATION_REQUIRED, waiting.reason());
        Map<String, Object> waitingResult = map(waiting.result());
        assertEquals("REVIEWER_PENDING", waitingResult.get("state"));
        assertFalse(waiting.toJson().contains("NO_CHANGE_COMPLETION_READY"), waiting.toJson());

        var location = new ProjectApplicationService().locate(project);
        var reviewerBinding = new ProviderSessionBindingService().find(location, "antigravity",
                "reviewer-before-producer-reviewer").orElseThrow();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        AgentResponse prematureCompletion = new AgentTaskCompletionService().completeTask(
                new AgentTaskCompletionService.CompleteTaskRequest(project, "antigravity",
                        "reviewer-before-producer-reviewer", "must wait", AgentTaskCompletionService.CompletionOutcome.NO_CHANGE,
                        reviewer.intent().intentId(), reviewer.intent().workGroupId(), reviewer.intent().version(),
                        store.workGroupProjection().group(reviewer.intent().workGroupId()).orElseThrow().version(),
                        store.headSequence(), WorkspaceCollaborationService.participantHandle(reviewerBinding.sessionId())));
        assertEquals(AgentStatus.BLOCKED, prematureCompletion.status(), prematureCompletion.toJson());
        assertEquals(AgentReason.TASK_NOT_READY, prematureCompletion.reason());
        assertEquals("NO_CHANGE_REVIEWER_PENDING", map(prematureCompletion.result()).get("reason"));

        sessions.ensure("codex", "reviewer-before-producer-producer");
        ClaimResult producer = collaboration.announce(project, "codex", "reviewer-before-producer-producer",
                "Produce the source", "Publish the producer snapshot",
                List.of(ResourceSelector.pathExact("src/todo.py")), reviewer.intent().workGroupId(),
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of());
        assertTrue(producer.acquired());

        AgentResponse admission = next.getNextAction(new AgentNextActionService.NextActionRequest(
                project, "antigravity", "reviewer-before-producer-reviewer"));
        Map<String, Object> admissionResult = map(admission.result());
        Map<String, Object> workflow = map(admissionResult.get("workflow"));
        Map<String, Object> arguments = map(workflow.get("arguments"));
        Map<String, Object> payload = map(arguments.get("payload"));
        assertEquals(AgentNextAction.REQUEST_COORDINATION, admission.nextAction(), admission.toJson());
        assertEquals("request_coordination", workflow.get("recommendedTool"), admission.toJson());
        assertEquals(producer.intent().intentId().toString(), payload.get("intentId"), admission.toJson());
        assertEquals(producer.intent().intentId().toString(), payload.get("reviewedIntentId"), admission.toJson());
        assertEquals(WorkspaceCollaborationService.participantHandle(
                new ProviderSessionBindingService().find(new ProjectApplicationService().locate(project),
                        "antigravity", "reviewer-before-producer-reviewer").orElseThrow().sessionId()),
                payload.get("reviewerParticipant"));
    }

    /** An explicit review target selects the intended producer among three participants. */
    @Test
    @SuppressWarnings("unchecked")
    void reviewerTargetSelectorDoesNotChooseFirstUnrelatedProducer(@TempDir Path temp) throws Exception {
        Path project = initializeProject(temp.resolve("three-participant"));
        installProviders();
        AgentSessionServiceFixture sessions = establishSessions(project, "three");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();

        var unrelated = collaboration.announce(project, "codex", "three-unrelated",
                "Produce unrelated source", "Publish unrelated source snapshot",
                List.of(ResourceSelector.pathExact("src/unrelated.py")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of());
        var producer = collaboration.announce(project, "codex", "three-producer",
                "Produce source", "Publish source snapshot",
                List.of(ResourceSelector.pathExact("src/producer.py")), null,
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of());
        var reviewer = collaboration.announce(project, "antigravity", "three-reviewer",
                "Review producer source", "Review the producer snapshot",
                List.of(ResourceSelector.pathExact("tests/reviewer.py")), producer.intent().workGroupId(),
                WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/producer.py")));
        assertTrue(unrelated.acquired());
        assertTrue(producer.acquired());
        assertTrue(reviewer.acquired());
        assertNotNull(unrelated.intent());
        assertNotNull(producer.intent());
        assertNotNull(reviewer.intent());
        assertFalse(unrelated.intent().intentId().equals(producer.intent().intentId()));
        assertFalse(unrelated.intent().intentId().equals(reviewer.intent().intentId()));
        assertFalse(producer.intent().intentId().equals(reviewer.intent().intentId()));
        assertEquals(unrelated.intent().workGroupId(), producer.intent().workGroupId());

        AgentResponse response = new AgentNextActionService().getNextAction(
                new AgentNextActionService.NextActionRequest(project, "antigravity", "three-reviewer"));
        assertEquals(AgentNextAction.REQUEST_COORDINATION, response.nextAction(), response.toJson());
        Map<String, Object> result = (Map<String, Object>) response.result();
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        Map<String, Object> arguments = (Map<String, Object>) workflow.get("arguments");
        Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
        assertEquals(producer.intent().intentId().toString(), payload.get("intentId"));
        assertFalse(unrelated.intent().intentId().toString().equals(payload.get("intentId")));
        assertNotNull(reviewer.intent());
    }

    /** Runs one complete admission-to-producer-publication projection for one order. */
    private static ReviewRun run(Path project, boolean reviewerFirst) throws Exception {
        initializeProject(project);
        installProviders();
        establishSessions(project, reviewerFirst ? "reviewer-first" : "producer-first");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        String producerConnection = reviewerFirst ? "reviewer-first-producer" : "producer-first-producer";
        String reviewerConnection = reviewerFirst ? "reviewer-first-reviewer" : "producer-first-reviewer";

        ClaimResult producer;
        ClaimResult reviewer;
        if (reviewerFirst) {
            reviewer = collaboration.announce(project, "antigravity", reviewerConnection,
                    "Review producer source", "Review the producer snapshot",
                    List.of(ResourceSelector.pathExact("tests/todo_test.py")), null,
                    WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.REVIEWER,
                    List.of(ResourceSelector.pathExact("src/todo.py")));
            producer = collaboration.announce(project, "codex", producerConnection,
                    "Produce source", "Publish source snapshot",
                    List.of(ResourceSelector.pathExact("src/todo.py")), reviewer.intent().workGroupId(),
                    WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of());
        } else {
            producer = collaboration.announce(project, "codex", producerConnection,
                    "Produce source", "Publish source snapshot",
                    List.of(ResourceSelector.pathExact("src/todo.py")), null,
                    WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.PRODUCER, List.of());
            reviewer = collaboration.announce(project, "antigravity", reviewerConnection,
                    "Review producer source", "Review the producer snapshot",
                    List.of(ResourceSelector.pathExact("tests/todo_test.py")), producer.intent().workGroupId(),
                    WorkIntent.CompletionMode.SNAPSHOT_REQUIRED, WorkIntent.Role.REVIEWER,
                    List.of(ResourceSelector.pathExact("src/todo.py")));
        }
        assertTrue(producer.acquired());
        assertTrue(reviewer.acquired());

        AgentNextActionService next = new AgentNextActionService();
        AgentResponse reviewerAdmission = next.getNextAction(new AgentNextActionService.NextActionRequest(
                project, "antigravity", reviewerConnection));
        Map<String, Object> reviewerResult = map(reviewerAdmission.result());
        Map<String, Object> workflow = map(reviewerResult.get("workflow"));
        Map<String, Object> arguments = map(workflow.get("arguments"));
        Map<String, Object> payload = map(arguments.get("payload"));
        assertEquals("request_coordination", workflow.get("recommendedTool"), reviewerAdmission.toJson());
        assertEquals(producer.intent().intentId().toString(), payload.get("intentId"), reviewerAdmission.toJson());

        AgentResponse producerBefore = next.getNextAction(new AgentNextActionService.NextActionRequest(
                project, "codex", producerConnection));
        assertFalse(producerBefore.toJson().contains("REVIEW_ADMISSION_REQUIRED"), producerBefore.toJson());

        CoordinationRequest request = collaboration.request(project, "antigravity", reviewerConnection,
                producer.intent().intentId(), CoordinationRequest.Kind.REVIEW, "Review the producer snapshot");
        collaboration.respond(project, "codex", producerConnection, request.requestId(),
                CoordinationRequest.Status.ACCEPTED, "admitted");
        PredictionEventStore store = new PredictionEventStore(
                project.resolve(".synesis/coordination"), new ProjectApplicationService().locate(project).projectId());
        LaneGrant grant = store.workGroupProjection().grants().stream().findFirst().orElseThrow();
        String reviewerParticipant = WorkspaceCollaborationService.participantHandle(
                new ProviderSessionBindingService().find(new ProjectApplicationService().locate(project),
                        "antigravity", reviewerConnection).orElseThrow().sessionId());
        collaboration.consumeLaneGrant(project, grant.grantId(), reviewerParticipant,
                producer.intent().intentId(), producer.intent().version());

        Path producerWorktree = Path.of(new ProviderSessionBindingService()
                .find(new ProjectApplicationService().locate(project), "codex", producerConnection)
                .orElseThrow().worktreePath());
        Files.writeString(producerWorktree.resolve("src/todo.py"), "changed\n");
        AgentResponse producerAfter = next.getNextAction(new AgentNextActionService.NextActionRequest(
                project, "codex", producerConnection));
        return new ReviewRun(producer.intent().intentId(), reviewerParticipant, producerAfter,
                producerBefore, String.valueOf(payload.get("intentId")), grant, reviewerAdmission);
    }

    /** Creates a minimal Git-backed Synesis project fixture. */
    private static Path initializeProject(Path project) throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src/producer.py"), "value = 1\n");
        Files.writeString(project.resolve("src/unrelated.py"), "value = 2\n");
        Files.writeString(project.resolve("src/todo.py"), "value = 1\n");
        Files.writeString(project.resolve("tests/reviewer.py"), "def test_review(): pass\n");
        Files.writeString(project.resolve("tests/todo_test.py"), "def test_todo(): pass\n");
        TestGit.run(project, "init");
        TestGit.run(project, "config", "user.name", "SYN-039 Test");
        TestGit.run(project, "config", "user.email", "syn039@example.test");
        TestGit.run(project, "add", ".");
        TestGit.run(project, "commit", "-m", "baseline");
        new ProjectApplicationService().init(project);
        return project;
    }

    /** Installs the provider adapters required by the isolated fixture. */
    private static void installProviders() throws Exception {
        new ProviderManualService().install("codex");
        new ProviderManualService().install("antigravity");
    }

    /** Establishes and trusts the two provider sessions used by a run. */
    private static AgentSessionServiceFixture establishSessions(Path project, String prefix) throws Exception {
        AgentSessionServiceFixture sessions = new AgentSessionServiceFixture(project, prefix);
        sessions.ensure("codex", prefix + "-producer");
        sessions.ensure("antigravity", prefix + "-reviewer");
        if (prefix.equals("three")) {
            sessions.ensure("codex", prefix + "-unrelated");
        }
        return sessions;
    }

    /** Casts one provider result map with a test failure instead of a late null dereference. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, String.valueOf(value));
        return (Map<String, Object>) value;
    }

    /** Captures the semantic evidence needed to compare the two admission orders. */
    private record ReviewRun(UUID producerIntentId, String reviewerParticipant,
            AgentResponse producerAfterGrant, AgentResponse producerBeforeMutation,
            String reviewRequestIntentId, LaneGrant grant, AgentResponse reviewerAdmission) {
        /** Returns the semantic direction independent of generated IDs. */
        private String semanticDirection() {
            return reviewerAdmission.nextAction().name() + ":" + grant.targetIntentId().equals(producerIntentId())
                    + ":" + grant.targetParticipant().equals(reviewerParticipant)
                    + ":" + producerAfterGrant.reason().name() + ":" + producerAfterGrant.nextAction().name();
        }
    }

    /** Small fixture adapter for session setup and trust. */
    private static final class AgentSessionServiceFixture {
        private final Path project;
        private final String prefix;

        private AgentSessionServiceFixture(Path project, String prefix) {
            this.project = project;
            this.prefix = prefix;
        }

        private void ensure(String provider, String connection) throws Exception {
            AgentSessionService service = new AgentSessionService();
            service.ensureSession(new AgentSessionService.SessionResolutionRequest(
                    project, provider, connection, null, false));
            var location = new ProjectApplicationService().locate(project);
            var binding = new ProviderSessionBindingService().find(location, provider, connection).orElseThrow();
            new ProviderSessionBindingService().verifyWorkspaceTrust(location, provider,
                    binding.sessionId(), Path.of(binding.worktreePath()));
            assertTrue(connection.startsWith(prefix), connection);
        }
    }
}
