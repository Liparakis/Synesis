package org.synesis.workspace.application.project;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.lifecycle.command.CommandFormatException;
import org.synesis.workspace.lifecycle.command.PhysicalWorktreeIdentity;
import org.synesis.workspace.lifecycle.command.ProjectCommandAuthoritySnapshot;
import org.synesis.workspace.lifecycle.command.ProjectCommandCanonicalizer;
import org.synesis.workspace.lifecycle.command.ProjectCommandNamespace;
import org.synesis.workspace.lifecycle.command.ProjectCommandPhase;
import org.synesis.workspace.lifecycle.command.ProjectCommandProcessAnchor;
import org.synesis.workspace.lifecycle.command.ProjectCommandProtectionService;
import org.synesis.workspace.lifecycle.command.ProjectCommandRecord;
import org.synesis.workspace.lifecycle.command.ProjectCommandStore;
import org.synesis.workspace.lifecycle.command.ProjectCommandTerminalResolution;

/** Implements typed-request replay and the durable release/reacquire admission protocol. */
public final class ProjectCommandAdmissionService {

    /** Maximum request IDs retained by one live process anchor. */
    public static final int MAX_REQUEST_IDS_PER_LIVE_ANCHOR = 8_192;

    private final ProjectCommandService commandService;
    private final ProjectCommandProtectionService protectionService;
    private final ProjectCommandStore store;
    private final java.nio.file.Path namespaceRoot;

    /** Creates an admission service for the supplied command collaborators.
     * @param commandService direct command executor
     * @param namespaceRoot host-wide command namespace root
     */
    public ProjectCommandAdmissionService(ProjectCommandService commandService,
            java.nio.file.Path namespaceRoot) {
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.namespaceRoot = Objects.requireNonNull(namespaceRoot, "namespaceRoot")
                .toAbsolutePath().normalize();
        this.protectionService = new ProjectCommandProtectionService(this.namespaceRoot);
        this.store = new ProjectCommandStore(this.namespaceRoot);
    }

    /** Supplies the exact post-renewal authority snapshot for one new request. */
    @FunctionalInterface
    public interface LeaseRenewal {
        /** Renews the existing lease and returns the post-renewal authority snapshot.
         * @return post-renewal authority snapshot
         * @throws Exception if renewal or authority capture fails
         */
        ProjectCommandAuthoritySnapshot renew() throws Exception;
    }

    /** Executes or replays one bounded command request under durable admission.
     * @param request direct command request
     * @param typedRequestId original typed JSON-RPC request ID
     * @param anchor exact MCP process anchor
     * @param worktree verified physical-worktree identity
     * @param before pre-renewal authority snapshot
     * @param renewal lease renewal and post-renewal snapshot callback
     * @return bounded agent response
     */
    public AgentResponse execute(ProjectCommandService.CommandRequest request, Object typedRequestId,
            ProjectCommandProcessAnchor anchor, PhysicalWorktreeIdentity worktree,
            ProjectCommandAuthoritySnapshot before, LeaseRenewal renewal) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(renewal, "renewal");

        String requestId;
        String requestDigest;
        String semanticDigest;
        try {
            requestId = ProjectCommandCanonicalizer.requestId(typedRequestId);
            requestDigest = ProjectCommandCanonicalizer.requestDigest(
                    request.argv(), request.workingDirectory(), request.timeoutSeconds());
            semanticDigest = ProjectCommandCanonicalizer.semanticDigest(requestDigest,
                    request.provider(), request.connectionInstanceId(), worktree.locator());
        } catch (RuntimeException failure) {
            return blocked(AgentReason.INVALID_PATH, "COMMAND_REQUEST_CANONICALIZATION_FAILED");
        }
        if (!anchor.scopeLocator().equals(worktree.locator())) {
            return blocked(AgentReason.COMMAND_ADMISSION_STALE, "WORKTREE_SCOPE_MISMATCH");
        }

        try {
            Optional<ProjectCommandRecord> existing = store.find(worktree.locator(), anchor.anchorId(), requestId);
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), requestDigest, semanticDigest);
            }
        } catch (CommandFormatException formatFailure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, formatFailure.getMessage());
        } catch (IOException failure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, failure.getMessage());
        }

        try (ProjectCommandProtectionService.ProtectionPermit permit = protectionService.acquire(worktree)) {
            if (!permit.isHeld()) {
                return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, "COMMAND_PROTECTION_UNAVAILABLE");
            }
            persistScopeAndAnchor(anchor, worktree);
            Optional<ProjectCommandRecord> existing = store.find(worktree.locator(), anchor.anchorId(), requestId);
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), requestDigest, semanticDigest);
            }
        } catch (CommandFormatException formatFailure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, formatFailure.getMessage());
        } catch (IOException failure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, failure.getMessage());
        }

        ProjectCommandAuthoritySnapshot after;
        try {
            after = renewal.renew();
        } catch (Exception failure) {
            return blocked(AgentReason.COMMAND_ADMISSION_STALE, "LEASE_RENEWAL_FAILED");
        }
        if (!before.sameAuthorityExceptLease(after)) {
            return blocked(AgentReason.COMMAND_ADMISSION_STALE, "AUTHORITY_CHANGED_DURING_LEASE_GAP");
        }

        ProjectCommandRecord starting;
        try (ProjectCommandProtectionService.ProtectionPermit permit = protectionService.acquire(worktree)) {
            if (!permit.isHeld()) {
                return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, "COMMAND_PROTECTION_UNAVAILABLE");
            }
            Optional<ProjectCommandRecord> raced = store.find(worktree.locator(), anchor.anchorId(), requestId);
            if (raced.isPresent()) {
                return blocked(AgentReason.COMMAND_AMBIGUOUS, "REQUEST_APPEARED_DURING_ADMISSION_GAP");
            }
            if (store.countForAnchor(worktree.locator(), anchor.anchorId()) >= MAX_REQUEST_IDS_PER_LIVE_ANCHOR) {
                return blocked(AgentReason.COMMAND_CAPACITY_EXCEEDED, "LIVE_PROCESS_REQUEST_CAPACITY");
            }
            long now = System.currentTimeMillis();
            starting = new ProjectCommandRecord(anchor.anchorId(), worktree.locator(), requestId,
                    requestDigest, semanticDigest, ProjectCommandPhase.STARTING, null, false, null,
                    false, false, null, 1L, now, now, Map.of(), Map.of());
            store.save(starting);
        } catch (CommandFormatException formatFailure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, formatFailure.getMessage());
        } catch (IOException failure) {
            return blocked(AgentReason.COMMAND_FORMAT_UNSUPPORTED, failure.getMessage());
        }

        AtomicLong revision = new AtomicLong(1L);
        try {
            AgentResponse response = commandService.runCommand(request, started -> {
                try (ProjectCommandProtectionService.ProtectionPermit permit = protectionService.acquire(worktree)) {
                    if (!permit.isHeld()) {
                        throw new IOException("COMMAND_PROTECTION_UNAVAILABLE");
                    }
                    long now = System.currentTimeMillis();
                    ProjectCommandRecord running = new ProjectCommandRecord(
                            starting.anchorId(), starting.scopeLocator(), starting.requestId(),
                            starting.requestDigest(), starting.semanticDigest(), ProjectCommandPhase.RUNNING,
                            null, false, null, false, false, null, revision.incrementAndGet(),
                            starting.createdAtEpochMillis(), now, Map.of(), Map.of(
                                    "pid", started.pid(), "executableIdentity", started.executableIdentity(),
                                    "commandLine", started.commandLine(), "processStartTime", started.processStartTime()));
                    store.save(running);
                    revision.set(2L);
                } catch (IOException failure) {
                    throw new IllegalStateException("COMMAND_RUNNING_PERSISTENCE_FAILED", failure);
                }
            });
            Map<String, Object> result = response.toMap();
            Map<?, ?> executionResult = result.get("result") instanceof Map<?, ?> map ? map : Map.of();
            boolean stdoutComplete = !Boolean.TRUE.equals(executionResult.get("stdoutTruncated"));
            boolean stderrComplete = !Boolean.TRUE.equals(executionResult.get("stderrTruncated"));
            Integer exitCode = executionResult.get("exitCode") instanceof Number number
                    ? number.intValue() : null;
            ProjectCommandRecord terminal = new ProjectCommandRecord(
                    starting.anchorId(), starting.scopeLocator(), starting.requestId(),
                    starting.requestDigest(), starting.semanticDigest(), ProjectCommandPhase.TERMINAL,
                    ProjectCommandTerminalResolution.OBSERVED_COMMAND_TERMINAL, true, exitCode,
                    stdoutComplete, stderrComplete, null, revision.incrementAndGet(), starting.createdAtEpochMillis(),
                    System.currentTimeMillis(), result, Map.of());
            try (ProjectCommandProtectionService.ProtectionPermit permit = protectionService.acquire(worktree)) {
                if (!permit.isHeld()) {
                    throw new IOException("COMMAND_PROTECTION_UNAVAILABLE");
                }
                store.save(terminal);
            }
            return response;
        } catch (RuntimeException | IOException failure) {
            try (ProjectCommandProtectionService.ProtectionPermit permit = protectionService.acquire(worktree)) {
                if (!permit.isHeld()) {
                    throw new IOException("COMMAND_PROTECTION_UNAVAILABLE");
                }
                ProjectCommandRecord ambiguous = new ProjectCommandRecord(
                        starting.anchorId(), starting.scopeLocator(), starting.requestId(),
                        starting.requestDigest(), starting.semanticDigest(), ProjectCommandPhase.AMBIGUOUS,
                        null, false, null, false, false, null, revision.get() >= 3L ? 3L : 2L,
                        starting.createdAtEpochMillis(), System.currentTimeMillis(), Map.of(), Map.of());
                store.save(ambiguous);
            } catch (IOException ignored) {
                // The original failure remains the blocking diagnostic.
            }
            return blocked(AgentReason.COMMAND_AMBIGUOUS, "COMMAND_OUTCOME_UNRESOLVED");
        }
    }

    private void persistScopeAndAnchor(ProjectCommandProcessAnchor anchor,
            PhysicalWorktreeIdentity worktree) throws IOException {
        try (ProjectCommandNamespace namespace = ProjectCommandNamespace.open(namespaceRoot)) {
            namespace.publishScope(worktree);
            namespace.writeAnchor(anchor);
        }
    }

    private static AgentResponse resolveExisting(ProjectCommandRecord record,
            String requestDigest, String semanticDigest) {
        if (!record.requestDigest().equals(requestDigest) || !record.semanticDigest().equals(semanticDigest)) {
            return blocked(AgentReason.COMMAND_IDEMPOTENCY_CONFLICT, "REQUEST_DIGEST_MISMATCH");
        }
        if (record.phase() == ProjectCommandPhase.TERMINAL) {
            return AgentResponse.fromMap(record.response());
        }
        return blocked(AgentReason.COMMAND_AMBIGUOUS, "COMMAND_PHASE_" + record.phase().name());
    }

    private static AgentResponse blocked(AgentReason reason, String diagnostic) {
        return new AgentResponse(AgentStatus.BLOCKED, reason, AgentNextAction.REQUEST_HUMAN_HELP,
                Map.of("error", diagnostic == null ? "COMMAND_ADMISSION_FAILED" : diagnostic));
    }
}
