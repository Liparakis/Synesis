package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ProviderSessionTerminalPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.PhysicalWorktreeIdentity;
import org.synesis.workspace.lifecycle.command.ProjectCommandPhase;
import org.synesis.workspace.lifecycle.command.ProjectCommandStore;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;

/** Commits an exact provider-session terminal fence after a fail-closed proof. */
public final class ProviderSessionTerminalizationService {

    /** Public result states returned to the provider-facing completion flow. */
    public enum Outcome {
        /** The exact session fence is durable and irreversible. */
        SESSION_TERMINATED,
        /** At least one exact-session authority obligation remains. */
        SESSION_TERMINATION_BLOCKED
    }

    /** Bounded result of a terminal-session request.
     * @param outcome terminal request outcome
     * @param blockers stable blocker categories, empty on success
     * @param eventSequence terminal event sequence, or {@code -1} when blocked
     */
    public record SealResult(Outcome outcome, List<String> blockers, long eventSequence) {
        /** Validates and freezes the result payload. */
        public SealResult {
            Objects.requireNonNull(outcome, "outcome");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        }
    }

    private final ProviderSessionBindingService bindingService;
    private final SessionLeaseService leaseService;

    /** Creates a terminalization service with default lifecycle stores. */
    public ProviderSessionTerminalizationService() {
        this(new ProviderSessionBindingService(), new SessionLeaseService());
    }

    /** Creates a terminalization service with explicit lifecycle collaborators.
     * @param bindingService provider binding service
     * @param leaseService session lease service
     */
    public ProviderSessionTerminalizationService(ProviderSessionBindingService bindingService,
            SessionLeaseService leaseService) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
    }

    /** Attempts to terminalize one exact provider session under the project append lock.
     * @param location initialized project location
     * @param binding exact durable binding selected by the caller
     * @param connectionInstanceId exact provider connection identity
     * @param signer authenticated event signer
     * @param reason bounded terminal request reason
     * @return durable success or bounded refusal
     * @throws Exception when durable state cannot be read or appended
     */
    public SealResult seal(ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding, String connectionInstanceId,
            NodeIdentity signer, String reason) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        Objects.requireNonNull(signer, "signer");
        String terminalReason = reason == null || reason.isBlank() ? "explicit_terminal" : reason.trim();
        if (terminalReason.length() > 256) {
            throw new IllegalArgumentException("terminal reason exceeds 256 characters");
        }
        Path coordinationRoot = location.root().resolve(".synesis/coordination");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(coordinationRoot)) {
            if (!lock.isHeld()) throw new IOException("event append lock unavailable");
            PredictionEventStore store = new PredictionEventStore(coordinationRoot, location.projectId());
            String requestedSessionId = binding.sessionId();
            ProviderSessionBindingService.Binding durableBinding = bindingService.list(location, binding.provider()).stream()
                    .filter(candidate -> candidate.sessionId().equals(requestedSessionId))
                    .findFirst().orElseThrow(() -> new IOException("SESSION_BINDING_NOT_FOUND"));
            if (!location.projectId().toString().equals(durableBinding.projectId())
                    || !binding.provider().equals(durableBinding.provider())
                    || !binding.providerInstanceFingerprint().equals(durableBinding.providerInstanceFingerprint())
                    || (!"BOUND".equals(durableBinding.status()) && !"COMPLETED".equals(durableBinding.status())
                    && !"TERMINAL".equals(durableBinding.status()))) {
                throw new IOException("SESSION_BINDING_IDENTITY_MISMATCH");
            }
            binding = durableBinding;
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            PredictionEvent existing = terminalEvent(store, binding.sessionId(), binding.provider(), participant);
            if (existing != null) {
                synchronizeTerminalMarkers(location, binding, connectionInstanceId);
                return new SealResult(Outcome.SESSION_TERMINATED, List.of(), existing.sequence());
            }
            Set<String> blockers = terminalBlockers(store, binding, participant);
            if (!blockers.isEmpty()) {
                return new SealResult(Outcome.SESSION_TERMINATION_BLOCKED, new ArrayList<>(blockers), -1L);
            }
            ProviderSessionTerminalPayload payload = new ProviderSessionTerminalPayload(
                    binding.sessionId(), binding.provider(), participant, terminalReason, store.headSequence());
            PredictionEvent terminal = store.append(java.util.UUID.randomUUID(),
                    PredictionEventType.PROVIDER_SESSION_TERMINALIZED, signer.nodeId(), payload.encode(), signer);
            synchronizeTerminalMarkers(location, binding, connectionInstanceId);
            return new SealResult(Outcome.SESSION_TERMINATED, List.of(), terminal.sequence());
        }
    }

    /** Returns whether the event log contains a terminal fence for one session.
     * @param location initialized project location
     * @param sessionId exact provider session identity
     * @return true when fenced
     * @throws Exception when the event log is unreadable
     */
    public static boolean isSessionTerminal(ProjectApplicationService.ProjectLocation location,
            String sessionId) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(sessionId, "sessionId");
        Path events = location.root().resolve(".synesis/coordination/events");
        if (!Files.isDirectory(events)) return false;
        PredictionEventStore store = new PredictionEventStore(events.getParent(), location.projectId());
        return store.collaborationProjection().isSessionTerminal(sessionId);
    }

    private void synchronizeTerminalMarkers(ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding, String connectionInstanceId) {
        // The event is the authoritative fence. These local markers are denormalized
        // accelerators and may be repaired by an idempotent retry after a partial write.
        try {
            bindingService.terminalizeBySessionId(location, binding.provider(), binding.sessionId());
        } catch (Exception ignored) {
            // Resolver and ensure_session still consult the event fence.
        }
        leaseService.markTerminalAuthorityConfirmed(location.root(), connectionInstanceId);
    }

    private static PredictionEvent terminalEvent(PredictionEventStore store, String sessionId,
            String provider, String participant) throws IOException {
        return store.events().stream()
                .filter(event -> event.type() == PredictionEventType.PROVIDER_SESSION_TERMINALIZED)
                .filter(event -> {
                    try {
                        ProviderSessionTerminalPayload payload = ProviderSessionTerminalPayload.decode(event.payload());
                        return payload.sessionId().equals(sessionId)
                                && payload.provider().equals(provider)
                                && payload.participant().equals(participant);
                    } catch (IOException malformed) {
                        return false;
                    }
                })
                .findFirst().orElse(null);
    }

    private static Set<String> terminalBlockers(PredictionEventStore store,
            ProviderSessionBindingService.Binding binding, String participant) throws IOException {
        Set<String> blockers = new LinkedHashSet<>();
        List<WorkIntent> intents = store.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(participant)).toList();
        if (!intents.isEmpty()) {
            blockers.add("ACTIVE_INTENT");
            if (intents.stream().anyMatch(intent -> !intent.selectors().isEmpty())) blockers.add("ACTIVE_CLAIM");
        }
        Set<java.util.UUID> intentIds = intents.stream().map(WorkIntent::intentId).collect(java.util.stream.Collectors.toSet());
        for (CoordinationRequest request : store.collaborationProjection().requests()) {
            if (request.status() == CoordinationRequest.Status.PENDING
                    && (request.requester().equals(participant) || request.target().equals(participant)
                    || intentIds.contains(request.conflictingIntentId()))) {
                blockers.add("PENDING_DEPENDENCY");
            }
        }
        for (LaneGrant grant : store.workGroupProjection().grants()) {
            boolean exact = grant.targetParticipant().equals(participant) || intentIds.contains(grant.targetIntentId());
            if (exact && (store.workGroupProjection().grantAvailable(grant.grantId())
                    || (store.workGroupProjection().grantConsumed(grant.grantId())
                    && store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isEmpty()))) {
                blockers.add("PENDING_REVIEW");
            }
        }
        for (var snapshot : store.taskCompletionProjection().allSnapshots()) {
            boolean exact = snapshot.providerSessionId().equals(binding.sessionId())
                    || snapshot.provenance().bindingIdentity().equals(binding.sessionId())
                    || snapshot.provenance().participant().equals(participant);
            if (!exact) continue;
            TaskCompletionState state = store.taskCompletionProjection().snapshotState(snapshot.snapshotId())
                    .orElse(TaskCompletionState.ACTIVE);
            if (state == TaskCompletionState.REVIEW_PENDING) blockers.add("PENDING_REVIEW");
            else if (state == TaskCompletionState.REVIEW_REJECTED) blockers.add("REJECTED_SNAPSHOT_CONTINUATION");
            else if (state != TaskCompletionState.INTEGRATED) blockers.add("UNRESOLVED_SNAPSHOT");
        }
        for (var prepared : store.taskCompletionProjection().allPrepared()) {
            if (intentIds.contains(prepared.laneId())) blockers.add("ACTIVE_MUTATION_AUTHORITY");
        }
        for (CapabilityRequestRecord request : store.capabilityRequestProjection().records().values()) {
            boolean exactActor = request.matchesRequester(binding.nodeId(), binding.supervisorId(), binding.workerId())
                    || request.matchesOwner(binding.nodeId(), binding.supervisorId(), binding.workerId());
            if (exactActor && isPending(request.state())) blockers.add("PENDING_DEPENDENCY");
        }
        if (binding.worktreePath() != null && Files.isDirectory(Path.of(binding.worktreePath()))) {
            try {
                PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(Path.of(binding.worktreePath()));
                ProjectCommandStore commands = new ProjectCommandStore(
                        AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
                if (commands.hasBlockingRecords(worktree)) blockers.add("PENDING_COMMAND");
            } catch (IOException commandStateUnavailable) {
                blockers.add("PENDING_COMMAND");
            }
        }
        var participantState = store.collaborationProjection().participantState(participant).orElse(null);
        if (participantState != null && participantState != org.synesis.coordination.domain.collaboration.Participant.State.COMPLETED
                && participantState != org.synesis.coordination.domain.collaboration.Participant.State.CANCELLED
                && participantState != org.synesis.coordination.domain.collaboration.Participant.State.REVOKED
                && participantState != org.synesis.coordination.domain.collaboration.Participant.State.DETACHED) {
            blockers.add("RECOVERABLE_PARTICIPANT_AUTHORITY");
        }
        return blockers;
    }

    private static boolean isPending(CapabilityLifecycleState state) {
        return state == CapabilityLifecycleState.AWAITING_OWNER
                || state == CapabilityLifecycleState.REVISION_REQUESTED
                || state == CapabilityLifecycleState.ACCEPTED
                || state == CapabilityLifecycleState.IMPLEMENTING
                || state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                || state == CapabilityLifecycleState.VALIDATING;
    }
}
