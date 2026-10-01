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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
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
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService()
        .locate(project);
    McpProviderTestSupport.install(location, "codex");
    new ProviderManualService().install("codex");

    AgentSessionService sessions = new AgentSessionService();
    ensureAndTrust(sessions, location, project, "continuation-owner");
    ensureAndTrust(sessions, location, project, "continuation-successor");
    ensureAndTrust(sessions, location, project, "continuation-racer");

    ProviderSessionBindingService bindings = new ProviderSessionBindingService();
    var ownerBinding = bindings.find(location, "codex", "continuation-owner")
        .orElseThrow();
    var successorBinding = bindings.find(location, "codex", "continuation-successor")
        .orElseThrow();
    var racerBinding = bindings.find(location, "codex", "continuation-racer")
        .orElseThrow();
    Path ownerWorktree = Path.of(ownerBinding.worktreePath());
    Files.createDirectories(ownerWorktree.resolve("src"));
    Files.writeString(ownerWorktree.resolve("src/partial.txt"), "durable partial output\n");

    WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
    var claim = collaboration.announce(project, "codex", "continuation-owner",
        "Implement the durable feature", "Publish and validate the feature",
        List.of(ResourceSelector.pathExact("src/partial.txt")));
    RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location,
        ownerBinding.sessionId());
    var identity = new IdentityBootstrap(location.profile()
        .resolve("link"))
        .loadOrCreate()
        .identity();
    PredictionEventStore store = new PredictionEventStore(location.root()
        .resolve(".synesis/coordination"), location.projectId());
    var intentService = new org.synesis.coordination.application.WorkIntentService(store, identity);
    intentService.suspend(claim.intent().participant());
    intentService.holdRecovery(claim.intent().participant(), snapshot.root()
        .toString() + "#" + snapshot.contentHash());

    McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex",
        "continuation-successor");
    McpProtocolHandler racer = new McpProtocolHandler(sessions, project, "codex",
        "continuation-racer");
    Map<String, Object> staleArguments = continuationArguments(racer.handleMessage(
        toolCall("get_next_action", "{}")));

    Map<String, Object> discovered = continuationArguments(successor.handleMessage(
        toolCall("get_next_action", "{}")));
    assertEquals("continuation", discovered.get("kind"));
    Map<String, Object> discoveredPayload = (Map<String, Object>) discovered.get("payload");
    assertEquals(claim.intent().intentId().toString(), discoveredPayload.get("intentId"));
    assertFalse(discoveredPayload.containsKey("grantId"));

    Map<String, Object> continued = innerResult(successor.handleMessage(
        toolCall("request_coordination", ProviderJson.write(discovered))));
    assertEquals("completed", continued.get("status"), continued.toString());
    Map<String, Object> continuationResult = (Map<String, Object>) continued.get("result");
    assertEquals("CONTINUED", continuationResult.get("status"), continued.toString());
    assertTrue((Boolean) continuationResult.get("callerOwnsSuccessor"));
    assertEquals(WorkspaceCollaborationService.participantHandle(successorBinding.sessionId()),
        continuationResult.get("successorParticipant"));

    PredictionEventStore afterContinuation = store(project);
    var successorIntent = afterContinuation.collaborationProjection()
        .intent(UUID.fromString(String.valueOf(continuationResult.get("successorIntentId"))))
        .orElseThrow();
    assertEquals(claim.intent().taskId(), successorIntent.taskId());
    assertEquals(claim.intent().workGroupId(), successorIntent.workGroupId());
    assertNotEquals(claim.intent().participant(), successorIntent.participant());
    assertEquals(Participant.State.DETACHED,
        afterContinuation.collaborationProjection()
            .participantState(claim.intent().participant())
            .orElseThrow());
    assertEquals(1, afterContinuation.events()
        .stream()
        .filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED)
        .count());
    assertTrue(Files.exists(Path.of(successorBinding.worktreePath())
        .resolve("src/partial.txt")));

    Files.writeString(project.resolve("README.md"), "# control advanced after continuation\n");
    git(project, "add", "README.md");
    git(project, "commit", "-m", "advance control after successor acquisition");
    var refreshedSuccessor = bindings.ensure(location, "codex", "continuation-successor")
        .binding();
    assertEquals(successorBinding.sessionId(), refreshedSuccessor.sessionId());
    assertEquals(successorBinding.worktreePath(), refreshedSuccessor.worktreePath());

    Map<String, Object> completionProjection = innerResult(successor.handleMessage(
        toolCall("get_next_action", "{\"completionRequested\":true}")));
    assertEquals("finish_lane", completionProjection.get("nextAction"),
        completionProjection.toString());

    Map<String, Object> replay = innerResult(successor.handleMessage(
        toolCall("request_coordination", ProviderJson.write(discovered))));
    Map<String, Object> replayResult = (Map<String, Object>) replay.get("result");
    assertEquals("CONTINUED", replayResult.get("status"));
    assertTrue((Boolean) replayResult.get("callerOwnsSuccessor"));

    Map<String, Object> stale = innerResult(racer.handleMessage(
        toolCall("request_coordination", ProviderJson.write(staleArguments))));
    Map<String, Object> staleResult = (Map<String, Object>) stale.get("result");
    assertEquals("ALREADY_COMMITTED", staleResult.get("status"), stale.toString());
    assertFalse((Boolean) staleResult.get("callerOwnsSuccessor"));
    assertEquals(successorIntent.intentId().toString(), staleResult.get("successorIntentId"));
    assertEquals(1, store(project).events()
        .stream()
        .filter(event -> event.type() == PredictionEventType.LANE_CONTINUATION_ACCEPTED)
        .count());

    var staleOwnerReplay = collaboration.continueLane(project, "codex", "continuation-owner",
        null, claim.intent().intentId(), claim.intent().version());
    assertEquals("ALREADY_COMMITTED", staleOwnerReplay.status());
    assertFalse(staleOwnerReplay.callerOwnsSuccessor());
    assertEquals(successorIntent.intentId(), staleOwnerReplay.successorIntentId());

    ensureAndTrust(sessions, location, project, "continuation-owner-returned");
    var returned = collaboration.announce(project, "codex", "continuation-owner-returned",
        "Try to reclaim the feature", "Must not replace the current successor",
        List.of(ResourceSelector.pathExact("src/partial.txt")));
    assertFalse(returned.acquired());
    assertTrue(returned.conflicts()
        .stream()
        .anyMatch(conflict -> conflict.participant().equals(successorIntent.participant())));
    assertNotEquals(ownerBinding.sessionId(), successorBinding.sessionId());
    assertNotEquals(successorBinding.sessionId(), racerBinding.sessionId());
  }

  @Test
  void reviewerSuccessorConsumesMigratedProducerGrantThroughProjectedMcpAction(
      @TempDir Path temp) throws Exception {
    Path project = initializedProject(temp.resolve("reviewer-continuation-project"));
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService()
        .locate(project);
    McpProviderTestSupport.install(location, "codex");
    new ProviderManualService().install("codex");

    AgentSessionService sessions = new AgentSessionService();
    ensureAndTrust(sessions, location, project, "reviewer-continuation-producer");
    ensureAndTrust(sessions, location, project, "reviewer-continuation-reviewer");
    ensureAndTrust(sessions, location, project, "reviewer-continuation-producer-successor");
    ensureAndTrust(sessions, location, project, "reviewer-continuation-reviewer-successor");

    ProviderSessionBindingService bindings = new ProviderSessionBindingService();
    var producerBinding = bindings.find(location, "codex", "reviewer-continuation-producer")
        .orElseThrow();
    var reviewerBinding = bindings.find(location, "codex", "reviewer-continuation-reviewer")
        .orElseThrow();
    Path producerWorktree = Path.of(producerBinding.worktreePath());
    Files.createDirectories(producerWorktree.resolve("src"));
    Files.writeString(producerWorktree.resolve("src/partial.txt"), "partial output\n");

    WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
    var producerClaim = collaboration.announce(project, "codex",
        "reviewer-continuation-producer", "Implement the feature",
        "Publish and review the feature", List.of(ResourceSelector.pathExact("src/partial.txt")));
    var reviewerClaim = collaboration.announce(project, "codex",
        "reviewer-continuation-reviewer", "Review the feature",
        "Independently review the feature", List.of(ResourceSelector.pathExact("review-report.md")),
        producerClaim.intent().workGroupId(), WorkIntent.Role.REVIEWER,
        List.of(ResourceSelector.pathExact("src/partial.txt")));

    McpProtocolHandler producer = new McpProtocolHandler(sessions, project, "codex",
        "reviewer-continuation-producer");
    McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex",
        "reviewer-continuation-reviewer");
    String reviewRequest = reviewer.handleMessage(toolCall("request_coordination",
        "{\"kind\":\"work_group_join\",\"payload\":{"
            + "\"workGroupId\":\"" + producerClaim.intent().workGroupId() + "\","
            + "\"intentId\":\"" + producerClaim.intent().intentId() + "\","
            + "\"proposal\":\"Review the immutable feature snapshot\"}}"));
    assertEquals("completed", innerResult(reviewRequest).get("status"), reviewRequest);
    Map<String, Object> reviewRequestResult = (Map<String, Object>) innerResult(reviewRequest)
        .get("result");
    String reviewRequestId = String.valueOf(((Map<String, Object>) reviewRequestResult.get(
        "request")).get("requestId"));
    collaboration.respond(project, "codex", "reviewer-continuation-producer",
        UUID.fromString(reviewRequestId), CoordinationRequest.Status.ACCEPTED, "admitted");

    RecoverySnapshotService.Snapshot producerSnapshot = new RecoverySnapshotService().materialize(
        location, producerBinding.sessionId());
    var identity = new IdentityBootstrap(location.profile()
        .resolve("link")).loadOrCreate()
        .identity();
    var intentService = new org.synesis.coordination.application.WorkIntentService(
        store(project), identity);
    intentService.suspend(producerClaim.intent().participant());
    String producerReference = producerSnapshot.root() + "#" + producerSnapshot.contentHash();
    intentService.holdRecovery(producerClaim.intent().participant(), producerReference);

    McpProtocolHandler producerSuccessor = new McpProtocolHandler(sessions, project, "codex",
        "reviewer-continuation-producer-successor");
    Map<String, Object> producerContinuation = continuationArguments(
        producerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
    Map<String, Object> producerContinuationResult = (Map<String, Object>) innerResult(
        producerSuccessor.handleMessage(toolCall("request_coordination",
            ProviderJson.write(producerContinuation)))).get("result");
    assertEquals("CONTINUED", producerContinuationResult.get("status"),
        producerContinuationResult.toString());
    UUID producerSuccessorIntentId = UUID.fromString(String.valueOf(
        producerContinuationResult.get("successorIntentId")));

    RecoverySnapshotService.Snapshot reviewerSnapshot = new RecoverySnapshotService().materialize(
        location, reviewerBinding.sessionId());
    intentService.suspend(reviewerClaim.intent().participant());
    String reviewerReference = reviewerSnapshot.root() + "#" + reviewerSnapshot.contentHash();
    intentService.holdRecovery(reviewerClaim.intent().participant(), reviewerReference);

    McpProtocolHandler reviewerSuccessor = new McpProtocolHandler(sessions, project, "codex",
        "reviewer-continuation-reviewer-successor");
    Map<String, Object> reviewerContinuation = continuationArguments(
        reviewerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
    Map<String, Object> reviewerContinuationResult = (Map<String, Object>) innerResult(
        reviewerSuccessor.handleMessage(toolCall("request_coordination",
            ProviderJson.write(reviewerContinuation)))).get("result");
    assertEquals("CONTINUED", reviewerContinuationResult.get("status"),
        reviewerContinuationResult.toString());

    Map<String, Object> reviewerNext = innerResult(
        reviewerSuccessor.handleMessage(toolCall("get_next_action", "{}")));
    Map<String, Object> reviewerNextResult = (Map<String, Object>) reviewerNext.get("result");
    Map<String, Object> projectedPayload = (Map<String, Object>) reviewerNextResult.get(
        "nextProtocolPayload");
    assertEquals("request_coordination", reviewerNext.get("nextAction"), reviewerNext.toString());
    assertEquals(producerSuccessorIntentId.toString(), projectedPayload.get("intentId"),
        reviewerNext.toString());
    assertEquals(producerSuccessorIntentId.toString(), projectedPayload.get("reviewedIntentId"),
        reviewerNext.toString());

    Map<String, Object> joinArguments = (Map<String, Object>) ((Map<String, Object>)
        reviewerNextResult.get("workflow")).get("arguments");
    Map<String, Object> consumed = innerResult(reviewerSuccessor.handleMessage(toolCall(
        "request_coordination", ProviderJson.write(joinArguments))));
    assertEquals("completed", consumed.get("status"), consumed.toString());
    UUID grantId = UUID.fromString(String.valueOf(projectedPayload.get("grantId")));
    assertTrue(store(project).workGroupProjection().grantConsumed(grantId), consumed.toString());
  }

  @Test
  void reviewerSuccessorDiscoversConsumedSnapshotBoundGrantAfterRecovery(@TempDir Path temp)
      throws Exception {
    Path project = initializedProject(temp.resolve("snapshot-reviewer-continuation-project"));
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService()
        .locate(project);
    McpProviderTestSupport.install(location, "codex");
    new ProviderManualService().install("codex");

    AgentSessionService sessions = new AgentSessionService();
    ensureAndTrust(sessions, location, project, "snapshot-review-producer");
    ensureAndTrust(sessions, location, project, "snapshot-review-reviewer");
    ensureAndTrust(sessions, location, project, "snapshot-review-successor");
    ProviderSessionBindingService bindings = new ProviderSessionBindingService();
    var producerBinding = bindings.find(location, "codex", "snapshot-review-producer")
        .orElseThrow();
    var reviewerBinding = bindings.find(location, "codex", "snapshot-review-reviewer")
        .orElseThrow();

    UUID groupId = UUID.randomUUID();
    WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
    collaboration.createWorkGroup(project, groupId, "Build a feature",
        "Review the exact candidate before integration", ReviewPolicy.mandatoryIndependent());
    ResourceSelector source = ResourceSelector.pathExact("src/partial.txt");
    var producerClaim = collaboration.announce(project, "codex", "snapshot-review-producer",
        "Implement a feature", "Publish the feature candidate", List.of(source), groupId);
    var reviewerClaim = collaboration.announce(project, "codex", "snapshot-review-reviewer",
        "Review the feature", "Independently review the exact snapshot",
        List.of(ResourceSelector.pathExact("review-report.md")), groupId,
        WorkIntent.Role.REVIEWER, List.of(source));

    String snapshotId = "snap_reviewer_recovery";
    String providerSession = producerBinding.sessionId();
    SnapshotProvenance provenance = new SnapshotProvenance(groupId,
        producerClaim.intent().intentId(), producerClaim.intent().authorityLineageId(),
        producerClaim.intent().participant(), providerSession,
        producerClaim.intent().version(), List.of(), List.of(), List.of("PATH_EXACT:src/partial.txt"),
        "refs/synesis/snapshots/" + snapshotId, "integrity-" + snapshotId,
        "manifest-" + snapshotId);
    TaskSnapshotPayload snapshot = new TaskSnapshotPayload(producerClaim.intent().taskId(),
        snapshotId, "node", "supervisor", "worker", providerSession,
        producerClaim.intent().baseCommit(), "candidate-commit", List.of("src/partial.txt"),
        List.of(), "Publish candidate", provenance, true);
    var identity = new IdentityBootstrap(location.profile().resolve("link"))
        .loadOrCreate().identity();
    store(project).append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED,
        identity.nodeId(), snapshot.encode(), identity);

    McpProtocolHandler reviewer = new McpProtocolHandler(sessions, project, "codex",
        "snapshot-review-reviewer");
    McpProtocolHandler successor = new McpProtocolHandler(sessions, project, "codex",
        "snapshot-review-successor");
    String request = reviewer.handleMessage(toolCall("request_coordination",
        "{\"kind\":\"work_group_join\",\"payload\":{"
            + "\"workGroupId\":\"" + groupId + "\","
            + "\"intentId\":\"" + producerClaim.intent().intentId() + "\","
            + "\"snapshotId\":\"" + snapshotId + "\","
            + "\"proposal\":\"Review this exact immutable snapshot\"}}"));
    assertEquals("completed", innerResult(request).get("status"), request);
    Map<String, Object> requestResult = (Map<String, Object>) innerResult(request).get("result");
    String requestId = String.valueOf(((Map<String, Object>) requestResult.get("request"))
        .get("requestId"));
    collaboration.respond(project, "codex", "snapshot-review-producer",
        UUID.fromString(requestId), CoordinationRequest.Status.ACCEPTED, "admitted");

    Map<String, Object> reviewerAdmission = innerResult(reviewer.handleMessage(
        toolCall("get_next_action", "{}")));
    assertEquals("request_coordination", reviewerAdmission.get("nextAction"),
        reviewerAdmission.toString());
    Map<String, Object> admissionArguments = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) reviewerAdmission.get("result")).get("workflow"))
        .get("arguments");
    Map<String, Object> consumed = innerResult(reviewer.handleMessage(toolCall(
        "request_coordination", ProviderJson.write(admissionArguments))));
    assertEquals("completed", consumed.get("status"), consumed.toString());
    var originalGrant = store(project).workGroupProjection().grants().stream()
        .filter(grant -> snapshotId.equals(grant.snapshotId()))
        .findFirst().orElseThrow();
    assertTrue(store(project).workGroupProjection().grantConsumed(originalGrant.grantId()));

    RecoverySnapshotService.Snapshot recovery = new RecoverySnapshotService()
        .materialize(location, reviewerBinding.sessionId());
    var lifecycle = new org.synesis.coordination.application.WorkIntentService(store(project),
        identity);
    lifecycle.suspend(reviewerClaim.intent().participant());
    lifecycle.holdRecovery(reviewerClaim.intent().participant(), recovery.root() + "#"
        + recovery.contentHash());

    Map<String, Object> continuation = continuationArguments(successor.handleMessage(
        toolCall("get_next_action", "{}")));
    assertEquals("continuation", continuation.get("kind"), continuation.toString());
    assertTrue(continuation.get("payload") instanceof Map<?, ?>, continuation.toString());
    Map<String, Object> continuationResult = (Map<String, Object>) innerResult(
        successor.handleMessage(toolCall("request_coordination",
            ProviderJson.write(continuation)))).get("result");
    assertEquals("CONTINUED", continuationResult.get("status"), continuationResult.toString());
    assertNotEquals(reviewerClaim.intent().participant(),
        continuationResult.get("successorParticipant"));

    var afterContinuation = store(project);
    assertTrue(afterContinuation.workGroupProjection().grantSuperseded(originalGrant.grantId()));
    var replacement = afterContinuation.workGroupProjection().grants().stream()
        .filter(grant -> snapshotId.equals(grant.snapshotId()))
        .filter(grant -> grant.targetParticipant().equals(String.valueOf(
            continuationResult.get("successorParticipant"))))
        .findFirst().orElseThrow();
    assertTrue(afterContinuation.workGroupProjection().grantAvailable(replacement.grantId()));

    Map<String, Object> reviewAction = innerResult(successor.handleMessage(
        toolCall("get_next_action", "{}")));
    assertEquals("request_coordination", reviewAction.get("nextAction"),
        reviewAction.toString());
    Map<String, Object> reviewArguments = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) reviewAction.get("result")).get("workflow"))
        .get("arguments");
    Map<String, Object> reviewPayload = (Map<String, Object>) reviewArguments.get("payload");
    assertEquals(replacement.grantId().toString(), reviewPayload.get("grantId"));
    assertEquals(snapshotId, reviewPayload.get("snapshotId"));
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

  private static void ensureAndTrust(AgentSessionService sessions,
      ProjectApplicationService.ProjectLocation location, Path project, String connection)
      throws Exception {
    sessions.ensureSession(new AgentSessionService.SessionResolutionRequest(
        project, "codex", connection, null, false));
    var binding = new ProviderSessionBindingService().find(location, "codex", connection)
        .orElseThrow();
    new ProviderSessionBindingService().verifyWorkspaceTrust(location, "codex", binding.sessionId(),
        Path.of(binding.worktreePath()));
  }

  private static PredictionEventStore store(Path project) throws Exception {
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().locate(
        project);
    return new PredictionEventStore(location.root()
        .resolve(".synesis/coordination"), location.projectId());
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
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
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
