package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.AgentSessionService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.GitProcessRunner;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * Verifies the explicit SYN-039 no-change completion workflow at the MCP boundary.
 */
final class McpSyn039NoChangeCompletionTest {

    private String previousHome;

    private static String toolCall(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerResult(String json) {
        Map<String, Object> outer = (Map<String, Object>) ProviderJson.parse(json);
        Map<String, Object> rpcResult = (Map<String, Object>) outer.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) rpcResult.get("content");
        return (Map<String, Object>) ProviderJson.parse((String) content.getFirst()
                .get("text"));
    }

    private static void git(Path root, String... arguments) throws Exception {
        GitProcessRunner.run(root, arguments);
    }

    @BeforeEach
    void isolateProviderManual() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home",
                Files.createTempDirectory("synesis-syn039-home-")
                        .toString());
    }

    @AfterEach
    void restoreProviderManualHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectedNoChangeFinishReleasesClaimsAndCompletesGroup(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change", true);

        Map<String, Object> projection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("finish_lane", projection.get("nextAction"), projection.toString());
        Map<String, Object> projectedResult = (Map<String, Object>) projection.get("result");
        assertEquals(true, projectedResult.get("noChangeCompletionAvailable"));
        Map<String, Object> workflow = (Map<String, Object>) projectedResult.get("workflow");
        assertEquals("finish_lane", workflow.get("recommendedTool"));
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");
        assertEquals("no_change", finishArguments.get("outcome"));
        assertTrue(finishArguments.containsKey("intentId"), finishArguments.toString());
        assertTrue(finishArguments.containsKey("workGroupVersion"), finishArguments.toString());
        assertTrue(finishArguments.containsKey("expectedRevision"), finishArguments.toString());

        Map<String, Object> completed = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("completed", completed.get("status"), completed.toString());
        Map<String, Object> completionResult = (Map<String, Object>) completed.get("result");
        assertEquals("NO_CHANGE", completionResult.get("outcome"));
        assertEquals("NOT_REQUIRED", completionResult.get("snapshotState"));
        assertEquals("NOT_REQUIRED", completionResult.get("integrationState"));
        assertEquals(true, completionResult.get("claimsReleased"));

        PredictionEventStore store = fixture.store();
        assertTrue(store.collaborationProjection()
                .activeIntents()
                .isEmpty());
        assertEquals("COMPLETED",
                store.workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
        assertEquals(1,
                store.collaborationProjection()
                        .noChangeCompletions()
                        .size());
        assertEquals(1,
                store.events()
                        .stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED)
                        .count());
        assertEquals(1,
                store.events()
                        .stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED)
                        .count());

        fixture.handler.close();
        assertEquals("COMPLETED",
                fixture.store()
                        .workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitNoChangeReplayIsStable(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-replay", true);
        Map<String, Object> projection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) projection.get("result")).get(
                "workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");

        Map<String, Object> first = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        Map<String, Object> second = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("completed", first.get("status"), first.toString());
        assertEquals("completed", second.get("status"), second.toString());
        assertEquals(first.get("result"), second.get("result"));
        assertEquals(1,
                fixture.store()
                        .events()
                        .stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_INTENT_RELEASED)
                        .count());
        assertEquals(1,
                fixture.store()
                        .events()
                        .stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED)
                        .count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void packagedBoundaryTerminalSealClassifiesLaterAbnormalTransportAsHistory(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn041-terminal-boundary", true);
        Map<String, Object> projection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) projection.get("result")).get(
                "workflow");
        Map<String, Object> finishArguments = new LinkedHashMap<>((Map<String, Object>) workflow.get("arguments"));
        finishArguments.put("terminalSession", true);

        Map<String, Object> completed = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("completed", completed.get("status"), completed.toString());
        Map<String, Object> result = (Map<String, Object>) completed.get("result");
        assertEquals("SESSION_TERMINATED", result.get("sessionTermination"), result.toString());
        assertTrue(fixture.store()
                .collaborationProjection()
                .isSessionTerminal(new ProviderSessionBindingService().list(fixture.location(), "codex")
                        .getFirst()
                        .sessionId()));

        var lease = new SessionLeaseStore().load(fixture.project, fixture.connection())
                .orElseThrow();
        var abnormal = new SessionLeaseService(new SessionLeaseStore(),
                _ -> java.util.Optional.empty()).evaluateLiveness(lease,
                new org.synesis.workspace.lifecycle.lease.SessionLeasePolicy());
        assertEquals(org.synesis.workspace.lifecycle.lease.SessionLeaseState.TERMINAL_DISCONNECTED, abnormal);

        var doctor = new DoctorService(new ProjectApplicationService(),
                new org.synesis.workspace.lifecycle.cleanup.LifecycleInventoryService(),
                new org.synesis.workspace.lifecycle.cleanup.CleanupEligibilityService(),
                new SessionLeaseService(new SessionLeaseStore(), _ -> java.util.Optional.empty()),
                new SessionLeaseStore(),
                _ -> java.util.Optional.empty()).diagnose(fixture.project);
        assertTrue(doctor.findings()
                        .stream()
                        .noneMatch(f -> f.code() == DoctorFindingCode.STALE_SESSION_LEASE),
                doctor.findings()
                        .toString());

        long originalPid = lease.processIdentity()
                .pid();
        assertTrue(new SessionLeaseService(new SessionLeaseStore(),
                _ -> java.util.Optional.empty()).markTerminalDisconnected(fixture.project,
                fixture.connection,
                originalPid));
        var abnormalHistory = new SessionLeaseStore().load(fixture.project, fixture.connection)
                .orElseThrow();
        McpProtocolHandler rejectedProbe = new McpProtocolHandler(new AgentSessionService(),
                fixture.project,
                "codex",
                fixture.connection);
        Map<String, Object> rejectedEnsure = innerResult(rejectedProbe.handleMessage(toolCall("ensure_session", "{}")));
        assertEquals("completed", rejectedEnsure.get("status"), rejectedEnsure.toString());
        assertEquals("SESSION_TERMINAL", ((Map<?, ?>) rejectedEnsure.get("result")).get("state"));
        rejectedProbe.close();
        assertEquals(abnormalHistory,
                new SessionLeaseStore().load(fixture.project, fixture.connection)
                        .orElseThrow());
        var doctorAfterProbe = new DoctorService(new ProjectApplicationService(),
                new org.synesis.workspace.lifecycle.cleanup.LifecycleInventoryService(),
                new org.synesis.workspace.lifecycle.cleanup.CleanupEligibilityService(),
                new SessionLeaseService(new SessionLeaseStore(), _ -> java.util.Optional.empty()),
                new SessionLeaseStore(),
                _ -> java.util.Optional.empty()).diagnose(fixture.project);
        assertTrue(doctorAfterProbe.findings()
                        .stream()
                        .noneMatch(f -> f.code() == DoctorFindingCode.STALE_SESSION_LEASE
                                || f.code() == DoctorFindingCode.AMBIGUOUS_SESSION_LIVENESS
                                || f.code() == DoctorFindingCode.DURABLE_STATE_AMBIGUOUS),
                doctorAfterProbe.findings()
                        .toString());
        fixture.handler.close();
    }

    @Test
    void providerExitDoesNotInferNoChangeCompletion(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-exit", true);
        fixture.handler.close();

        PredictionEventStore store = fixture.store();
        assertTrue(store.collaborationProjection()
                .activeIntents()
                .isEmpty());
        assertEquals("ACTIVE",
                store.workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
        assertTrue(store.collaborationProjection()
                .noChangeCompletions()
                .isEmpty());
        assertEquals(0,
                store.events()
                        .stream()
                        .filter(event -> event.type() == PredictionEventType.WORK_GROUP_STATUS_CHANGED)
                        .count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirtyNoChangeLaneIsRejectedWithoutRelease(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-dirty", true);
        var binding = new ProviderSessionBindingService().find(fixture.location(), "codex", fixture.connection())
                .orElseThrow();
        assertNotNull(binding.worktreePath());
        Path worktree = Path.of(binding.worktreePath());
        Map<String, Object> cleanProjection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action",
                "{}")));
        Map<String, Object> cleanWorkflow = (Map<String, Object>) ((Map<String, Object>) cleanProjection.get("result")).get(
                "workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) cleanWorkflow.get("arguments");
        Files.writeString(worktree.resolve("unpublished.txt"), "mutation\n");

        Map<String, Object> rejected = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("retry_required", rejected.get("status"), rejected.toString());
        assertEquals("task_not_ready", rejected.get("reason"), rejected.toString());
        assertEquals("NO_CHANGE_DIRTY_WORKSPACE", ((Map<String, Object>) rejected.get("result")).get("reason"));

        PredictionEventStore store = fixture.store();
        assertTrue(store.collaborationProjection()
                .intent(fixture.intentId())
                .isPresent());
        assertEquals("ACTIVE",
                store.workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
        assertTrue(store.collaborationProjection()
                .noChangeCompletions()
                .isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleWorkspaceGenerationIsRejectedWithoutRelease(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-generation", true);
        var binding = new ProviderSessionBindingService().find(fixture.location(), "codex", fixture.connection())
                .orElseThrow();
        Path worktree = Path.of(binding.worktreePath());
        Map<String, Object> projection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) projection.get("result")).get(
                "workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) workflow.get("arguments");
        Files.writeString(worktree.resolve("committed-after-bind.txt"), "generation drift\n");
        git(worktree, "add", "committed-after-bind.txt");
        git(worktree, "commit", "-m", "advance worker generation");

        Map<String, Object> rejected = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("retry_required", rejected.get("status"), rejected.toString());
        assertEquals("workspace_generation_changed", rejected.get("reason"), rejected.toString());

        PredictionEventStore store = fixture.store();
        assertTrue(store.collaborationProjection()
                .intent(fixture.intentId())
                .isPresent());
        assertEquals("ACTIVE",
                store.workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
        assertTrue(store.collaborationProjection()
                .noChangeCompletions()
                .isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanNoChangeLaneCompletesAfterControlBaseAdvances(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-control-advance", true);
        innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        Files.writeString(fixture.project.resolve("control-advance.txt"), "integrated elsewhere\n");
        git(fixture.project, "add", "control-advance.txt");
        git(fixture.project, "commit", "-m", "advance control checkout");

        var stale = new WorkspaceReadinessService().assess(fixture.location, "codex", fixture.connection());
        assertFalse(stale.ready());
        assertEquals("CONTROL_BASE_ADVANCED", stale.internalReason());

        Map<String, Object> refreshed = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        assertEquals("finish_lane", refreshed.get("nextAction"), refreshed.toString());
        Map<String, Object> refreshedResult = (Map<String, Object>) refreshed.get("result");
        assertEquals(true, refreshedResult.get("noChangeCompletionAvailable"));
        Map<String, Object> refreshedWorkflow = (Map<String, Object>) refreshedResult.get("workflow");
        Map<String, Object> finishArguments = (Map<String, Object>) refreshedWorkflow.get("arguments");

        Map<String, Object> completed = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("completed", completed.get("status"), completed.toString());
        assertEquals("NO_CHANGE", ((Map<String, Object>) completed.get("result")).get("outcome"));
        assertTrue(fixture.store()
                .collaborationProjection()
                .activeIntents()
                .isEmpty());
        assertEquals("COMPLETED",
                fixture.store()
                        .workGroupProjection()
                        .group(fixture.groupId())
                        .orElseThrow()
                        .status()
                        .name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotIntentRejectsExplicitNoChangeOutcome(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-snapshot-contract", false);
        var binding = new ProviderSessionBindingService().find(fixture.location(), "codex", fixture.connection())
                .orElseThrow();
        PredictionEventStore store = fixture.store();
        var intent = store.collaborationProjection()
                .intent(fixture.intentId())
                .orElseThrow();
        var group = store.workGroupProjection()
                .group(fixture.groupId())
                .orElseThrow();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("outcome", "no_change");
        arguments.put("intentId",
                intent.intentId()
                        .toString());
        arguments.put("workGroupId",
                group.workGroupId()
                        .toString());
        arguments.put("claimEpoch", intent.version());
        arguments.put("workGroupVersion", group.version());
        arguments.put("expectedRevision", store.headSequence());
        arguments.put("participant", WorkspaceCollaborationService.participantHandle(binding.sessionId()));

        Map<String, Object> rejected = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(arguments))));
        assertEquals("blocked", rejected.get("status"), rejected.toString());
        assertEquals("policy_denied", rejected.get("reason"), rejected.toString());
        assertEquals("NO_CHANGE_NOT_AUTHORIZED", ((Map<String, Object>) rejected.get("result")).get("reason"));
        assertTrue(fixture.store()
                .collaborationProjection()
                .intent(fixture.intentId())
                .isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedParticipantRemainsFailClosed(@TempDir Path temp) throws Exception {
        Fixture fixture = prepare(temp, "syn039-no-change-participant", true);
        Map<String, Object> projection = innerResult(fixture.handler.handleMessage(toolCall("get_next_action", "{}")));
        Map<String, Object> workflow = (Map<String, Object>) ((Map<String, Object>) projection.get("result")).get(
                "workflow");
        Map<String, Object> finishArguments = new LinkedHashMap<>((Map<String, Object>) workflow.get("arguments"));
        finishArguments.put("participant", "agt_not_the_bound_participant");

        Map<String, Object> rejected = innerResult(fixture.handler.handleMessage(toolCall("finish_lane",
                ProviderJson.write(finishArguments))));
        assertEquals("blocked", rejected.get("status"), rejected.toString());
        assertEquals("policy_denied", rejected.get("reason"), rejected.toString());
        assertEquals("NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH",
                ((Map<String, Object>) rejected.get("result")).get("reason"));
        assertTrue(fixture.store()
                .collaborationProjection()
                .intent(fixture.intentId())
                .isPresent());
        assertTrue(fixture.store()
                .collaborationProjection()
                .noChangeCompletions()
                .isEmpty());
    }

    private Fixture prepare(Path temp, String connection, boolean noChange) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        git(project, "init");
        git(project, "config", "user.name", "SYN-039 Test");
        git(project, "config", "user.email", "syn039@example.test");
        Files.writeString(project.resolve("verification.txt"), "baseline\n");
        git(project, "add", ".");
        git(project, "commit", "-m", "baseline");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(project)
                .location();
        new ProviderManualService().install("codex");
        McpProtocolHandler handler = new McpProtocolHandler(new AgentSessionService(), project, "codex", connection);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("goal", "Verify the repository");
        task.put("acceptance", "Verification succeeds without repository mutation");
        task.put("completionMode", noChange ? "no_change_allowed" : "snapshot_required");
        task.put("claims", List.of(Map.of("kind", "path_exact", "path", "verification.txt")));
        Map<String, Object> ensure = new LinkedHashMap<>();
        ensure.put("task", task);
        Map<String, Object> response = innerResult(handler.handleMessage(toolCall("ensure_session",
                ProviderJson.write(ensure))));
        assertEquals("ready", response.get("status"), response.toString());
        PredictionEventStore store = new PredictionEventStore(location.root()
                .resolve(".synesis/coordination"), location.projectId());
        var intent = store.collaborationProjection()
                .activeIntents()
                .stream()
                .findFirst()
                .orElseThrow();
        return new Fixture(project, location, connection, handler, intent.intentId(), intent.workGroupId());
    }

    private record Fixture(Path project, ProjectApplicationService.ProjectLocation location, String connection,
                           McpProtocolHandler handler, UUID intentId, UUID groupId) {

        private PredictionEventStore store() {
            try {
                return new PredictionEventStore(location.root()
                        .resolve(".synesis/coordination"), location.projectId());
            } catch (java.io.IOException | java.security.GeneralSecurityException exception) {
                throw new IllegalStateException("Unable to reopen the coordination store", exception);
            }
        }
    }
}
