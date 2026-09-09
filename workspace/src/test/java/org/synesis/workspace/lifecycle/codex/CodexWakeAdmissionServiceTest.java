package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.capability.SecureRandomCapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Tests exact dormant-participant wake admission and duplicate suppression.
 */
class CodexWakeAdmissionServiceTest {

  @TempDir
  Path temp;

  private static CodexWakeAdmissionService.DormantBinding candidate(String actionKey) {
    return candidate(actionKey, CodexLifecycleStateStore.State.COMPLETED);
  }

  private static CodexWakeAdmissionService.DormantBinding candidate(String actionKey,
      CodexLifecycleStateStore.State state) {
    ProviderSessionBindingService.Binding binding = new ProviderSessionBindingService.Binding(
        2, "session-1", "project-1", "node-1", "codex", "fingerprint-1", "supervisor-1",
        "worker-1", "worktree-1", "C:/worktree-1", "C:/project-1", "synesis/codex/session-1",
        "0123456789012345678901234567890123456789", "C:/git", "ALLOCATED", "VERIFIED",
        "WORKTREE_VERIFIED", "BOUND", 1L, 2L, 3L, "VERIFIED", 1, null);
    CodexLifecycleStateStore.Checkpoint checkpoint = new CodexLifecycleStateStore.Checkpoint(
        "session-1", "project-1", "codex", 7L, state, "host-1", 2L, 3L, 42L, 4L,
        "codex.exe", "codex.exe --app-server", "thread-1", "turn-1", null, true, 8L);
    String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
    return new CodexWakeAdmissionService.DormantBinding(binding, checkpoint, "intent-1", 4L,
        new CodexWakeAdmissionService.ActionableItem(actionKey, participant));
  }

  @Test
  void liveAttachmentUsesNotifyWithExactAuthorityAndThread() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher(true);
    MemoryAdmissionStore admissions = new MemoryAdmissionStore();
    CodexWakeAdmissionService service = new CodexWakeAdmissionService(dispatcher, admissions);
    CodexWakeAdmissionService.WakeResult result = service.admit(candidate("request-1"));

    assertEquals(CodexWakeAdmissionService.Outcome.DISPATCHED, result.outcome());
    assertEquals(LifecycleControlRequestEnvelope.Operation.NOTIFY, result.operation());
    LifecycleControlRequestEnvelope request = dispatcher.requests.getFirst();
    assertEquals("thread-1", request.expectedThreadId());
    assertEquals("turn-1", request.expectedTurnId());
    assertEquals("project-1",
        request.authority()
            .projectId());
    assertEquals("session-1",
        request.authority()
            .bindingSessionId());
    assertEquals(WorkspaceCollaborationService.participantHandle("session-1"),
        request.authority()
            .participant());
    assertTrue(request.continuation());
    assertTrue(request.input()
        .contains("get_next_action"));
    assertEquals(Set.of(result.admissionKey()), admissions.keys);
  }

  @Test
  void deadAttachmentUsesResumeAndPreservesExactThread() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher(false);
    CodexWakeAdmissionService service = new CodexWakeAdmissionService(dispatcher,
        new MemoryAdmissionStore());

    CodexWakeAdmissionService.WakeResult result = service.admit(candidate("request-2"));

    assertEquals(CodexWakeAdmissionService.Outcome.DISPATCHED, result.outcome());
    assertEquals(LifecycleControlRequestEnvelope.Operation.RESUME, result.operation());
    assertEquals("thread-1",
        dispatcher.requests.getFirst()
            .expectedThreadId());
    assertEquals("turn-1",
        dispatcher.requests.getFirst()
            .expectedTurnId());
  }

  @Test
  void durablePeerRequestSelectsOnlyTheExactDormantBinding() throws Exception {
    Path project = Files.createDirectories(temp.resolve("project"));
    TestGit.run(project, "init");
    TestGit.run(project, "config", "user.name", "Wake Test");
    TestGit.run(project, "config", "user.email", "wake@example.test");
    Files.writeString(project.resolve("README.md"), "wake fixture\n");
    TestGit.run(project, "add", ".");
    TestGit.run(project, "commit", "-m", "fixture");
    ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(
            project)
        .location();
    ProviderTestSupport.install(location, "codex");

    AgentSessionService sessionService = new AgentSessionService();
    AgentSessionService.AgentSessionContext dormant = sessionService.resolveSessionContext(
        new AgentSessionService.SessionResolutionRequest(project, "codex", "wake-dormant", null,
            false));
    AgentSessionService.AgentSessionContext peer = sessionService.resolveSessionContext(
        new AgentSessionService.SessionResolutionRequest(project, "codex", "wake-peer", null,
            false));
    ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
    bindingService.verifyWorkspaceTrust(location, "codex", dormant.sessionId(),
        dormant.worktreePath());
    bindingService.verifyWorkspaceTrust(location, "codex", peer.sessionId(), peer.worktreePath());
    ProviderSessionBindingService.Binding dormantBinding = bindingService.list(location, "codex")
        .stream()
        .filter(binding -> binding.sessionId()
            .equals(dormant.sessionId()))
        .findFirst()
        .orElseThrow();
    ProviderSessionBindingService.Binding peerBinding = bindingService.list(location, "codex")
        .stream()
        .filter(binding -> binding.sessionId()
            .equals(peer.sessionId()))
        .findFirst()
        .orElseThrow();

    WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
    var dormantIntent = collaboration.announce(project, "codex", "wake-dormant",
        "dormant lane", "wake acceptance", List.of(ResourceSelector.pathExact("README.md")));
    collaboration.announce(project, "codex", "wake-peer",
        "peer lane", "peer acceptance", List.of(ResourceSelector.pathExact("peer.txt")));
    collaboration.request(project,
        "codex",
        "wake-peer",
        dormantIntent.intent()
            .intentId(),
        CoordinationRequest.Kind.CONTRACT,
        "peer durable state changed");

    CodexLifecycleStateStore stateStore = new CodexLifecycleStateStore(
        CodexWakeAdmissionService.runtimeRoot(location)
            .resolve("bindings"));
    stateStore.write(new CodexLifecycleStateStore.Checkpoint(
        dormantBinding.sessionId(),
        location.projectId()
            .toString(),
        "codex",
        12L,
        CodexLifecycleStateStore.State.COMPLETED,
        "host-fixture",
        4L,
        5L,
        123L,
        6L,
        "codex.exe",
        "codex.exe --app-server",
        "thread-dormant",
        "turn-old",
        null,
        true,
        System.currentTimeMillis()));

    FakeDispatcher dispatcher = new FakeDispatcher(false);
    CodexWakeAdmissionService service = new CodexWakeAdmissionService(dispatcher,
        new MemoryAdmissionStore());
    List<CodexWakeAdmissionService.WakeResult> results = service.scan(project);

    assertEquals(1, results.size());
    assertEquals(CodexWakeAdmissionService.Outcome.DISPATCHED,
        results.getFirst()
            .outcome());
    assertEquals(LifecycleControlRequestEnvelope.Operation.RESUME,
        results.getFirst()
            .operation());
    LifecycleControlRequestEnvelope request = dispatcher.requests.getFirst();
    assertEquals(location.projectId()
            .toString(),
        request.authority()
            .projectId());
    assertEquals(dormantBinding.sessionId(),
        request.authority()
            .bindingSessionId());
    assertEquals(WorkspaceCollaborationService.participantHandle(dormantBinding.sessionId()),
        request.authority()
            .participant());
    assertEquals("thread-dormant", request.expectedThreadId());
    assertEquals("turn-old", request.expectedTurnId());
    assertTrue(request.input()
        .contains("get_next_action"));
    assertFalse(peerBinding.sessionId()
        .equals(request.authority()
            .bindingSessionId()));
  }

  @Test
  void fileLedgerFailsClosedAtBoundInsteadOfEvictingExactIdentity() throws Exception {
    Path file = temp.resolve("wake-admissions.json");
    List<String> entries = java.util.stream.IntStream.range(0, 2_048)
        .mapToObj(index -> "key-" + index)
        .toList();
    Files.writeString(file, org.synesis.workspace.infrastructure.json.ProviderJson.write(
        java.util.Map.of("entries", entries)));

    CodexWakeAdmissionService.FileAdmissionStore store =
        new CodexWakeAdmissionService.FileAdmissionStore(file);

    assertTrue(store.contains("key-2047"));
    IOException failure = assertThrows(IOException.class, () -> store.record("key-new"));
    assertTrue(failure.getMessage()
        .contains("exceeds bound"));
    assertFalse(store.contains("key-new"));
  }

  @Test
  void sameActionIsAdmittedOnceAndNonDormantStateIsRejected() throws Exception {
    FakeDispatcher dispatcher = new FakeDispatcher(true);
    MemoryAdmissionStore admissions = new MemoryAdmissionStore();
    CodexWakeAdmissionService service = new CodexWakeAdmissionService(dispatcher, admissions);

    CodexWakeAdmissionService.WakeResult first = service.admit(candidate("request-3"));
    CodexWakeAdmissionService.WakeResult duplicate = service.admit(candidate("request-3"));
    CodexWakeAdmissionService.WakeResult running = service.admit(candidate("request-4",
        CodexLifecycleStateStore.State.RUNNING));

    assertEquals(CodexWakeAdmissionService.Outcome.DISPATCHED, first.outcome());
    assertEquals(CodexWakeAdmissionService.Outcome.SKIPPED_ALREADY_ADMITTED, duplicate.outcome());
    assertEquals(CodexWakeAdmissionService.Outcome.NOT_DORMANT, running.outcome());
    assertEquals(1, dispatcher.requests.size());
  }

  @Test
  void capabilityPublicationTargetsOnlyTheExactRequesterBinding() {
    CodexWakeAdmissionService.DormantBinding dormant = candidate("request-capability");
    ProviderSessionBindingService.Binding binding = dormant.binding();
    CapabilityContract contract = new CapabilityContract("inputs", "output", List.of("behavior"),
        List.of("acceptance"));
    CapabilityRequestRecord available = new CapabilityRequestRecord(
        new SecureRandomCapabilityRequestHandleGenerator().generate(),
        "tasktracker.domain.persistence",
        "node-1", "supervisor-1", "worker-1", "owner-node", "", "", UUID.randomUUID(), contract,
        CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, null, 1L, 2L);

    String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
    CodexWakeAdmissionService.ActionableItem action = CodexWakeAdmissionService.capabilityAction(
        available, binding, participant);

    assertEquals("capability-request:" + available.handle()
        .value(), action.key());
    assertEquals(participant, action.participant());
    assertEquals(null, CodexWakeAdmissionService.capabilityAction(
        available.withUpdate(CapabilityLifecycleState.AWAITING_OWNER, null, null, 3L), binding,
        participant));
    ProviderSessionBindingService.Binding otherBinding = new ProviderSessionBindingService.Binding(
        binding.schemaVersion(), binding.sessionId(), binding.projectId(), binding.nodeId(),
        binding.provider(),
        binding.providerInstanceFingerprint(), "other-supervisor", binding.workerId(),
        binding.worktreeId(),
        binding.worktreePath(), binding.controlCheckoutPath(), binding.branch(),
        binding.baseCommit(),
        binding.gitCommonDir(), binding.creationState(), binding.verificationState(),
        binding.lastSeenState(),
        binding.status(), binding.createdAtEpochMillis(), binding.lastSeenEpochMillis(),
        binding.lastVerifiedProjectSequence(), binding.providerTrustState(),
        binding.bindingVersion(),
        binding.completedAt());
    assertEquals(null,
        CodexWakeAdmissionService.capabilityAction(available, otherBinding, participant));
  }

  private static final class FakeDispatcher implements
      CodexWakeAdmissionService.LifecycleDispatcher {

    private final boolean live;
    private final List<LifecycleControlRequestEnvelope> requests = new ArrayList<>();

    private FakeDispatcher(boolean live) {
      this.live = live;
    }

    @Override
    public String hostInstanceId() {
      return "host-1";
    }

    @Override
    public boolean attachmentAlive(String bindingSessionId) {
      return live;
    }

    @Override
    public CodexLifecycleHttpClient.Response dispatch(LifecycleControlRequestEnvelope request) {
      requests.add(request);
      return new CodexLifecycleHttpClient.Response(true, "continued", "RUNNING", 8L,
          request.expectedThreadId(), "turn-2", java.util.Map.of());
    }
  }

  private static final class MemoryAdmissionStore implements
      CodexWakeAdmissionService.AdmissionStore {

    private final Set<String> keys = new HashSet<>();

    @Override
    public boolean contains(String key) {
      return keys.contains(key);
    }

    @Override
    public void record(String key) {
      keys.add(key);
    }
  }
}
