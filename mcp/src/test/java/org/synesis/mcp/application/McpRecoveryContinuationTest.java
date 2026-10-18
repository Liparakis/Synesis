package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;

/**
 * Proves blind discovery and execution of successor authority through the ten-tool MCP surface.
 */
@SuppressWarnings("unchecked")
final class McpRecoveryContinuationTest {

    @Test
    void blindSuccessorDiscoversExecutesAndReplaysContinuation(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("continuation-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "continuation-owner");
        ensureAndTrust(sessions, location, project, "continuation-successor");
        ensureAndTrust(sessions, location, project, "continuation-racer");

        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "continuation-owner").orElseThrow();
        var successorBinding = bindings.find(location, "codex", "continuation-successor").orElseThrow();
        var racerBinding = bindings.find(location, "codex", "continuation-racer").orElseThrow();
        Path ownerWorktree = Path.of(ownerBinding.worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/partial.txt"), "durable partial output\n");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "continuation-owner", "Implement the durable feature", "Publish and validate the feature", List.of(ResourceSelector.pathExact("src/partial.txt")));
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, ownerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var intentService = new org.synesis.coordination.application.WorkIntentService(store, identity);
        intentService.suspend(claim.intent().participant());
        intentService.holdRecovery(claim.intent().participant(), snapshot.root().toString() + "#" + snapshot.contentHash());

        McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex", "continuation-successor");
        McpProtocolHandler racer = new McpProtocolHandler(sessions, project, "codex", "continuation-racer");
        Map<String, Object> staleArguments = continuationArguments(racer.handleMessage(toolCall("get_next_action", "{}")));

        Map<String, Object> discovered = continuationArguments(successor.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("continuation", discovered.get("kind"));
        Map<String, Object> discoveredPayload = (Map<String, Object>) discovered.get("payload");
        assertEquals(claim.intent().intentId().toString(), discoveredPayload.get("intentId"));
        assertFalse(discoveredPayload.containsKey("grantId"));

        Map<String, Object> continued = innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(discovered))));
        assertEquals("completed", continued.get("status"), continued.toString());
        Map<String, Object> continuationResult = (Map<String, Object>) continued.get("result");
        assertEquals("CONTINUED", continuationResult.get("status"), continued.toString());
        assertTrue((Boolean) continuationResult.get("callerOwnsSuccessor"));
        assertEquals(WorkspaceCollaborationService.participantHandle(successorBinding.sessionId()), continuationResult.get("successorParticipant"));

        PredictionEventStore afterContinuation = store(project);
        var successorIntent = afterContinuation.collaborationProjection().intent(UUID.fromString(String.valueOf(continuationResult.get("successorIntentId")))).orElseThrow();
        assertEquals(claim.intent().taskId(), successorIntent.taskId());
        assertEquals(claim.intent().workGroupId(), successorIntent.workGroupId());
        assertNotEquals(claim.intent().participant(), successorIntent.participant());
        assertEquals(Participant.State.DETACHED, afterContinuation.collaborationProjection().participantState(claim.intent().participant()).orElseThrow());
        assertEquals(1, afterContinuation.events().stream().filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).count());
        assertTrue(Files.exists(Path.of(successorBinding.worktreePath()).resolve("src/partial.txt")));

        Files.writeString(project.resolve("README.md"), "# control advanced after continuation\n");
        git(project, "add", "README.md");
        git(project, "commit", "-m", "advance control after successor acquisition");
        // The successor is moved onto the new control head like any session holding uncommitted
        // claimed work: it stays the same session, keeps the work it recovered, and now sees
        // what the control branch gained. Without that a successor could never read or test
        // against work a peer integrated after the continuation.
        var refreshedSuccessor = bindings.ensure(location, "codex", "continuation-successor").binding();
        assertEquals(successorBinding.sessionId(), refreshedSuccessor.sessionId());
        Path refreshedWorktree = Path.of(refreshedSuccessor.worktreePath());
        assertNotEquals(successorBinding.worktreePath(), refreshedSuccessor.worktreePath());
        assertEquals("durable partial output\n", Files.readString(refreshedWorktree.resolve("src/partial.txt")), "recovered work must be carried over unchanged");
        assertEquals("# control advanced after continuation\n", Files.readString(refreshedWorktree.resolve("README.md")));
        assertEquals("durable partial output\n", Files.readString(Path.of(successorBinding.worktreePath()).resolve("src/partial.txt")), "the worktree it left is not touched");

        Map<String, Object> completionProjection = innerResult(successor.handleMessage(toolCall("get_next_action", "{\"completionRequested\":true}")));
        assertEquals("finish_lane", completionProjection.get("nextAction"), completionProjection.toString());

        Map<String, Object> replay = innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(discovered))));
        Map<String, Object> replayResult = (Map<String, Object>) replay.get("result");
        assertEquals("CONTINUED", replayResult.get("status"));
        assertTrue((Boolean) replayResult.get("callerOwnsSuccessor"));

        Map<String, Object> stale = innerResult(racer.handleMessage(toolCall("request_coordination", ProviderJson.write(staleArguments))));
        Map<String, Object> staleResult = (Map<String, Object>) stale.get("result");
        assertEquals("ALREADY_COMMITTED", staleResult.get("status"), stale.toString());
        assertFalse((Boolean) staleResult.get("callerOwnsSuccessor"));
        assertEquals(successorIntent.intentId().toString(), staleResult.get("successorIntentId"));
        assertEquals(1, store(project).events().stream().filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED).count());

        var staleOwnerReplay = collaboration.continueLane(project, "codex", "continuation-owner", null, claim.intent().intentId(), claim.intent().version());
        assertEquals("ALREADY_COMMITTED", staleOwnerReplay.status());
        assertFalse(staleOwnerReplay.callerOwnsSuccessor());
        assertEquals(successorIntent.intentId(), staleOwnerReplay.successorIntentId());

        ensureAndTrust(sessions, location, project, "continuation-owner-returned");
        var returned = collaboration.announce(project,
                "codex",
                "continuation-owner-returned",
                "Try to reclaim the feature",
                "Must not replace the current successor",
                List.of(ResourceSelector.pathExact("src/partial.txt")));
        assertFalse(returned.acquired());
        assertTrue(returned.conflicts().stream().anyMatch(conflict -> conflict.participant().equals(successorIntent.participant())));
        assertNotEquals(ownerBinding.sessionId(), successorBinding.sessionId());
        assertNotEquals(successorBinding.sessionId(), racerBinding.sessionId());
    }

    @Test
    void successorKeepsItsRecoveredWorktreeWhenTheControlBranchChangedAFileItClaims(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("continuation-conflict-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "conflict-owner");
        ensureAndTrust(sessions, location, project, "conflict-successor");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var ownerBinding = bindings.find(location, "codex", "conflict-owner").orElseThrow();
        var successorBinding = bindings.find(location, "codex", "conflict-successor").orElseThrow();
        Path ownerWorktree = Path.of(ownerBinding.worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/partial.txt"), "durable partial output\n");

        var claim = new WorkspaceCollaborationService().announce(project, "codex", "conflict-owner", "Implement the durable feature", "Publish and validate the feature",
                List.of(ResourceSelector.pathExact("src/partial.txt")));
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, ownerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var intentService = new org.synesis.coordination.application.WorkIntentService(store(project), identity);
        intentService.suspend(claim.intent().participant());
        intentService.holdRecovery(claim.intent().participant(), snapshot.root().toString() + "#" + snapshot.contentHash());

        McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex", "conflict-successor");
        Map<String, Object> discovered = continuationArguments(successor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> continued = (Map<String, Object>) innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(discovered)))).get("result");
        assertEquals("CONTINUED", continued.get("status"), continued.toString());

        // The control branch now holds a different version of the very file the successor
        // recovered. Carrying the recovered copy onto that head would silently overwrite it, so
        // the refresh declines and the successor keeps the worktree it was given.
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/partial.txt"), "another version on the control branch\n");
        git(project, "add", "src/partial.txt");
        git(project, "commit", "-m", "control changes the claimed file");

        var retained = bindings.ensure(location, "codex", "conflict-successor").binding();
        assertEquals(successorBinding.sessionId(), retained.sessionId());
        assertEquals(successorBinding.worktreePath(), retained.worktreePath());
        assertEquals("durable partial output\n", Files.readString(Path.of(retained.worktreePath()).resolve("src/partial.txt")));
    }

    @Test
    void successorHoldsTheContinuedLaneWhenItNamesOrExtendsIt(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("continuation-extend-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "extend-owner");
        ensureAndTrust(sessions, location, project, "extend-successor");
        var ownerBinding = new ProviderSessionBindingService().find(location, "codex", "extend-owner").orElseThrow();
        List<ResourceSelector> held = List.of(ResourceSelector.pathExact("src/partial.txt"));
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claim = collaboration.announce(project, "codex", "extend-owner", "Implement the durable feature", "Publish and validate the feature", held);
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, ownerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var intentService = new org.synesis.coordination.application.WorkIntentService(store(project), identity);
        intentService.suspend(claim.intent().participant());
        intentService.holdRecovery(claim.intent().participant(), snapshot.root().toString() + "#" + snapshot.contentHash());

        McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex", "extend-successor");
        Map<String, Object> discovered = continuationArguments(successor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> continued = (Map<String, Object>) innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(discovered)))).get("result");
        assertEquals("CONTINUED", continued.get("status"), continued.toString());
        WorkIntent continuedIntent = store(project).collaborationProjection().activeIntents().stream()
                .filter(intent -> !intent.participant().equals(claim.intent().participant())).findFirst().orElseThrow();

        // The continued lane does not carry the identifier derived from the successor's session,
        // so the session has to be recognized as its holder by participant.
        var named = collaboration.announce(project, "codex", "extend-successor", null, null, held);
        assertTrue(named.acquired());
        assertEquals(continuedIntent.intentId(), named.intent().intentId());

        List<ResourceSelector> wider = List.of(ResourceSelector.pathExact("src/partial.txt"), ResourceSelector.pathExact("src/more.txt"));
        var refused = org.junit.jupiter.api.Assertions.assertThrows(WorkspaceCollaborationService.ScopeExtensionRequiredException.class,
                () -> collaboration.announce(project, "codex", "extend-successor", null, null, wider));
        assertEquals(continuedIntent.intentId(), refused.currentIntent().intentId());
        var stale = org.junit.jupiter.api.Assertions.assertThrows(WorkspaceCollaborationService.ScopeExtensionRequiredException.class,
                () -> collaboration.announce(project, "codex", "extend-successor", null, null, wider, null, null, null, null,
                        continuedIntent.intentId(), continuedIntent.version() - 1));
        assertEquals(continuedIntent.version(), stale.currentIntent().version());

        var extended = collaboration.announce(project, "codex", "extend-successor", null, null, wider, null, null, null, null,
                continuedIntent.intentId(), continuedIntent.version());
        assertTrue(extended.acquired(), extended.toString());
        assertEquals(continuedIntent.intentId(), extended.intent().intentId());
        assertTrue(extended.intent().selectors().containsAll(wider));

        collaboration.release(project, "codex", "extend-successor");
        assertTrue(store(project).collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.intentId().equals(continuedIntent.intentId())),
                "a continued lane is released like an announced one");
    }

    @Test
    void reviewerSuccessorConsumesMigratedProducerGrantThroughProjectedMcpAction(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("reviewer-continuation-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "reviewer-continuation-producer");
        ensureAndTrust(sessions, location, project, "reviewer-continuation-reviewer");
        ensureAndTrust(sessions, location, project, "reviewer-continuation-producer-successor");
        ensureAndTrust(sessions, location, project, "reviewer-continuation-reviewer-successor");

        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var producerBinding = bindings.find(location, "codex", "reviewer-continuation-producer").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "reviewer-continuation-reviewer").orElseThrow();
        Path producerWorktree = Path.of(producerBinding.worktreePath());
        Files.createDirectories(producerWorktree.resolve("src"));
        Files.writeString(producerWorktree.resolve("src/partial.txt"), "partial output\n");

        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var producerClaim = collaboration.announce(project, "codex", "reviewer-continuation-producer", "Implement the feature", "Publish and review the feature", List.of(ResourceSelector.pathExact("src/partial.txt")));
        var reviewerClaim = collaboration.announce(project,
                "codex",
                "reviewer-continuation-reviewer",
                "Review the feature",
                "Independently review the feature",
                List.of(ResourceSelector.pathExact("review-report.md")),
                producerClaim.intent().workGroupId(),
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/partial.txt")));

        McpProtocolHandler producer = new McpProtocolHandler(sessions, project, "codex", "reviewer-continuation-producer");
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "reviewer-continuation-reviewer");
        String reviewRequest = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + producerClaim.intent().workGroupId() + "\"," + "\"intentId\":\"" + producerClaim.intent().intentId() + "\","
                        + "\"proposal\":\"Review the immutable feature snapshot\"}}"));
        assertEquals("completed", innerResult(reviewRequest).get("status"), reviewRequest);
        Map<String, Object> reviewRequestResult = (Map<String, Object>) innerResult(reviewRequest).get("result");
        String reviewRequestId = String.valueOf(((Map<String, Object>) reviewRequestResult.get("request")).get("requestId"));
        collaboration.respond(project, "codex", "reviewer-continuation-producer", UUID.fromString(reviewRequestId), CoordinationRequest.Status.ACCEPTED, "admitted");

        RecoverySnapshotService.Snapshot producerSnapshot = new RecoverySnapshotService().materialize(location, producerBinding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var intentService = new org.synesis.coordination.application.WorkIntentService(store(project), identity);
        intentService.suspend(producerClaim.intent().participant());
        String producerReference = producerSnapshot.root() + "#" + producerSnapshot.contentHash();
        intentService.holdRecovery(producerClaim.intent().participant(), producerReference);

        McpProtocolHandler producerSuccessor = new McpProtocolHandler(sessions, project, "codex", "reviewer-continuation-producer-successor");
        Map<String, Object> producerContinuation = continuationArguments(producerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> producerContinuationResult = (Map<String, Object>) innerResult(producerSuccessor.handleMessage(toolCall("request_coordination", ProviderJson.write(producerContinuation)))).get("result");
        assertEquals("CONTINUED", producerContinuationResult.get("status"), producerContinuationResult.toString());
        UUID producerSuccessorIntentId = UUID.fromString(String.valueOf(producerContinuationResult.get("successorIntentId")));

        RecoverySnapshotService.Snapshot reviewerSnapshot = new RecoverySnapshotService().materialize(location, reviewerBinding.sessionId());
        intentService.suspend(reviewerClaim.intent().participant());
        String reviewerReference = reviewerSnapshot.root() + "#" + reviewerSnapshot.contentHash();
        intentService.holdRecovery(reviewerClaim.intent().participant(), reviewerReference);

        McpProtocolHandler reviewerSuccessor = new McpProtocolHandler(sessions, project, "codex", "reviewer-continuation-reviewer-successor");
        Map<String, Object> reviewerContinuation = continuationArguments(reviewerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> reviewerContinuationResult = (Map<String, Object>) innerResult(reviewerSuccessor.handleMessage(toolCall("request_coordination", ProviderJson.write(reviewerContinuation)))).get("result");
        assertEquals("CONTINUED", reviewerContinuationResult.get("status"), reviewerContinuationResult.toString());

        Map<String, Object> reviewerNext = innerResult(reviewerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> reviewerNextResult = (Map<String, Object>) reviewerNext.get("result");
        Map<String, Object> projectedPayload = (Map<String, Object>) reviewerNextResult.get("nextProtocolPayload");
        assertEquals("request_coordination", reviewerNext.get("nextAction"), reviewerNext.toString());
        assertEquals(producerSuccessorIntentId.toString(), projectedPayload.get("intentId"), reviewerNext.toString());
        assertEquals(producerSuccessorIntentId.toString(), projectedPayload.get("reviewedIntentId"), reviewerNext.toString());

        Map<String, Object> joinArguments = (Map<String, Object>) ((Map<String, Object>) reviewerNextResult.get("workflow")).get("arguments");
        Map<String, Object> consumed = innerResult(reviewerSuccessor.handleMessage(toolCall("request_coordination", ProviderJson.write(joinArguments))));
        assertEquals("completed", consumed.get("status"), consumed.toString());
        UUID grantId = UUID.fromString(String.valueOf(projectedPayload.get("grantId")));
        assertTrue(store(project).workGroupProjection().grantConsumed(grantId), consumed.toString());
    }

    @Test
    void reviewerSuccessorDiscoversConsumedSnapshotBoundGrantAfterRecovery(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("snapshot-reviewer-continuation-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");

        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "snapshot-review-producer");
        ensureAndTrust(sessions, location, project, "snapshot-review-reviewer");
        ensureAndTrust(sessions, location, project, "snapshot-review-successor");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var producerBinding = bindings.find(location, "codex", "snapshot-review-producer").orElseThrow();
        var reviewerBinding = bindings.find(location, "codex", "snapshot-review-reviewer").orElseThrow();

        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(project, groupId, "Build a feature", "Review the exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        ResourceSelector source = ResourceSelector.pathExact("src/partial.txt");
        var producerClaim = collaboration.announce(project, "codex", "snapshot-review-producer", "Implement a feature", "Publish the feature candidate", List.of(source), groupId);
        var reviewerClaim = collaboration.announce(project,
                "codex",
                "snapshot-review-reviewer",
                "Review the feature",
                "Independently review the exact snapshot",
                List.of(ResourceSelector.pathExact("review-report.md")),
                groupId,
                WorkIntent.Role.REVIEWER,
                List.of(source));

        String snapshotId = "snap_reviewer_recovery";
        String providerSession = producerBinding.sessionId();
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                producerClaim.intent().intentId(),
                producerClaim.intent().authorityLineageId(),
                producerClaim.intent().participant(),
                providerSession,
                producerClaim.intent().version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/partial.txt"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "manifest-" + snapshotId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(producerClaim.intent().taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                providerSession,
                producerClaim.intent().baseCommit(),
                "candidate-commit",
                List.of("src/partial.txt"),
                List.of(),
                "Publish candidate",
                provenance,
                true);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        store(project).append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);

        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "snapshot-review-reviewer");
        McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex", "snapshot-review-successor");
        String request = reviewer.handleMessage(toolCall("request_coordination",
                "{\"kind\":\"work_group_join\",\"payload\":{" + "\"workGroupId\":\"" + groupId + "\"," + "\"intentId\":\"" + producerClaim.intent().intentId() + "\"," + "\"snapshotId\":\"" + snapshotId + "\","
                        + "\"proposal\":\"Review this exact immutable snapshot\"}}"));
        assertEquals("completed", innerResult(request).get("status"), request);
        Map<String, Object> requestResult = (Map<String, Object>) innerResult(request).get("result");
        String requestId = String.valueOf(((Map<String, Object>) requestResult.get("request")).get("requestId"));
        collaboration.respond(project, "codex", "snapshot-review-producer", UUID.fromString(requestId), CoordinationRequest.Status.ACCEPTED, "admitted");

        Map<String, Object> reviewerAdmission = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("request_coordination", reviewerAdmission.get("nextAction"), reviewerAdmission.toString());
        Map<String, Object> admissionArguments = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) reviewerAdmission.get("result")).get("workflow")).get("arguments");
        Map<String, Object> consumed = innerResult(reviewer.handleMessage(toolCall("request_coordination", ProviderJson.write(admissionArguments))));
        assertEquals("completed", consumed.get("status"), consumed.toString());
        var originalGrant = store(project).workGroupProjection().grants().stream().filter(grant -> snapshotId.equals(grant.snapshotId())).findFirst().orElseThrow();
        assertTrue(store(project).workGroupProjection().grantConsumed(originalGrant.grantId()));

        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, reviewerBinding.sessionId());
        var lifecycle = new org.synesis.coordination.application.WorkIntentService(store(project), identity);
        lifecycle.suspend(reviewerClaim.intent().participant());
        lifecycle.holdRecovery(reviewerClaim.intent().participant(), recovery.root() + "#" + recovery.contentHash());

        Map<String, Object> continuation = continuationArguments(successor.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("continuation", continuation.get("kind"), continuation.toString());
        assertTrue(continuation.get("payload") instanceof Map<?, ?>, continuation.toString());
        Map<String, Object> continuationResult = (Map<String, Object>) innerResult(successor.handleMessage(toolCall("request_coordination", ProviderJson.write(continuation)))).get("result");
        assertEquals("CONTINUED", continuationResult.get("status"), continuationResult.toString());
        assertNotEquals(reviewerClaim.intent().participant(), continuationResult.get("successorParticipant"));

        var afterContinuation = store(project);
        assertTrue(afterContinuation.workGroupProjection().grantSuperseded(originalGrant.grantId()));
        var replacement = afterContinuation.workGroupProjection().grants().stream().filter(grant -> snapshotId.equals(grant.snapshotId()))
                .filter(grant -> grant.targetParticipant().equals(String.valueOf(continuationResult.get("successorParticipant")))).findFirst().orElseThrow();
        assertTrue(afterContinuation.workGroupProjection().grantAvailable(replacement.grantId()));

        Map<String, Object> reviewAction = innerResult(successor.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("request_coordination", reviewAction.get("nextAction"), reviewAction.toString());
        Map<String, Object> reviewArguments = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) reviewAction.get("result")).get("workflow")).get("arguments");
        Map<String, Object> reviewPayload = (Map<String, Object>) reviewArguments.get("payload");
        assertEquals(replacement.grantId().toString(), reviewPayload.get("grantId"));
        assertEquals(snapshotId, reviewPayload.get("snapshotId"));
    }

    /**
     * Real run 4: a fresh session in a mandatory-review project saw only "choose a part that no
     * participant claims" and never learned it could review the snapshot awaiting review.
     */
    @Test
    @SuppressWarnings("unchecked")
    void freshSessionIsToldItMayReviewASnapshotAwaitingReview(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("fresh-reviewer-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "fresh-review-producer");
        ensureAndTrust(sessions, location, project, "fresh-review-newcomer");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var producerBinding = bindings.find(location, "codex", "fresh-review-producer").orElseThrow();

        UUID groupId = UUID.randomUUID();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.createWorkGroup(project, groupId, "Build a feature", "Review the exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var producerClaim = collaboration.announce(project, "codex", "fresh-review-producer", "Implement a feature", "Publish the feature candidate", List.of(ResourceSelector.pathExact("src/wrap.py")), groupId);
        String snapshotId = "snap_fresh_reviewer";
        SnapshotProvenance provenance = new SnapshotProvenance(groupId,
                producerClaim.intent().intentId(),
                producerClaim.intent().authorityLineageId(),
                producerClaim.intent().participant(),
                producerBinding.sessionId(),
                producerClaim.intent().version(),
                List.of(),
                List.of(),
                List.of("PATH_EXACT:src/wrap.py"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity-" + snapshotId,
                "manifest-" + snapshotId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(producerClaim.intent().taskId(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                producerBinding.sessionId(),
                producerClaim.intent().baseCommit(),
                "candidate-commit",
                List.of("src/wrap.py"),
                List.of(),
                "Publish candidate",
                provenance,
                true);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        store(project).append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);

        McpProtocolHandler newcomer = new McpProtocolHandler(sessions, project, "codex", "fresh-review-newcomer");
        Map<String, Object> ensured = innerResult(newcomer.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("ready", ensured.get("status"), ensured.toString());
        List<Map<String, Object>> offered = (List<Map<String, Object>>) ((Map<String, Object>) ensured.get("result")).get("reviewCandidates");
        assertEquals(1, offered.size(), ensured.toString());
        assertEquals(snapshotId, offered.getFirst().get("snapshotId"));
        assertEquals(List.of("src/wrap.py"), offered.getFirst().get("files"));
        assertEquals("ensure_session", ((Map<String, Object>) offered.getFirst().get("reviewerIntent")).get("tool"));

        Map<String, Object> next = innerResult(newcomer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("coordination_intent_required", next.get("reason"), next.toString());
        String instruction = String.valueOf(((Map<String, Object>) next.get("result")).get("instruction"));
        assertTrue(instruction.contains("review a snapshot awaiting review: reviewCandidates[i].reviewerIntent"), instruction);
        assertTrue(instruction.contains("choose a part that no listed participant claims"), instruction);

        Map<String, Object> producerEnsured = innerResult(new McpProtocolHandler(sessions, project, "codex", "fresh-review-producer").handleMessage(toolCall("ensure_session", "{}")));
        assertFalse(((Map<String, Object>) producerEnsured.get("result")).containsKey("reviewCandidates"), producerEnsured.toString());
    }

    /**
     * Real run "Two homes, one project", defect 3: an author published a snapshot for mandatory
     * review and its agent left (RECOVERY_HELD). A fresh agent was offered that snapshot for review,
     * claimed a reviewer lane and asked to join; the request was addressed to the held author, which
     * cannot answer, and a session holding a lane may not continue another. The fresh agent is now
     * pointed at continuing the author instead, and as its successor admits the next reviewer.
     */
    @Test
    void freshSessionContinuesAHeldAuthorBeforeItsSnapshotIsOfferedForReview(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("held-author-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        ensureAndTrust(sessions, location, project, "held-author");
        ensureAndTrust(sessions, location, project, "fresh-one");
        ensureAndTrust(sessions, location, project, "fresh-two");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build slug", "Review the exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var author = collaboration.announce(project, "codex", "held-author", "Implement slug", "Publish slug", List.of(ResourceSelector.pathExact("slug.py")), groupId);
        publishForReview(project, location, "held-author", author.intent(), "snap_held_slug", "slug.py");
        holdForRecovery(project, location, "held-author", author.intent().participant());

        McpProtocolHandler first = new McpProtocolHandler(sessions, project, "codex", "fresh-one");
        Map<String, Object> firstEnsured = (Map<String, Object>) innerResult(first.handleMessage(toolCall("ensure_session", "{}"))).get("result");
        assertFalse(firstEnsured.containsKey("reviewCandidates"), "a held author cannot admit a reviewer: " + firstEnsured);
        List<Map<String, Object>> continuations = (List<Map<String, Object>>) firstEnsured.get("continuationCandidates");
        assertEquals(1, continuations.size(), firstEnsured.toString());
        assertEquals(author.intent().intentId().toString(), continuations.getFirst().get("sourceIntentId"));

        Map<String, Object> continuation = continuationArguments(first.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("continuation", continuation.get("kind"), continuation.toString());
        Map<String, Object> continued = (Map<String, Object>) innerResult(first.handleMessage(toolCall("request_coordination", ProviderJson.write(continuation)))).get("result");
        assertEquals("CONTINUED", continued.get("status"), continued.toString());
        String successorIntent = String.valueOf(continued.get("successorIntentId"));

        McpProtocolHandler second = new McpProtocolHandler(sessions, project, "codex", "fresh-two");
        Map<String, Object> secondEnsured = (Map<String, Object>) innerResult(second.handleMessage(toolCall("ensure_session", "{}"))).get("result");
        List<Map<String, Object>> offered = (List<Map<String, Object>>) secondEnsured.get("reviewCandidates");
        assertEquals("snap_held_slug", offered.getFirst().get("snapshotId"), secondEnsured.toString());
        assertFalse(secondEnsured.containsKey("continuationCandidates"), secondEnsured.toString());

        collaboration.announce(project, "codex", "fresh-two", "Review slug", "Independently review the exact snapshot", List.of(ResourceSelector.pathExact("review_slug.md")), groupId, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("slug.py")));
        String request = second.handleMessage(toolCall("request_coordination", "{\"kind\":\"work_group_join\",\"payload\":{\"workGroupId\":\"" + groupId + "\",\"intentId\":\"" + successorIntent
                + "\",\"snapshotId\":\"snap_held_slug\",\"proposal\":\"Review this exact immutable snapshot\"}}"));
        assertEquals("completed", innerResult(request).get("status"), request);

        Map<String, Object> admission = innerResult(first.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("respond_coordination", admission.get("nextAction"), admission.toString());
        Map<String, Object> admit = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) admission.get("result")).get("workflow")).get("arguments");
        assertEquals("completed", innerResult(first.handleMessage(toolCall("respond_coordination", ProviderJson.write(admit)))).get("status"));

        Map<String, Object> review = innerResult(second.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("request_coordination", review.get("nextAction"), review.toString());
        Map<String, Object> grantPayload = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) review.get("result")).get("workflow")).get("arguments")).get("payload");
        assertTrue(grantPayload.containsKey("grantId"), review.toString());
    }

    /**
     * Two authors held, as in the real run. Once one fresh agent continues the first author, a second
     * fresh agent is pointed at reviewing that author's snapshot, not at continuing the other author:
     * two successors and no reviewer would wait on each other until the run stalled.
     */
    @Test
    void secondFreshSessionReviewsTheContinuedAuthorRatherThanContinuingAnother(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("two-held-authors-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        for (String connection : List.of("author-slug", "author-wrap", "fresh-one", "fresh-two")) {
            ensureAndTrust(sessions, location, project, connection);
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build slug and wrap", "Review each exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var slug = collaboration.announce(project, "codex", "author-slug", "Implement slug", "Publish slug", List.of(ResourceSelector.pathExact("slug.py")), groupId);
        var wrap = collaboration.announce(project, "codex", "author-wrap", "Implement wrap", "Publish wrap", List.of(ResourceSelector.pathExact("wrap.py")), groupId);
        publishForReview(project, location, "author-slug", slug.intent(), "snap_slug", "slug.py");
        publishForReview(project, location, "author-wrap", wrap.intent(), "snap_wrap", "wrap.py");
        holdForRecovery(project, location, "author-slug", slug.intent().participant());
        holdForRecovery(project, location, "author-wrap", wrap.intent().participant());

        assertEquals("COMMITTED", collaboration.continueLane(project, "codex", "fresh-one", null, slug.intent().intentId(), slug.intent().version()).status());

        McpProtocolHandler second = new McpProtocolHandler(sessions, project, "codex", "fresh-two");
        Map<String, Object> ensured = (Map<String, Object>) innerResult(second.handleMessage(toolCall("ensure_session", "{}"))).get("result");
        assertEquals(List.of("snap_slug"), ((List<Map<String, Object>>) ensured.get("reviewCandidates")).stream().map(candidate -> candidate.get("snapshotId")).toList(), ensured.toString());
        assertFalse(ensured.containsKey("continuationCandidates"), ensured.toString());

        Map<String, Object> next = innerResult(second.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("coordination_intent_required", next.get("reason"), next.toString());
        assertTrue(String.valueOf(((Map<String, Object>) next.get("result")).get("instruction")).contains("review a snapshot awaiting review"), next.toString());
    }

    /**
     * The state the real run stalled in: a reviewer already waits for a held author to admit it. A
     * fresh agent is pointed at continuing that author before reviewing another live author's
     * snapshot, and as the successor admits the waiting reviewer.
     */
    @Test
    void freshSessionContinuesTheHeldAuthorAWaitingReviewerAskedFirst(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("waiting-reviewer-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        for (String connection : List.of("author-held", "author-live", "reviewer-waiting", "fresh")) {
            ensureAndTrust(sessions, location, project, connection);
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build slug and wrap", "Review each exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var held = collaboration.announce(project, "codex", "author-held", "Implement slug", "Publish slug", List.of(ResourceSelector.pathExact("slug.py")), groupId);
        var live = collaboration.announce(project, "codex", "author-live", "Implement wrap", "Publish wrap", List.of(ResourceSelector.pathExact("wrap.py")), groupId);
        publishForReview(project, location, "author-held", held.intent(), "snap_slug", "slug.py");
        publishForReview(project, location, "author-live", live.intent(), "snap_wrap", "wrap.py");
        collaboration.announce(project, "codex", "reviewer-waiting", "Review slug", "Independently review the exact snapshot", List.of(ResourceSelector.pathExact("review_slug.md")), groupId, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("slug.py")));
        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "reviewer-waiting");
        reviewer.handleMessage(toolCall("request_coordination", "{\"kind\":\"work_group_join\",\"payload\":{\"workGroupId\":\"" + groupId + "\",\"intentId\":\"" + held.intent().intentId()
                + "\",\"snapshotId\":\"snap_slug\",\"proposal\":\"Review this exact immutable snapshot\"}}"));
        holdForRecovery(project, location, "author-held", held.intent().participant());
        assertEquals("owner_response_pending", innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}"))).get("reason"));

        McpProtocolHandler fresh = new McpProtocolHandler(sessions, project, "codex", "fresh");
        Map<String, Object> ensured = (Map<String, Object>) innerResult(fresh.handleMessage(toolCall("ensure_session", "{}"))).get("result");
        assertFalse(ensured.containsKey("reviewCandidates"), ensured.toString());
        Map<String, Object> continuation = continuationArguments(fresh.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals(held.intent().intentId().toString(), ((Map<String, Object>) continuation.get("payload")).get("intentId"), continuation.toString());
        assertEquals("CONTINUED", ((Map<String, Object>) innerResult(fresh.handleMessage(toolCall("request_coordination", ProviderJson.write(continuation)))).get("result")).get("status"));

        Map<String, Object> admission = innerResult(fresh.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("respond_coordination", admission.get("nextAction"), admission.toString());
    }

    /**
     * Overnight run across two computers, defect 3: an author whose agent left was SUSPENDED before it
     * was held, and fresh agents were offered its snapshot for review while a continuation was waiting
     * for them. A suspended author cannot admit a reviewer either, so the fresh agent is pointed at the
     * held lane and not at a review that would wait on nobody.
     */
    @Test
    @SuppressWarnings("unchecked")
    void freshSessionIsNotLedToReviewASuspendedAuthorWhileAContinuationWaits(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("suspended-author-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        for (String connection : List.of("author-held", "author-suspended", "fresh")) {
            ensureAndTrust(sessions, location, project, connection);
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build slug and wrap", "Review each exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var held = collaboration.announce(project, "codex", "author-held", "Implement slug", "Publish slug", List.of(ResourceSelector.pathExact("slug.py")), groupId);
        var suspended = collaboration.announce(project, "codex", "author-suspended", "Implement wrap", "Publish wrap", List.of(ResourceSelector.pathExact("wrap.py")), groupId);
        publishForReview(project, location, "author-suspended", suspended.intent(), "snap_wrap", "wrap.py");
        holdForRecovery(project, location, "author-held", held.intent().participant());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        new org.synesis.coordination.application.WorkIntentService(store(project), identity).suspend(suspended.intent().participant());

        McpProtocolHandler fresh = new McpProtocolHandler(sessions, project, "codex", "fresh");
        Map<String, Object> ensured = (Map<String, Object>) innerResult(fresh.handleMessage(toolCall("ensure_session", "{}"))).get("result");
        assertFalse(ensured.containsKey("reviewCandidates"), "a suspended author cannot admit a reviewer: " + ensured);
        List<Map<String, Object>> continuations = (List<Map<String, Object>>) ensured.get("continuationCandidates");
        assertEquals(List.of(held.intent().intentId().toString()), continuations.stream().map(candidate -> candidate.get("sourceIntentId")).toList(), ensured.toString());
        Map<String, Object> continuation = continuationArguments(fresh.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals(held.intent().intentId().toString(), ((Map<String, Object>) continuation.get("payload")).get("intentId"), continuation.toString());
    }

    /**
     * Fourth real run on the ADR-0099 amendment: a reviewer lane's module was integrated through
     * another reviewer, while other modules of the same work group were still open. The lane was told
     * to wait for ever. It is now told that no open producer lane claims what it reviews, and that it
     * may cancel.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aReviewerWhoseModuleWasIntegratedElsewhereIsToldItMayCancel(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("moot-reviewer-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        for (String connection : List.of("producer-slug", "producer-cli", "reviewer")) {
            ensureAndTrust(sessions, location, project, connection);
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build slug and cli", "Review each exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        collaboration.announce(project, "codex", "producer-slug", "Implement slug", "Publish slug", List.of(ResourceSelector.pathExact("slug.py")), groupId);
        collaboration.announce(project, "codex", "producer-cli", "Implement cli", "Publish cli", List.of(ResourceSelector.pathExact("cli.py")), groupId);
        collaboration.announce(project, "codex", "reviewer", "Review slug", "Independently review slug", List.of(ResourceSelector.pathExact("review_slug.md")), groupId, WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("slug.py")));
        collaboration.release(project, "codex", "producer-slug");

        McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex", "reviewer");
        Map<String, Object> next = innerResult(reviewer.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("wait", next.get("nextAction"), next.toString());
        assertTrue(String.valueOf(((Map<String, Object>) next.get("result")).get("instruction")).contains("cancel_lane"), next.toString());

    }

    /**
     * Real run across a PC and a VM (2026-10-18, mp18-link): the author of count.py accepted one
     * reviewer's request for its only snapshot, and a second reviewer lane for count.py waited for
     * ever to be admitted. It is told the review went to another reviewer and that it may cancel.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aReviewerWhoseOnlyCandidateWentToAnotherReviewerIsToldItMayCancel(@TempDir Path temp) throws Exception {
        Path project = initializedProject(temp.resolve("losing-reviewer-project"));
        ProjectLocation location = new ProjectApplicationService().locate(project);
        McpProviderTestSupport.install(location, "codex");
        new ProviderManualService().install("codex");
        AgentSessionService sessions = new AgentSessionService();
        for (String connection : List.of("author-count", "reviewer-admitted", "reviewer-late")) {
            ensureAndTrust(sessions, location, project, connection);
        }
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        UUID groupId = UUID.randomUUID();
        collaboration.createWorkGroup(project, groupId, "Build count", "Review each exact candidate before integration", ReviewPolicy.mandatoryIndependent());
        var author = collaboration.announce(project, "codex", "author-count", "Implement count", "Publish count", List.of(ResourceSelector.pathExact("count.py")), groupId);
        for (String reviewer : List.of("reviewer-admitted", "reviewer-late")) {
            collaboration.announce(project, "codex", reviewer, "Review count", "Independently review count", List.of(ResourceSelector.pathExact(reviewer + ".md")), groupId, WorkIntent.Role.REVIEWER,
                    List.of(ResourceSelector.pathExact("count.py")));
        }
        publishForReview(project, location, "author-count", author.intent(), "snap_count", "count.py");
        McpProtocolHandler late = new McpProtocolHandler(sessions, project, "codex", "reviewer-late");
        Map<String, Object> before = innerResult(late.handleMessage(toolCall("get_next_action", "{}")));
        assertFalse(String.valueOf(before).contains("admitted another reviewer"), before.toString());

        CoordinationRequest request = collaboration.request(project, "codex", "reviewer-admitted", author.intent().intentId(), CoordinationRequest.Kind.REVIEW, "Review this exact immutable snapshot", "snap_count");
        collaboration.respond(project, "codex", "author-count", request.requestId(), CoordinationRequest.Status.ACCEPTED, "admitted");

        Map<String, Object> next = innerResult(late.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("wait", next.get("nextAction"), next.toString());
        String instruction = String.valueOf(((Map<String, Object>) next.get("result")).get("instruction"));
        assertTrue(instruction.contains("admitted another reviewer") && instruction.contains("cancel_lane"), next.toString());
    }

    private static void publishForReview(Path project, ProjectLocation location, String connection, WorkIntent intent, String snapshotId, String path) throws Exception {
        String session = new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow().sessionId();
        SnapshotProvenance provenance = new SnapshotProvenance(intent.workGroupId(), intent.intentId(), intent.authorityLineageId(), intent.participant(), session, intent.version(), List.of(), List.of(),
                List.of("PATH_EXACT:" + path), "refs/synesis/snapshots/" + snapshotId, "integrity-" + snapshotId, "manifest-" + snapshotId);
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(intent.taskId(), snapshotId, "node", "supervisor", "worker", session, intent.baseCommit(), "candidate-commit", List.of(path), List.of(),
                "Publish candidate", provenance, true);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        store(project).append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, identity.nodeId(), snapshot.encode(), identity);
    }

    private static void holdForRecovery(Path project, ProjectLocation location, String connection, String participant) throws Exception {
        String session = new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow().sessionId();
        RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService().materialize(location, session);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var lifecycle = new org.synesis.coordination.application.WorkIntentService(store(project), identity);
        lifecycle.suspend(participant);
        lifecycle.holdRecovery(participant, recovery.root() + "#" + recovery.contentHash());
    }

    private static Path initializedProject(Path project) throws Exception {
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "Continuation Test");
        git(project, "config", "user.email", "continuation@example.test");
        Files.writeString(project.resolve("README.md"), "# continuation\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");
        new ProjectApplicationService().init(project);
        return project;
    }

    private static void ensureAndTrust(AgentSessionService sessions, ProjectLocation location, Path project, String connection) throws Exception {
        sessions.ensureSession(new SessionResolutionRequest(project, "codex", connection, null, false));
        var binding = new ProviderSessionBindingService().find(location, "codex", connection).orElseThrow();
        new ProviderSessionBindingService().verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
    }

    private static PredictionEventStore store(Path project) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(project);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private static Map<String, Object> continuationArguments(String response) {
        Map<String, Object> outer = innerResult(response);
        Map<String, Object> result = (Map<String, Object>) outer.get("result");
        Map<String, Object> workflow = (Map<String, Object>) result.get("workflow");
        return (Map<String, Object>) workflow.get("arguments");
    }

    private static Map<String, Object> innerResult(String response) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(response);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst().get("text"));
    }

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }
}
