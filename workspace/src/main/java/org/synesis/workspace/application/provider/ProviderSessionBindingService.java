package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.session.ProviderSessionTerminalizationService;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;

import static org.synesis.workspace.application.provider.ProviderBindingFile.fallbackEvidence;
import static org.synesis.workspace.application.provider.ProviderBindingFile.fingerprint;
import static org.synesis.workspace.application.provider.ProviderBindingFile.read;
import static org.synesis.workspace.application.provider.ProviderBindingFile.requireText;
import static org.synesis.workspace.application.provider.ProviderBindingFile.write;
import static org.synesis.workspace.application.provider.ProviderSessionCoordinationFacts.authorizedRecoveryContinuation;
import static org.synesis.workspace.application.provider.ProviderSessionCoordinationFacts.cleanlyDetached;
import static org.synesis.workspace.application.provider.ProviderSessionCoordinationFacts.hasActiveManagedAttachment;
import static org.synesis.workspace.application.provider.ProviderWorkspaceRefresh.controlAdvancedWithoutUnintegratedWorkerWork;
import static org.synesis.workspace.application.provider.ProviderWorkspaceRefresh.reallocatePreservingSession;
import static org.synesis.workspace.application.provider.ProviderWorkspaceRefresh.refreshClaimedDirtyWorkspace;
import static org.synesis.workspace.application.provider.ProviderWorkspaceRefresh.workspaceGenerationFresh;
import static org.synesis.workspace.application.provider.ProviderWorktreeAllocation.withWorktree;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.currentCommit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.isWorktreeClean;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.sameFilesystemObject;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.verifyBinding;

/**
 * Creates and resumes project-scoped provider session bindings.
 *
 * <p>The binding is local, durable, and deliberately contains no private key,
 * provider credential, or conversation content. Provider-instance evidence is hashed before
 * persistence. An absent provider session identifier uses one project/provider fallback nonce and
 * is reported as a degraded fallback so it is not mistaken for independent chat identity.
 *
 * <p>This class owns the binding lifecycle and its public contract. The durable file format,
 * worktree allocation, Git facts, coordination facts, stale-workspace refresh, and workspace
 * verification are separate package-private classes ({@code ProviderBindingFile},
 * {@code ProviderWorktreeAllocation}, {@code ProviderWorktreeGitFacts},
 * {@code ProviderSessionCoordinationFacts}, {@code ProviderWorkspaceRefresh}, and
 * {@code ProviderWorkspaceVerification}); the public methods that expose them stay here so callers
 * keep one entry point.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ProviderSessionBindingService {

    static final int SCHEMA_VERSION = 2;
    static final String SESSIONS_DIRECTORY = "sessions";

    /**
     * Creates a session binding service.
     */
    public ProviderSessionBindingService() {
    }

    @SuppressWarnings("SameParameterValue")
    static Binding copy(Binding b, String worktreeId, String control, String path, String base, String branch, String creation, String verification, String lastSeen, String trust) {
        return new Binding(SCHEMA_VERSION,
                b.sessionId(),
                b.projectId(),
                b.nodeId(),
                b.provider(),
                b.providerInstanceFingerprint(),
                b.supervisorId(),
                b.workerId(),
                worktreeId,
                path,
                control,
                branch,
                base,
                b.gitCommonDir(),
                creation,
                verification,
                lastSeen,
                b.status(),
                b.createdAtEpochMillis(),
                System.currentTimeMillis(),
                b.lastVerifiedProjectSequence(),
                trust,
                b.bindingVersion(),
                b.completedAt());
    }

    private static Binding newBinding(ProjectLocation location, NodeIdentity identity, String provider, String fingerprint) {
        long now = System.currentTimeMillis();
        return new Binding(SCHEMA_VERSION,
                "session-" + UUID.randomUUID(),
                location.projectId().toString(),
                identity.nodeId(),
                provider,
                fingerprint,
                "supervisor-" + UUID.randomUUID(),
                "worker-" + UUID.randomUUID(),
                null,
                null,
                location.root().toString(),
                null,
                currentCommit(location.root()),
                null,
                "UNASSIGNED",
                "UNVERIFIED",
                "BOOTSTRAPPED",
                "BOUND",
                now,
                now,
                0L,
                "WORKSPACE_UNVERIFIED",
                1,
                null);
    }

    /**
     * Returns whether the exact provider session has an active intent that explicitly requires no
     * repository mutation.
     *
     * @param location initialized project location
     * @param binding  exact provider binding
     * @return whether a matching announced intent permits no-change session readiness
     * @throws Exception when durable coordination state cannot be read
     */
    public boolean hasActiveNoChangeIntent(ProjectLocation location, Binding binding) throws Exception {
        return ProviderSessionCoordinationFacts.hasActiveNoChangeIntent(location, binding);
    }

    /**
     * Ensures one idempotent binding for a provider instance.
     *
     * @param location         initialized project location
     * @param provider         stable provider identifier
     * @param instanceEvidence provider session/conversation key, or {@code null}
     * @return durable binding result
     * @throws BindingException when project identity or local state is invalid
     */
    public synchronized BindingResult ensure(ProjectLocation location, String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            // Runtime exclusions must exist before session-local state or a
            // lane worktree can be created. Git visibility is not ownership of
            // any provider configuration file.
            RepositoryPrivateStateService.ensure(location.root());
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            String evidence = instanceEvidence == null || instanceEvidence.isBlank() ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String fingerprint = fingerprint(evidence);
            Path local = location.synesisDirectory().resolve("local");
            Path sessions = local.resolve(SESSIONS_DIRECTORY);
            Path bindingPath = sessions.resolve(provider + "-" + fingerprint + ".json");
            if (Files.exists(bindingPath)) {
                Binding binding = read(bindingPath);
                if (!binding.projectId().equals(location.projectId().toString()) || !binding.nodeId().equals(identity.nodeId()) || !binding.provider().equals(provider) || !binding.providerInstanceFingerprint()
                        .equals(fingerprint)) {
                    throw new BindingException("SESSION_IDENTITY_MISMATCH",
                            "Stored provider binding does not match this project: " + binding.projectId() + "/" + binding.nodeId() + "/" + binding.provider() + "/" + binding.providerInstanceFingerprint() + " expected "
                                    + location.projectId() + "/" + identity.nodeId() + "/" + provider + "/" + fingerprint);
                }
                if ("TERMINAL".equals(binding.status()) || isSessionTerminal(location, binding.sessionId())) {
                    throw new BindingException("SESSION_TERMINAL", "The exact provider session has an irreversible terminal fence");
                }
                if (cleanlyDetached(location, binding) && !hasActiveManagedAttachment(location, binding)) {
                    Binding rebound = withWorktree(location, newBinding(location, identity, provider, fingerprint)).touch();
                    write(bindingPath, rebound);
                    return new BindingResult(rebound, instanceEvidence == null || instanceEvidence.isBlank());
                }
                if (!binding.status().equals("REVOKED") && !binding.status().equals("COMPLETED") && !binding.status().equals("SUSPENDED") && !binding.status().equals("CANCELLED")) {
                    if (binding.worktreePath() != null && workspaceGenerationFresh(location, binding)) {
                        Binding refreshed = verifyBinding(location.root(), binding).touch();
                        write(bindingPath, refreshed);
                        return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                    }
                    boolean retainExistingWorktree = false;
                    if (binding.worktreePath() != null && !workspaceGenerationFresh(location, binding)) {
                        boolean authorizedContinuation = authorizedRecoveryContinuation(location, binding);
                        boolean worktreeClean = isWorktreeClean(binding);
                        if (!worktreeClean && !authorizedContinuation) {
                            Binding refreshed = refreshClaimedDirtyWorkspace(this, location, binding, bindingPath);
                            return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                        }
                        if (!worktreeClean) {
                            // A session that continued a lane needs what its peers integrate as
                            // much as any other: a requester cannot read or test against a
                            // dependency that exists only on the new control head. The refresh
                            // keeps the session, its claims, and its uncommitted work. Where it
                            // cannot do so safely, the recovered worktree stays as it is, which is
                            // what a continuation always got before.
                            try {
                                Binding refreshed = refreshClaimedDirtyWorkspace(this, location, binding, bindingPath);
                                return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                            } catch (BindingException keepRecoveredWorktree) {
                                // Falls through to retaining the existing worktree.
                            }
                        }
                        boolean activeManagedAttachment = hasActiveManagedAttachment(location, binding);
                        boolean activeNoChangeIntent = hasActiveNoChangeIntent(location, binding);
                        if (controlAdvancedWithoutUnintegratedWorkerWork(location, binding) && !activeManagedAttachment && !activeNoChangeIntent && worktreeClean) {
                            Binding rebound = reallocatePreservingSession(location, binding).touch();
                            write(bindingPath, rebound);
                            return new BindingResult(rebound, instanceEvidence == null || instanceEvidence.isBlank());
                        }
                        if (!activeManagedAttachment && !authorizedContinuation && !activeNoChangeIntent) {
                            binding = newBinding(location, identity, provider, fingerprint);
                        } else {
                            retainExistingWorktree = true;
                        }
                    }
                    Binding refreshed = (retainExistingWorktree ? binding : withWorktree(location, binding)).touch();
                    write(bindingPath, refreshed);
                    return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                }
            }
            Binding binding = newBinding(location, identity, provider, fingerprint);
            binding = withWorktree(location, binding);
            write(bindingPath, binding);
            return new BindingResult(binding, instanceEvidence == null || instanceEvidence.isBlank());
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_FAILED", "Could not establish provider session", failure);
        }
    }

    /**
     * Finds the active provider binding that owns an assigned worktree.
     *
     * @param location initialized control project
     * @param provider stable provider identifier
     * @param worktree assigned worktree path
     * @return matching binding, or empty when the worktree is not bound
     * @throws BindingException when stored bindings are malformed
     */
    public synchronized java.util.Optional<Binding> findByWorktree(ProjectLocation location, String provider, Path worktree) throws BindingException {
        Path normalized = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
        return list(location, provider).stream().filter(binding -> "BOUND".equals(binding.status()) && binding.worktreePath() != null)
                .filter(binding -> sameFilesystemObject(normalized, Path.of(binding.worktreePath()).toAbsolutePath().normalize())).findFirst();
    }

    /**
     * Reports whether the assigned worktree has confirmed user changes that must not be discarded by
     * stale-workspace recovery.
     *
     * @param binding exact provider binding whose worktree is inspected
     * @return {@code true} only when a non-managed uncommitted change is observed
     */
    public boolean hasConfirmedUncommittedWork(Binding binding) {
        return ProviderWorktreeGitFacts.hasConfirmedUncommittedWork(binding);
    }

    /**
     * Loads all valid bindings for one provider.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @return bindings, possibly empty
     * @throws BindingException when a binding file is malformed
     */
    public synchronized java.util.List<Binding> list(ProjectLocation location, String provider) throws BindingException {
        try {
            Path directory = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                return java.util.List.of();
            }
            try (var paths = Files.list(directory)) {
                java.util.List<Binding> result = new java.util.ArrayList<>();
                for (Path path : paths.filter(item -> item.getFileName().toString().startsWith(provider + "-")).toList()) {
                    result.add(read(path));
                }
                return result.stream().sorted(java.util.Comparator.comparing(Binding::createdAtEpochMillis)).toList();
            }
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not read provider sessions", failure);
        }
    }

    /**
     * Resolves the durable binding for one exact provider connection instance.
     *
     * <p>Operations must never fall back to the newest provider binding: a single
     * project can legitimately contain several concurrent workers. The same evidence normalization
     * and fingerprinting used by
     * {@link #ensure(ProjectLocation, String, String)} is therefore applied
     * here.
     *
     * @param location         initialized project location
     * @param provider         stable provider identifier
     * @param instanceEvidence provider connection identity
     * @return the matching binding, or empty when this connection has not been ensured
     * @throws BindingException when the binding is malformed or identity-mismatched
     */
    public synchronized java.util.Optional<Binding> find(ProjectLocation location, String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            String evidence = instanceEvidence == null || instanceEvidence.isBlank() ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String instanceFingerprint = fingerprint(evidence);
            Path bindingPath = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY).resolve(provider + "-" + instanceFingerprint + ".json");
            if (!Files.isRegularFile(bindingPath)) {
                return java.util.Optional.empty();
            }
            Binding binding = read(bindingPath);
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            if (!location.projectId().toString().equals(binding.projectId()) || !identity.nodeId().equals(binding.nodeId()) || !provider.equals(binding.provider())
                    || !instanceFingerprint.equals(binding.providerInstanceFingerprint())) {
                throw new BindingException("SESSION_IDENTITY_MISMATCH", "Stored provider binding does not match this project or connection");
            }
            return java.util.Optional.of(binding);
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not resolve provider connection binding", failure);
        }
    }

    /**
     * Resolves one binding by its persisted provider-instance fingerprint.
     *
     * <p>This path is used only by the provider lifecycle owner when a durable
     * wake association intentionally carries the stored fingerprint rather than raw provider
     * connection evidence. It never falls back to the newest binding and rejects duplicate
     * fingerprints.</p>
     *
     * @param location    initialized control project
     * @param provider    stable provider identifier
     * @param fingerprint exact persisted fingerprint
     * @return matching binding, or empty when absent
     * @throws BindingException when stored bindings are malformed or ambiguous
     */
    public synchronized java.util.Optional<Binding> findByFingerprint(ProjectLocation location, String provider, String fingerprint) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        requireText(fingerprint, "fingerprint");
        java.util.List<Binding> matches = list(location, provider).stream().filter(binding -> fingerprint.equals(binding.providerInstanceFingerprint())).toList();
        if (matches.size() > 1) {
            throw new BindingException("SESSION_BINDING_AMBIGUOUS", "Multiple provider bindings share the same fingerprint");
        }
        return matches.stream().findFirst();
    }

    /**
     * Marks one exact provider connection binding complete while preserving its worktree and audit
     * record.  Resolution is by the caller's connection evidence; no provider-wide or latest-binding
     * fallback is permitted.
     *
     * @param location         initialized control project
     * @param provider         stable provider identifier
     * @param instanceEvidence exact provider connection identity
     * @return {@code true} when the binding was transitioned or was already complete
     * @throws BindingException when the exact binding cannot be read or persisted
     */
    public synchronized boolean complete(ProjectLocation location, String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            String evidence = instanceEvidence == null || instanceEvidence.isBlank() ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String fingerprint = fingerprint(evidence);
            Path bindingPath = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY).resolve(provider + "-" + fingerprint + ".json");
            if (!Files.isRegularFile(bindingPath)) {
                return false;
            }
            Binding binding = read(bindingPath);
            return completeIfIdle(location, binding, bindingPath, null);
        } catch (Exception failure) {
            throw new BindingException("SESSION_COMPLETE_FAILED", "Could not complete provider connection binding", failure);
        }
    }

    /**
     * Marks one exact provider binding complete by its durable session ID.
     *
     * <p>This lookup is used after integration, when the immutable snapshot
     * carries the session identity but the original transient connection evidence is no longer
     * available. It never selects a latest or provider- wide binding.</p>
     *
     * @param location  initialized control project
     * @param provider  stable provider identifier
     * @param sessionId exact durable session identifier
     * @return {@code true} when the binding was transitioned or already complete
     * @throws BindingException when a matching binding is malformed or cannot be persisted
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean completeBySessionId(ProjectLocation location, String provider, String sessionId) throws BindingException {
        return completeBySessionId(location, provider, sessionId, null);
    }

    /**
     * Completes an exact binding under the integration caller's held project append lock.
     *
     * @param location   initialized project
     * @param provider   exact provider
     * @param sessionId  exact session
     * @param appendLock held project lock, or null to acquire it here
     * @return true only when the session has no remaining owned intents and is complete
     * @throws BindingException when state or locking cannot be verified
     */
    public synchronized boolean completeBySessionId(ProjectLocation location, String provider, String sessionId, ProjectAppendLock appendLock) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        requireText(sessionId, "sessionId");
        try {
            Binding binding = list(location, provider).stream().filter(candidate -> sessionId.equals(candidate.sessionId())).findFirst().orElse(null);
            if (binding == null) {
                return false;
            }
            if (!provider.equals(binding.provider())) {
                throw new BindingException("SESSION_IDENTITY_MISMATCH", "Stored provider binding does not match the requested provider");
            }
            Path bindingPath = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY).resolve(provider + "-" + binding.providerInstanceFingerprint() + ".json");
            if (!Files.isRegularFile(bindingPath)) {
                return false;
            }
            return completeIfIdle(location, binding, bindingPath, appendLock);
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_COMPLETE_FAILED", "Could not complete provider connection binding", failure);
        }
    }

    /**
     * Checks fresh ownership for this exact session without transferring or releasing claims.
     *
     * @param location initialized project
     * @param binding  exact authenticated binding
     * @return whether any announced intent still belongs to this binding's participant
     * @throws Exception when authoritative coordination state cannot be read or authenticated
     */
    public boolean hasActiveIntent(ProjectLocation location, Binding binding) throws Exception {
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId()).collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> intent.participant().equals(participant));
    }

    private boolean completeIfIdle(ProjectLocation location, Binding binding, Path bindingPath, ProjectAppendLock appendLock) throws Exception {
        if (appendLock == null) {
            try (ProjectAppendLock acquired = ProjectAppendLock.acquire(location.root().resolve(".synesis/coordination"))) {
                return completeIfIdle(location, binding, bindingPath, acquired);
            }
        }
        if (!appendLock.isHeld()) {
            throw new IOException("event append lock unavailable");
        }
        Binding current = read(bindingPath);
        if (!current.sessionId().equals(binding.sessionId())) {
            throw new IOException("SESSION_IDENTITY_MISMATCH");
        }
        if (hasActiveIntent(location, current)) {
            return false;
        }
        if ("COMPLETED".equals(current.status())) {
            return true;
        }
        if (!"BOUND".equals(current.status()) && !"SUSPENDED".equals(current.status())) {
            return false;
        }
        write(bindingPath, current.complete());
        return true;
    }

    /**
     * Marks one exact provider binding as terminal after its event-log fence commits.
     *
     * @param location  initialized control project
     * @param provider  provider identifier
     * @param sessionId exact durable session identifier
     * @return true when marked or already terminal
     * @throws BindingException when stored state cannot be read or persisted
     */
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean terminalizeBySessionId(ProjectLocation location, String provider, String sessionId) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        requireText(sessionId, "sessionId");
        try {
            Binding binding = list(location, provider).stream().filter(candidate -> sessionId.equals(candidate.sessionId())).findFirst().orElse(null);
            if (binding == null) {
                return false;
            }
            if ("TERMINAL".equals(binding.status())) {
                return true;
            }
            if (!"BOUND".equals(binding.status()) && !"COMPLETED".equals(binding.status())) {
                return false;
            }
            Path bindingPath = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY).resolve(provider + "-" + binding.providerInstanceFingerprint() + ".json");
            if (!Files.isRegularFile(bindingPath)) {
                return false;
            }
            write(bindingPath, binding.terminalize());
            return true;
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_TERMINALIZE_FAILED", "Could not persist provider session terminal marker", failure);
        }
    }

    /**
     * Returns the event-log terminal fence for an exact binding session.
     *
     * @param location  initialized control project
     * @param sessionId exact durable session identifier
     * @return true when terminalized
     * @throws BindingException when the event log is unreadable
     */
    public boolean isSessionTerminal(ProjectLocation location, String sessionId) throws BindingException {
        try {
            return ProviderSessionTerminalizationService.isSessionTerminal(location, sessionId);
        } catch (Exception failure) {
            throw new BindingException("SESSION_TERMINAL_STATE_UNAVAILABLE", "Could not verify provider session terminal state", failure);
        }
    }

    /**
     * Revokes all sessions for a provider while preserving their audit records.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @throws BindingException when a binding cannot be read or persisted
     */
    public synchronized void revoke(ProjectLocation location, String provider) throws BindingException {
        try {
            Path directory = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(item -> item.getFileName().toString().startsWith(provider + "-")).toList()) {
                    Binding binding = read(path);
                    write(path, binding.revoke());
                }
            }
        } catch (Exception failure) {
            throw new BindingException("SESSION_REVOKE_FAILED", "Could not revoke provider sessions", failure);
        }
    }

    /**
     * Determines whether a binding was created from the provider fallback nonce.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @param binding  binding to classify
     * @return {@code true} when the binding fingerprint matches the fallback nonce
     * @throws BindingException when the fallback record cannot be read
     */
    public synchronized boolean isFallbackEvidence(ProjectLocation location, String provider, Binding binding) throws BindingException {
        return ProviderBindingFile.isFallbackEvidence(location, provider, binding);
    }

    /**
     * Verifies that a provider event is executing in its assigned worktree.
     *
     * @param location project location
     * @param binding  session binding
     * @param cwd      provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return ProviderWorkspaceVerification.verifyWorkspace(location, binding, cwd);
    }

    /**
     * Verifies a clean no-change lane without requiring the control checkout to remain at its base.
     *
     * <p>A no-change participant performs no repository mutation. Once another
     * lane has integrated, its clean read-only worktree may therefore have a stale control base while
     * its own binding, branch, generation, and Git ancestry remain verifiable. Ordinary mutation and
     * command callers must continue using
     * {@link #verifyWorkspace(ProjectLocation, Binding, Path)}.
     *
     * @param location project location
     * @param binding  session binding
     * @param cwd      provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyNoChangeWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return ProviderWorkspaceVerification.verifyNoChangeWorkspace(location, binding, cwd);
    }

    /**
     * Verifies a live managed continuation without requiring the control checkout to remain at the
     * binding's admission base.
     *
     * <p>A sibling lane may publish and integrate an immutable snapshot while
     * this managed provider remains attached to its own clean isolated worktree. The exact active
     * attachment is the additional fence that permits that provider to continue; an ordinary or
     * inactive binding must continue to use the strict current-control-base check.</p>
     *
     * @param location project location
     * @param binding  exact provider binding
     * @param cwd      provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyManagedContinuationWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return ProviderWorkspaceVerification.verifyManagedContinuationWorkspace(location, binding, cwd);
    }

    /**
     * Verifies a mutation continuation on the exact assigned worktree when the target remains inside
     * the binding's active collaboration claim.
     *
     * <p>This permits a claimed lane to continue after a sibling integrates its snapshot. It does
     * not permit a changed worker generation, another worktree, or a target outside the active claim.
     * The collaboration service also applies its completion and publication fences.</p>
     *
     * @param location     initialized project location
     * @param binding      exact provider binding
     * @param cwd          provider event working directory
     * @param relativePath claimed mutation target
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyClaimedMutationContinuationWorkspace(ProjectLocation location, Binding binding, Path cwd, String relativePath) {
        return ProviderWorkspaceVerification.verifyClaimedMutationContinuationWorkspace(location, binding, cwd, relativePath);
    }

    /**
     * Verifies a clean producer lane whose HEAD is a committed descendant of its admission base
     * without authorizing ordinary workspace mutation.
     *
     * <p>Completion only needs to inspect and publish the immutable committed
     * delta. It therefore permits the worker HEAD to advance while retaining the exact assigned
     * worktree, branch, Git ancestry, and common repository checks. Ordinary reads, patches, and
     * commands continue to use the strict generation check in
     * {@link #verifyWorkspace(ProjectLocation, Binding, Path)}.</p>
     *
     * @param location project location
     * @param binding  session binding
     * @param cwd      provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyCompletionWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return ProviderWorkspaceVerification.verifyCompletionWorkspace(location, binding, cwd);
    }

    /**
     * Performs strict, idempotent workspace verification inspecting the real filesystem and Git
     * repository state. On success, transitions providerTrustState to VERIFIED and records evidence.
     *
     * @param location  initialized project location
     * @param provider  provider identifier
     * @param sessionId exact session identifier; {@code null} is rejected to prevent cross-chat
     *                  authority
     * @param cwd       provider's declared active working directory
     * @return structured workspace verification result
     */
    public synchronized WorkspaceVerificationResult verifyWorkspaceTrust(ProjectLocation location, String provider, String sessionId, Path cwd) {
        return ProviderWorkspaceVerification.verifyWorkspaceTrust(this, location, provider, sessionId, cwd);
    }

    /**
     * Result of workspace trust verification.
     *
     * @param verified       whether workspace verification succeeded
     * @param code           stable status code
     * @param evidenceDigest evidence SHA-256 digest on success, or {@code null}
     * @param binding        updated session binding, or {@code null}
     */
    public record WorkspaceVerificationResult(boolean verified, String code, String evidenceDigest, Binding binding) {

        /**
         * Validates result shape.
         */
        public WorkspaceVerificationResult {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * Immutable durable provider session binding.
     *
     * @param schemaVersion               record schema version
     * @param sessionId                   session identity
     * @param projectId                   project identity
     * @param nodeId                      project node identity
     * @param provider                    provider identifier
     * @param providerInstanceFingerprint hashed provider-instance evidence
     * @param supervisorId                supervisor actor identity
     * @param workerId                    worker actor identity
     * @param worktreeId                  assigned worktree identity, when allocated
     * @param worktreePath                assigned worktree path, when allocated
     * @param controlCheckoutPath         canonical control checkout path
     * @param branch                      assigned branch, when allocated
     * @param baseCommit                  Git base commit
     * @param gitCommonDir                Git common directory identity
     * @param creationState               worktree creation state
     * @param verificationState           worktree verification state
     * @param lastSeenState               last-seen binding state
     * @param status                      lifecycle status
     * @param createdAtEpochMillis        creation timestamp
     * @param lastSeenEpochMillis         last-seen timestamp
     * @param lastVerifiedProjectSequence last verified project sequence
     * @param providerTrustState          provider trust state
     * @param bindingVersion              binding version
     * @param completedAt                 terminal timestamp, when complete
     */
    public record Binding(int schemaVersion, String sessionId, String projectId, String nodeId, String provider, String providerInstanceFingerprint, String supervisorId, String workerId, String worktreeId,
                          String worktreePath, String controlCheckoutPath, String branch, String baseCommit, String gitCommonDir, String creationState, String verificationState, String lastSeenState, String status,
                          long createdAtEpochMillis, long lastSeenEpochMillis, long lastVerifiedProjectSequence, String providerTrustState, int bindingVersion, String completedAt) {

        /**
         * Returns a refreshed binding with an updated last-seen timestamp.
         *
         * @return refreshed binding
         */
        public Binding touch() {
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    lastSeenState,
                    status,
                    createdAtEpochMillis,
                    System.currentTimeMillis(),
                    lastVerifiedProjectSequence,
                    providerTrustState,
                    bindingVersion,
                    completedAt);
        }

        /**
         * Returns a terminal revoked binding.
         *
         * @return revoked binding
         */
        public Binding revoke() {
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    "REVOKED",
                    "REVOKED",
                    createdAtEpochMillis,
                    System.currentTimeMillis(),
                    lastVerifiedProjectSequence,
                    "REVOKED",
                    bindingVersion,
                    Long.toString(System.currentTimeMillis()));
        }

        /**
         * Returns a terminal completed binding while preserving its worktree.
         *
         * @return completed binding
         */
        public Binding complete() {
            long completed = System.currentTimeMillis();
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    "COMPLETED",
                    "COMPLETED",
                    createdAtEpochMillis,
                    completed,
                    lastVerifiedProjectSequence,
                    providerTrustState,
                    bindingVersion,
                    Long.toString(completed));
        }

        /**
         * Returns an irreversible terminal binding marker while preserving audit fields.
         *
         * @return terminal binding
         */
        public Binding terminalize() {
            long terminalized = System.currentTimeMillis();
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    "TERMINAL",
                    "TERMINAL",
                    createdAtEpochMillis,
                    terminalized,
                    lastVerifiedProjectSequence,
                    providerTrustState,
                    bindingVersion,
                    Long.toString(terminalized));
        }
    }

    /**
     * Result of automatic provider bootstrap.
     *
     * @param binding          durable binding
     * @param fallbackEvidence whether the provider key was unavailable
     */
    public record BindingResult(Binding binding, boolean fallbackEvidence) {

        /**
         * Validates the binding result.
         */
        public BindingResult {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * Workspace verification result.
     *
     * @param verified whether cwd is the assigned worktree
     * @param code     stable result code
     */
    public record WorkspaceCheck(boolean verified, String code) {

    }

    /**
     * Stable automatic binding failure.
     */
    public static final class BindingException extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;
        /**
         * Stable machine-readable failure code.
         */
        private final String code;

        /**
         * Creates a binding failure.
         *
         * @param code    stable code
         * @param message safe message
         */
        public BindingException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Creates a binding failure with an internal cause.
         *
         * @param code    stable code
         * @param message safe message
         * @param cause   cause
         */
        public BindingException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /**
         * Returns the stable failure code.
         *
         * @return code
         */
        public String code() {
            return code;
        }
    }
}
