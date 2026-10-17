package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpToolArguments.isStrictInteger;
import static org.synesis.mcp.application.McpToolArguments.validationSnapshotSelector;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.application.integration.CapabilityValidationSnapshotAccessService;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;
import org.synesis.workspace.application.project.ProjectCommandService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.command.PhysicalWorktreeIdentity;
import org.synesis.workspace.lifecycle.command.ProjectCommandAuthoritySnapshot;
import org.synesis.workspace.lifecycle.command.ProjectCommandProcessAnchor;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;
import org.synesis.workspace.project.SleepCommands;

/**
 * Runs the {@code run_command} tool: validates its arguments and executes the command against a
 * validation snapshot, a review snapshot, or the durable command path, in that order.
 *
 * <p>The durable path is fenced by a process anchor that is captured on the first command and
 * re-armed only after a verified session resolution moves this connection to a different worktree.
 * That anchor is the one piece of connection state the command path mutates, so it lives here with
 * the code that reads it; the project root and the root-authority diagnostic stay owned by
 * {@link McpProjectRootSelection} and arrive as arguments at each call.</p>
 */
final class McpDurableCommand {

    /**
     * Largest accepted {@code run_command} timeout, in seconds.
     */
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 3600;
    /**
     * Service that admits durable project commands before execution.
     */
    private final ProjectCommandAdmissionService commandAdmissionService;
    /**
     * Service that grants reads of immutable review snapshots.
     */
    private final ReviewSnapshotAccessService reviewSnapshotAccessService;
    /** Resolves explicitly selected requester validation snapshots for reads and test commands. */
    private final CapabilityValidationSnapshotAccessService validationSnapshotAccessService;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;
    /**
     * Process evidence captured once so later close callbacks remain PID-gated.
     */
    private final SessionProcessIdentity commandProcessIdentity;
    /**
     * Renews the connection lease before each command admission step.
     */
    private final McpSessionLease lease;
    /**
     * Durable command anchor refreshed only after verified session activity.
     */
    private ProjectCommandProcessAnchor commandProcessAnchor;

    /**
     * Creates the command path for one connection.
     *
     * @param commandAdmissionService         service that admits durable project commands before execution
     * @param reviewSnapshotAccessService     grants reads and test commands against immutable review snapshots
     * @param validationSnapshotAccessService resolves requester validation snapshots for test commands
     * @param provider                        stable provider ID associated with this connection's binding
     * @param connectionInstanceId            connection incarnation used to prevent cross-connection authority reuse
     * @param commandProcessIdentity          process evidence captured once for this connection
     * @param lease                           lease renewal used by command admission
     */
    McpDurableCommand(ProjectCommandAdmissionService commandAdmissionService,
            ReviewSnapshotAccessService reviewSnapshotAccessService,
            CapabilityValidationSnapshotAccessService validationSnapshotAccessService,
            String provider,
            String connectionInstanceId,
            SessionProcessIdentity commandProcessIdentity,
            McpSessionLease lease) {
        this.commandAdmissionService = commandAdmissionService;
        this.reviewSnapshotAccessService = reviewSnapshotAccessService;
        this.validationSnapshotAccessService = validationSnapshotAccessService;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
        this.commandProcessIdentity = commandProcessIdentity;
        this.lease = lease;
    }

    /**
     * Validates the command arguments and runs the command against a validation snapshot, a review
     * snapshot, or the durable command path, in that order.
     *
     * @param id        JSON-RPC request identifier, used as the durable command request identifier
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @param projectRootAuthorityFailure fail-closed root diagnostic, or {@code null} when none is latched
     * @return the response to serialize
     */
    AgentResponse runCommand(Object id, Map<String, Object> arguments, Path activeProjectRoot, String projectRootAuthorityFailure) {
        AgentResponse agentResponse;
        List<String> argv = new ArrayList<>();
        boolean unsupportedField = arguments != null && arguments.keySet().stream()
                .anyMatch(key -> !Set.of("argv", "workingDirectory", "timeoutSeconds", "capabilityRequestHandle", "implementationRevision").contains(key));
        if (arguments != null && arguments.get("argv") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    argv = null;
                    break;
                }
                argv.add(s);
            }
        } else {
            argv = null;
        }
        boolean malformedWorkingDirectory = arguments != null && arguments.containsKey("workingDirectory") && !(arguments.get("workingDirectory") instanceof String);
        String workingDirectory = arguments != null && arguments.get("workingDirectory") instanceof String value ? value : ".";
        Integer timeoutSeconds = null;
        boolean malformedTimeout = false;
        if (arguments != null && arguments.containsKey("timeoutSeconds")) {
            Object rawTimeout = arguments.get("timeoutSeconds");
            if (rawTimeout instanceof Number number && isStrictInteger(number) && number.longValue() >= 1 && number.longValue() <= MAX_COMMAND_TIMEOUT_SECONDS) {
                timeoutSeconds = number.intValue();
            } else {
                malformedTimeout = true;
            }
        }
        if (argv == null || argv.isEmpty() || malformedWorkingDirectory || malformedTimeout || unsupportedField) {
            agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
        } else if (SleepCommands.onlySleeps(argv)) {
            agentResponse = sleepRefused();
        } else {
            try {
                var snapshot = validationSnapshotSelector(arguments);
                AgentResponse validationCommand = validationSnapshotAccessService.runCommand(activeProjectRoot,
                        provider,
                        connectionInstanceId,
                        snapshot.capabilityRequestHandle(),
                        snapshot.implementationRevision(),
                        argv,
                        workingDirectory,
                        timeoutSeconds);
                if (validationCommand != null) {
                    agentResponse = validationCommand;
                } else {
                    AgentResponse reviewCommand = reviewSnapshotAccessService.runReviewCommand(activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                    if (reviewCommand != null) {
                        agentResponse = reviewCommand;
                    } else {
                        ProjectCommandService.CommandRequest cmdReq = new ProjectCommandService.CommandRequest(activeProjectRoot, provider, connectionInstanceId, argv, workingDirectory, timeoutSeconds);
                        agentResponse = runDurableCommand(cmdReq, id, activeProjectRoot, projectRootAuthorityFailure);
                    }
                }
            } catch (IllegalArgumentException ex) {
                agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
        }
        return agentResponse;
    }

    /**
     * Refuses a command that only sleeps (ADR-0099). Agents slept here for up to fifteen minutes while the review they
     * waited for was asked of them; an agent waits by ending its turn, and Synesis wakes it.
     */
    private static AgentResponse sleepRefused() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("reason", "SLEEP_COMMAND_REFUSED");
        result.put("instruction", "Do not wait by sleeping. When Synesis says WAIT, end your turn: Synesis wakes you when something arrives for your lane, "
                + "from this computer or another one of the project.");
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, null, result);
    }

    /**
     * Admits, executes, and records one command under the refreshed session authority fence.
     *
     * @param request   validated command request
     * @param requestId JSON-RPC request identifier
     * @param activeProjectRoot the connection's currently selected control project root
     * @param projectRootAuthorityFailure fail-closed root diagnostic, or {@code null} when none is latched
     * @return the command outcome, or a retry or blocked response when authority cannot be verified
     */
    private AgentResponse runDurableCommand(ProjectCommandService.CommandRequest request, Object requestId, Path activeProjectRoot, String projectRootAuthorityFailure) {
        if (projectRootAuthorityFailure != null) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", projectRootAuthorityFailure));
        }
        ProjectLocation location;
        ReadinessResult readiness;
        try {
            location = new ProjectApplicationService().locate(activeProjectRoot);
            readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKSPACE_UNVERIFIED"));
        }
        if (!readiness.ready()) {
            return readiness.response();
        }

        PhysicalWorktreeIdentity worktree;
        try {
            worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot, readiness.worktree(), new LifecyclePathVerifier());
        } catch (Exception failure) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_STALE, AgentNextAction.ENSURE_SESSION, Map.of("error", "WORKTREE_IDENTITY_UNVERIFIED"));
        }
        if (commandProcessAnchor == null) {
            commandProcessAnchor = ProjectCommandProcessAnchor.capture(worktree.locator(), commandProcessIdentity, Instant.now().toEpochMilli());
        } else if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COMMAND_ADMISSION_STALE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "MCP_PROCESS_SCOPE_CHANGED"));
        }
        ProjectCommandAuthoritySnapshot before = captureAuthoritySnapshot(readiness, worktree, activeProjectRoot);
        return commandAdmissionService.execute(request, requestId, commandProcessAnchor, worktree, before, () -> {
            try {
                return renewLeaseForCommand(worktree, activeProjectRoot);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Re-arms command admission only after a successful session resolution has verified a different
     * isolated worktree. A provider connection may move from a stale clean worktree to a recovery
     * worktree, while the immutable MCP process identity remains attached to the same connection.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     */
    void refreshCommandProcessAnchorForVerifiedSession(Path activeProjectRoot) {
        if (commandProcessAnchor == null) {
            return;
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            ReadinessResult readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
            if (!readiness.ready()) {
                return;
            }
            PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(activeProjectRoot, readiness.worktree(), new LifecyclePathVerifier());
            if (!commandProcessAnchor.scopeLocator().equals(worktree.locator())) {
                commandProcessAnchor = null;
            }
        } catch (Exception ignored) {
            // Preserve fail-closed command admission when the new scope cannot
            // be independently verified after session resolution.
        }
    }

    /**
     * Renews the connection lease and captures the authority snapshot required for command admission.
     *
     * @param worktree verified physical worktree identity
     * @param activeProjectRoot the connection's currently selected control project root
     * @return authority snapshot taken after the renewal
     * @throws IllegalArgumentException when the lease cannot be renewed or the workspace is no longer ready
     */
    private ProjectCommandAuthoritySnapshot renewLeaseForCommand(PhysicalWorktreeIdentity worktree, Path activeProjectRoot) throws Exception {
        ProjectLocation location = lease.renewLeaseAndHeartbeat(activeProjectRoot);
        ReadinessResult readiness = new WorkspaceReadinessService().assess(location, provider, connectionInstanceId);
        if (!readiness.ready()) {
            throw new IllegalStateException("COMMAND_AUTHORITY_REFRESH_FAILED");
        }
        return captureAuthoritySnapshot(readiness, worktree, activeProjectRoot);
    }

    /**
     * Captures the current readiness, worktree identity, and exact lease for durable command checks.
     *
     * @param readiness current workspace readiness
     * @param worktree  verified physical worktree identity
     * @param activeProjectRoot the connection's currently selected control project root
     * @return snapshot used to fence the command
     */
    private ProjectCommandAuthoritySnapshot captureAuthoritySnapshot(ReadinessResult readiness, PhysicalWorktreeIdentity worktree, Path activeProjectRoot) {
        SessionLeaseRecord lease = new SessionLeaseStore().load(activeProjectRoot, connectionInstanceId).orElse(null);
        return ProjectCommandAuthoritySnapshot.capture(readiness.binding(), worktree, lease, "none");
    }
}
