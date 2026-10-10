package org.synesis.workspace.application.workspace.mutation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.workspace.WorkspacePathPolicy;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.filesystem.BoundedFileLock;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.project.ActionGuardrail;
import org.synesis.workspace.project.ProjectPathResolver;

/**
 * Broker enforcing workspace mutation invariants across providers.
 *
 * <p>No workspace mutation may occur unless:
 * <ul>
 *   <li>Session is bound</li>
 *   <li>Assigned worktree is verified</li>
 *   <li>Workspace trust is {@code VERIFIED}</li>
 *   <li>Synesis evaluates the exact proposed mutation with {@code HOOK_INTERCEPTED=true}</li>
 *   <li>Decision is {@code ALLOW}</li>
 * </ul>
 *
 * @since 1.0
 */
public final class WorkspaceMutationBroker {

    private static final Duration MUTATION_LOCK_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Creates a workspace mutation broker.
     */
    public WorkspaceMutationBroker() {
    }

    private static String computeEvidence(MutationRequest request) {
        String payload = request.relativePath() + "\n" + request.toolName() + "\n"
                + (request.newContent() == null ? "" : request.newContent()) + "\n"
                + (request.expectedPreviousHash() == null ? "" : request.expectedPreviousHash())
                + "\n" + request.createOnly();
        return Sha256.hex(payload);
    }

    private static void recordEvidence(ProjectLocation location,
            String provider,
            ProviderSessionBindingService.Binding binding,
            String decisionId,
            String evidenceHash,
            String relativePath,
            Decision decision,
            String reasonCode,
            String message) throws IOException {
        Path evidenceDir = location.synesisDirectory().resolve("local").resolve("evidence").resolve(provider);
        Files.createDirectories(evidenceDir);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("decisionId", decisionId);
        record.put("sessionId", binding != null ? binding.sessionId() : "UNBOUND");
        record.put("provider", provider);
        record.put("interceptionEvidence", evidenceHash);
        record.put("relativePath", relativePath);
        record.put("hookIntercepted", true);
        record.put("decision", decision.name());
        record.put("reasonCode", reasonCode);
        record.put("message", message);
        record.put("timestamp", System.currentTimeMillis());
        Path file = evidenceDir.resolve(decisionId + ".json");
        atomicWrite(file, (ProviderJson.write(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Evaluates and applies a workspace mutation request enforcing all workspace invariants.
     *
     * <p>The first failing check decides the recorded decision, so the stages run in a fixed
     * order: authorize the caller, validate the target, then write under the worktree lock.</p>
     *
     * @param request workspace mutation request
     * @return mutation result
     */
    public synchronized MutationResult applyMutation(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        Attempt attempt = new Attempt(request, "dec-" + UUID.randomUUID(), computeEvidence(request));

        Authorization authorization = authorize(request);
        if (authorization.rejection() != null) {
            return attempt.reject(authorization.rejection(), authorization.binding());
        }
        ProviderSessionBindingService.Binding binding = authorization.binding();
        Path assignedWorktree = authorization.worktree();

        Staged<Target> validated = validateTarget(request, assignedWorktree);
        if (validated.rejection() != null) {
            return attempt.reject(validated.rejection(), binding);
        }
        return writeUnderLock(attempt, binding, assignedWorktree, validated.value());
    }

    private static Authorization authorize(MutationRequest request) {
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);
        ProviderSessionBindingService.Binding binding;
        try {
            if (request.connectionInstanceId() == null || request.connectionInstanceId().isBlank()) {
                return Authorization.rejected(null, Decision.SESSION_UNBOUND, "EXACT_SESSION_REQUIRED",
                        "An exact provider connection is required for mutation authority");
            }
            binding = authorityResolver.resolve(request.location(), request.provider(), request.connectionInstanceId());
            if (!"BOUND".equals(binding.status())) {
                return Authorization.rejected(binding, Decision.SESSION_UNBOUND, "SESSION_NOT_BOUND",
                        "Provider session status is " + binding.status() + ", expected BOUND");
            }
        } catch (Exception failure) {
            return Authorization.rejected(null, Decision.SESSION_UNBOUND, "SESSION_LOAD_FAILED",
                    "Could not load provider session binding: " + failure.getMessage());
        }

        if (binding.worktreePath() == null || binding.worktreePath().isBlank()) {
            return Authorization.rejected(binding, Decision.WORKSPACE_UNVERIFIED, "WORKTREE_PATH_MISSING",
                    "Assigned worktree path is missing");
        }
        Path assignedWorktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
        ProviderSessionBindingService.WorkspaceCheck workspaceCheck = bindingService.continueAfterControlBaseAdvance(
                request.location(), binding, assignedWorktree,
                bindingService.verifyWorkspace(request.location(), binding, assignedWorktree),
                request.relativePath());
        if (!workspaceCheck.verified()) {
            return Authorization.rejected(binding, Decision.WORKSPACE_UNVERIFIED, workspaceCheck.code(),
                    "Assigned worktree verification failed: " + workspaceCheck.code());
        }

        if (!"VERIFIED".equals(binding.providerTrustState())) {
            return Authorization.rejected(binding, Decision.WORKSPACE_UNVERIFIED, "WORKSPACE_NOT_VERIFIED",
                    "Workspace trust state is " + binding.providerTrustState() + ", expected VERIFIED");
        }
        if (!request.hookIntercepted()) {
            return Authorization.rejected(binding, Decision.INTERCEPTION_MISSING, "HOOK_NOT_INTERCEPTED",
                    "Missing interception: HOOK_INTERCEPTED is false");
        }
        if (request.isSyntheticCheck()) {
            return Authorization.rejected(binding, Decision.INTERCEPTION_MISSING, "SYNTHETIC_CHECK_REJECTED",
                    "Synthetic hook execution does not count as real interception");
        }
        return new Authorization(binding, assignedWorktree, null);
    }

    private static Staged<Target> validateTarget(MutationRequest request, Path assignedWorktree) {
        if (request.createOnly() && request.expectedPreviousHash() != null) {
            return Staged.rejected(Decision.INVALID_TARGET, "CONFLICTING_PRECONDITIONS",
                    "Create-only and previous-hash conditions cannot be combined");
        }
        if (request.expectedPreviousHash() != null
                && !request.expectedPreviousHash().matches("(?i)[0-9a-f]{64}")) {
            return Staged.rejected(Decision.INVALID_TARGET, "INVALID_PREVIOUS_HASH",
                    "Previous content hash must be a SHA-256 value");
        }

        if (WorkspacePathPolicy.isAbsolute(request.relativePath())) {
            return Staged.rejected(Decision.INVALID_TARGET, "ABSOLUTE_PATH_REJECTED",
                    "Absolute target paths are rejected: " + request.relativePath());
        }
        if (WorkspacePathPolicy.containsTraversal(request.relativePath())) {
            return Staged.rejected(Decision.INVALID_TARGET, "PATH_TRAVERSAL_REJECTED",
                    "Path traversal rejected: " + request.relativePath());
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, request.relativePath());
        } catch (Exception failure) {
            return Staged.rejected(Decision.INVALID_TARGET, "INVALID_PATH_FORMAT",
                    "Invalid target relative path: " + failure.getMessage());
        }
        if (WorkspacePathPolicy.isProtectedTarget(resolvedRelative)) {
            return Staged.rejected(Decision.DENY_POLICY, "PROTECTED_CONFIGURATION_TARGET",
                    "Target path is a protected configuration target: " + resolvedRelative);
        }

        ActionGuardrail.Request guardrailReq = new ActionGuardrail.Request(assignedWorktree, resolvedRelative, request.toolName(), "Workspace mutation");
        ActionGuardrail.Response guardrailResp = ActionGuardrail.evaluate(request.location().profile(), guardrailReq);
        if (guardrailResp.outcome() == ActionGuardrail.Outcome.BLOCKED) {
            return Staged.rejected(Decision.DENY_POLICY, "POLICY_BLOCKED", guardrailResp.message());
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.REQUEST_OWNER) {
            return Staged.rejected(Decision.REQUEST_OWNER, "REQUEST_OWNER", guardrailResp.message());
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.INVALID_INPUT || guardrailResp.outcome() == ActionGuardrail.Outcome.UNSUPPORTED) {
            return Staged.rejected(Decision.INVALID_TARGET, "INVALID_GUARDRAIL_INPUT", guardrailResp.message());
        }

        Path targetFile = assignedWorktree.resolve(resolvedRelative).toAbsolutePath().normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return Staged.rejected(Decision.INVALID_TARGET, "PATH_ESCAPE_DETECTED",
                    "Path escape detected outside assigned worktree");
        }
        return Staged.of(new Target(resolvedRelative, targetFile));
    }

    private MutationResult writeUnderLock(Attempt attempt, ProviderSessionBindingService.Binding binding,
            Path assignedWorktree, Target target) {
        MutationLockLocation lockLocation;
        try {
            lockLocation = mutationLockLocation(attempt.request().location(), assignedWorktree);
        } catch (IOException unavailable) {
            return attempt.reject(LOCK_FAILED, binding);
        }
        try (BoundedFileLock lock = BoundedFileLock.acquire(lockLocation.lockFile(),
                MUTATION_LOCK_TIMEOUT)) {
            if (!lock.isHeld() || !assignedWorktree.toRealPath().equals(lockLocation.worktree())) {
                return attempt.reject(LOCK_FAILED, binding);
            }
            return applyTargetMutation(attempt, binding, lockLocation.worktree(), target);
        } catch (IOException unavailable) {
            return attempt.reject(LOCK_FAILED, binding);
        }
    }

    private static MutationLockLocation mutationLockLocation(ProjectLocation location,
            Path assignedWorktree) throws IOException {
        Path metadataRoot = location.synesisDirectory().toAbsolutePath().normalize();
        Path localRoot = metadataRoot.resolve("local");
        Path lockDirectory = localRoot.resolve("mutation-locks");
        if (Files.isSymbolicLink(metadataRoot) || Files.isSymbolicLink(localRoot)
                || Files.isSymbolicLink(lockDirectory)) {
            throw new IOException("mutation lock path contains a symbolic link");
        }
        Path canonicalWorktree = assignedWorktree.toRealPath();
        Files.createDirectories(lockDirectory);
        String key = Sha256.hex(canonicalWorktree.toString());
        return new MutationLockLocation(lockDirectory.resolve(key + ".lock"), canonicalWorktree);
    }

    private MutationResult applyTargetMutation(Attempt attempt,
            ProviderSessionBindingService.Binding binding, Path canonicalAssigned, Target target) {
        MutationRequest request = attempt.request();
        Path targetFile = target.file();
        Rejection precondition = checkPreconditions(request, canonicalAssigned, targetFile);
        if (precondition != null) {
            return attempt.reject(precondition, binding);
        }

        Path controlRoot = request.location().root().toAbsolutePath().normalize();
        Path controlFile = controlRoot.resolve(target.resolvedRelative()).toAbsolutePath().normalize();
        byte[] controlBefore = null;
        if (Files.exists(controlFile)) {
            try {
                controlBefore = Files.readAllBytes(controlFile);
            } catch (IOException ignored) {
            }
        }

        try {
            Files.createDirectories(targetFile.getParent());
            byte[] contentBytes = request.newContentBytes() != null ? request.newContentBytes().clone() : (request.newContent() == null ? new byte[0] : request.newContent().getBytes(StandardCharsets.UTF_8));
            atomicWrite(targetFile, contentBytes);
        } catch (Exception failure) {
            return attempt.reject(new Rejection(Decision.DENY_POLICY, "MUTATION_WRITE_FAILED",
                    "Failed to apply mutation: " + failure.getMessage()), binding);
        }

        boolean controlUnchanged;
        if (Files.exists(controlFile)) {
            if (controlBefore == null) {
                controlUnchanged = false;
            } else {
                try {
                    byte[] controlAfter = Files.readAllBytes(controlFile);
                    controlUnchanged = Arrays.equals(controlBefore, controlAfter);
                } catch (IOException failure) {
                    controlUnchanged = false;
                }
            }
        } else {
            controlUnchanged = (controlBefore == null);
        }

        if (!controlUnchanged) {
            return attempt.reject(new Rejection(Decision.DENY_POLICY, "CONTROL_CHECKOUT_MODIFIED",
                    "Control checkout was modified during mutation"), binding, targetFile);
        }
        return attempt.allow(binding, targetFile);
    }

    private static Rejection checkPreconditions(MutationRequest request, Path canonicalAssigned,
            Path targetFile) {
        try {
            if (!WorkspacePathPolicy.targetResolvesWithinWorktree(canonicalAssigned, targetFile)) {
                return new Rejection(Decision.INVALID_TARGET, "SYMLINK_ESCAPE_REJECTED",
                        "Symlink escape rejected");
            }
        } catch (IOException failure) {
            return new Rejection(Decision.INVALID_TARGET, "SYMLINK_RESOLUTION_FAILED",
                    "Symlink resolution failed: " + failure.getMessage());
        }

        if (request.createOnly() && Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
            return new Rejection(Decision.STALE_CONTEXT, "FILE_ALREADY_EXISTS",
                    "Create-only condition failed: target already exists");
        }
        if (request.expectedPreviousHash() != null) {
            Rejection mismatch = new Rejection(Decision.STALE_CONTEXT, "HASH_MISMATCH",
                    "Previous content hash mismatch");
            if (!Files.isRegularFile(targetFile)) {
                return mismatch;
            }
            try {
                String actualHash = Sha256.hex(targetFile);
                if (!request.expectedPreviousHash().equalsIgnoreCase(actualHash)) {
                    return mismatch;
                }
            } catch (IOException unreadable) {
                return new Rejection(Decision.STALE_CONTEXT, "HASH_READ_FAILED",
                        "Previous content could not be verified");
            }
        }
        return null;
    }

    private static final Rejection LOCK_FAILED = new Rejection(Decision.WORKSPACE_UNVERIFIED,
            "MUTATION_LOCK_FAILED", "The assigned worktree mutation lock could not be acquired");

    private record MutationLockLocation(Path lockFile, Path worktree) {
    }

    /**
     * A refused mutation: the decision, reason code, and message that are recorded and returned.
     */
    private record Rejection(Decision decision, String reasonCode, String message) {
    }

    /**
     * Outcome of the authorization stage. The binding is kept on a rejection because the evidence
     * file names the session that was refused.
     */
    private record Authorization(ProviderSessionBindingService.Binding binding, Path worktree,
                                 Rejection rejection) {

        static Authorization rejected(ProviderSessionBindingService.Binding binding,
                Decision decision, String reasonCode, String message) {
            return new Authorization(binding, null, new Rejection(decision, reasonCode, message));
        }
    }

    /**
     * What the next stage needs, or the rejection that ends the request.
     */
    private record Staged<T>(T value, Rejection rejection) {

        static <T> Staged<T> of(T value) {
            return new Staged<>(value, null);
        }

        static <T> Staged<T> rejected(Decision decision, String reasonCode, String message) {
            return new Staged<>(null, new Rejection(decision, reasonCode, message));
        }
    }

    private record Target(String resolvedRelative, Path file) {
    }

    /**
     * One call's identity; every call records exactly one evidence file through it.
     */
    private record Attempt(MutationRequest request, String decisionId, String interceptionEvidence) {

        MutationResult reject(Rejection rejection, ProviderSessionBindingService.Binding binding) {
            return reject(rejection, binding, null);
        }

        MutationResult reject(Rejection rejection, ProviderSessionBindingService.Binding binding,
                Path mutatedPath) {
            return record(false, rejection.decision(), rejection.reasonCode(), rejection.message(), binding, mutatedPath);
        }

        MutationResult allow(ProviderSessionBindingService.Binding binding, Path mutatedPath) {
            return record(true, Decision.ALLOW, "ALLOWED", "Mutation successful", binding, mutatedPath);
        }

        private MutationResult record(boolean success, Decision decision, String reasonCode,
                String message, ProviderSessionBindingService.Binding binding, Path mutatedPath) {
            try {
                recordEvidence(request.location(), request.provider(), binding, decisionId, interceptionEvidence, request.relativePath(), decision, reasonCode, message);
            } catch (Exception ignored) {
            }
            String updatedRevision = null;
            if (success && mutatedPath != null) {
                try {
                    updatedRevision = Sha256.hex(Files.readAllBytes(mutatedPath));
                } catch (Exception ignored) {
                }
            }
            return new MutationResult(success, decision, reasonCode, message, decisionId, interceptionEvidence, mutatedPath, true, updatedRevision);
        }
    }

    /**
     * Outcome classification of a workspace mutation request.
     */
    public enum Decision {
        /**
         * Mutation is allowed and applied to the assigned worktree.
         */
        ALLOW,
        /**
         * Mutation is blocked by active project policy.
         */
        DENY_POLICY,
        /**
         * Mutation is directed to another node's semantic capability owner.
         */
        REQUEST_OWNER,
        /**
         * Stale workspace context.
         */
        STALE_CONTEXT,
        /**
         * Workspace trust state is unverified.
         */
        WORKSPACE_UNVERIFIED,
        /**
         * Actor is not authorized.
         */
        ACTOR_NOT_AUTHORIZED,
        /**
         * Target path or input is invalid.
         */
        INVALID_TARGET,
        /**
         * Hook interception is missing or synthetic.
         */
        INTERCEPTION_MISSING,
        /**
         * Provider session is unbound.
         */
        SESSION_UNBOUND
    }

    /**
     * Workspace mutation request parameters.
     *
     * @param location             initialized project location
     * @param provider             provider identifier
     * @param connectionInstanceId exact provider connection identity, or {@code null}
     * @param relativePath         target repository-relative path
     * @param toolName             tool or action identifier
     * @param newContent           new proposed file content
     * @param newContentBytes      exact encoded bytes to persist, or {@code null} for UTF-8 encoding
     * @param hookIntercepted      whether Synesis intercepted the proposed mutation
     * @param isSyntheticCheck     whether execution is a synthetic check
     * @param expectedPreviousHash expected target SHA-256 precondition, or {@code null}
     * @param createOnly           whether the target must be absent before mutation
     */
    public record MutationRequest(ProjectLocation location, String provider,
                                  String connectionInstanceId, String relativePath,
                                  String toolName, String newContent, byte[] newContentBytes,
                                  boolean hookIntercepted, boolean isSyntheticCheck,
                                  String expectedPreviousHash, boolean createOnly) {

        /**
         * Validates the request fields.
         */
        public MutationRequest {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(toolName, "toolName");
        }

        /**
         * Constructs a request without a connection identity for controlled internal checks.
         *
         * @param location         initialized project location
         * @param provider         provider identifier
         * @param relativePath     target repository-relative path
         * @param toolName         tool or action identifier
         * @param newContent       proposed file content
         * @param hookIntercepted  whether Synesis intercepted the mutation
         * @param isSyntheticCheck whether execution is synthetic
         */
        public MutationRequest(ProjectLocation location, String provider, String relativePath, String toolName, String newContent, boolean hookIntercepted, boolean isSyntheticCheck) {
            this(location, provider, null, relativePath, toolName, newContent, null,
                    hookIntercepted, isSyntheticCheck, null, false);
        }

        /**
         * Constructs a connection-aware request using default UTF-8 persistence.
         *
         * @param location             initialized project location
         * @param provider             provider identifier
         * @param connectionInstanceId exact connection identity
         * @param relativePath         target repository-relative path
         * @param toolName             tool or action identifier
         * @param newContent           proposed file content
         * @param hookIntercepted      whether Synesis intercepted the mutation
         * @param isSyntheticCheck     whether execution is synthetic
         */
        public MutationRequest(ProjectLocation location, String provider, String connectionInstanceId, String relativePath, String toolName, String newContent, boolean hookIntercepted, boolean isSyntheticCheck) {
            this(location, provider, connectionInstanceId, relativePath, toolName, newContent,
                    null, hookIntercepted, isSyntheticCheck, null, false);
        }

        /**
         * Constructs a connection-aware request with atomic file preconditions.
         *
         * @param location initialized project location
         * @param provider provider identifier
         * @param connectionInstanceId exact connection identity
         * @param relativePath target repository-relative path
         * @param toolName tool or action identifier
         * @param newContent proposed file content
         * @param hookIntercepted whether Synesis intercepted the proposed mutation
         * @param isSyntheticCheck whether execution is a synthetic check
         * @param expectedPreviousHash expected SHA-256 of the existing target, or {@code null}
         * @param createOnly whether the target must not already exist
         */
        public MutationRequest(ProjectLocation location, String provider,
                String connectionInstanceId, String relativePath, String toolName,
                String newContent, boolean hookIntercepted, boolean isSyntheticCheck,
                String expectedPreviousHash, boolean createOnly) {
            this(location, provider, connectionInstanceId, relativePath, toolName, newContent,
                    null, hookIntercepted, isSyntheticCheck, expectedPreviousHash, createOnly);
        }
    }

    /**
     * Result of processing a workspace mutation request.
     *
     * @param success                  whether the mutation was successfully applied
     * @param decision                 broker decision outcome
     * @param reasonCode               structured decision reason code
     * @param message                  human readable status explanation or denial reason
     * @param decisionId               unique decision record identifier
     * @param interceptionEvidence     SHA-256 interception evidence hash
     * @param mutatedPath              path of the mutated file in the assigned worktree, or
     *                                 {@code null}
     * @param controlCheckoutUnchanged whether the control checkout remained unchanged
     * @param updatedRevision          updated opaque file revision, or {@code null}
     */
    public record MutationResult(boolean success, Decision decision, String reasonCode, String message, String decisionId, String interceptionEvidence, Path mutatedPath, boolean controlCheckoutUnchanged,
                                 String updatedRevision) {

        /**
         * Constructs a result without a returned revision.
         *
         * @param success                  whether mutation succeeded
         * @param decision                 broker decision
         * @param reasonCode               bounded reason code
         * @param message                  bounded message
         * @param decisionId               internal decision identifier
         * @param interceptionEvidence     internal evidence
         * @param mutatedPath              mutated path
         * @param controlCheckoutUnchanged whether control checkout stayed unchanged
         */
        public MutationResult(boolean success, Decision decision, String reasonCode, String message, String decisionId, String interceptionEvidence, Path mutatedPath, boolean controlCheckoutUnchanged) {
            this(success, decision, reasonCode, message, decisionId, interceptionEvidence, mutatedPath, controlCheckoutUnchanged, null);
        }

        /**
         * Validates the result shape.
         */
        public MutationResult {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(message, "message");
        }
    }
}
