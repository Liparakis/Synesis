package org.synesis.workspace.application.workspace.mutation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
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
        return sha256(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the runtime", unavailable);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the runtime", unavailable);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Checks lexical containment and the real path of the nearest existing target ancestor.
     *
     * @param assignedWorktree verified assigned worktree
     * @param targetFile normalized candidate file path
     * @return whether the candidate resolves within the assigned worktree
     * @throws IOException when the path cannot be resolved safely
     */
    public static boolean targetResolvesWithinWorktree(Path assignedWorktree, Path targetFile)
            throws IOException {
        Path root = Objects.requireNonNull(assignedWorktree, "assigned worktree")
                .toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(targetFile, "target file")
                .toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return false;
        }
        Path canonicalRoot = root.toRealPath();
        Path existingAncestor = target;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)
                && existingAncestor.getParent() != null) {
            existingAncestor = existingAncestor.getParent();
        }
        return existingAncestor.toRealPath().startsWith(canonicalRoot);
    }

    /**
     * Identifies project-internal and provider-managed files that mutations must not replace.
     *
     * @param resolvedRelative normalized project-relative target
     * @return whether the target belongs to a protected configuration namespace
     */
    public static boolean isProtectedTarget(String resolvedRelative) {
        if (resolvedRelative == null || resolvedRelative.isBlank()) {
            return true;
        }
        String normalized = resolvedRelative.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith(".synesis/") || normalized.startsWith(".codex/")
                || normalized.startsWith(".git/") || normalized.equals(".synesis")
                || normalized.equals(".codex") || normalized.equals(".git");
    }

    private static MutationResult evaluateAndRecord(boolean success,
            Decision decision,
            String reasonCode,
            String message,
            String decisionId,
            String interceptionEvidence,
            MutationRequest request,
            ProviderSessionBindingService.Binding binding,
            Path mutatedPath) {
        try {
            recordEvidence(request.location(), request.provider(), binding, decisionId, interceptionEvidence, request.relativePath(), decision, reasonCode, message);
        } catch (Exception ignored) {
        }
        String updatedRevision = null;
        if (success && mutatedPath != null) {
            try {
                updatedRevision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(mutatedPath)));
            } catch (Exception ignored) {
            }
        }
        return new MutationResult(success, decision, reasonCode, message, decisionId, interceptionEvidence, mutatedPath, true, updatedRevision);
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
     * @param request workspace mutation request
     * @return mutation result
     */
    public synchronized MutationResult applyMutation(MutationRequest request) {
        Objects.requireNonNull(request, "request");

        String decisionId = "dec-" + UUID.randomUUID();
        String interceptionEvidence = computeEvidence(request);

        // 1. Session binding check
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);
        ProviderSessionBindingService.Binding binding;
        try {
            if (request.connectionInstanceId() == null || request.connectionInstanceId().isBlank()) {
                return evaluateAndRecord(false,
                        Decision.SESSION_UNBOUND,
                        "EXACT_SESSION_REQUIRED",
                        "An exact provider connection is required for mutation authority",
                        decisionId,
                        interceptionEvidence,
                        request,
                        null,
                        null);
            }
            binding = authorityResolver.resolve(request.location(), request.provider(), request.connectionInstanceId());
            if (!"BOUND".equals(binding.status())) {
                return evaluateAndRecord(false,
                        Decision.SESSION_UNBOUND,
                        "SESSION_NOT_BOUND",
                        "Provider session status is " + binding.status() + ", expected BOUND",
                        decisionId,
                        interceptionEvidence,
                        request,
                        binding,
                        null);
            }
        } catch (Exception failure) {
            return evaluateAndRecord(false, Decision.SESSION_UNBOUND, "SESSION_LOAD_FAILED", "Could not load provider session binding: " + failure.getMessage(), decisionId, interceptionEvidence, request, null, null);
        }

        // 2. Assigned worktree verification
        if (binding.worktreePath() == null || binding.worktreePath().isBlank()) {
            return evaluateAndRecord(false, Decision.WORKSPACE_UNVERIFIED, "WORKTREE_PATH_MISSING", "Assigned worktree path is missing", decisionId, interceptionEvidence, request, binding, null);
        }
        Path assignedWorktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
        ProviderSessionBindingService.WorkspaceCheck workspaceCheck = bindingService.verifyWorkspace(request.location(), binding, assignedWorktree);
        if (!workspaceCheck.verified() && "CONTROL_BASE_ADVANCED".equals(workspaceCheck.code())) {
            // Keep broker verification aligned with readiness: a live managed provider may mutate
            // within its exact assigned worktree after a sibling integrates, while inactive or
            // ordinary bindings remain fenced by the current control base.
            ProviderSessionBindingService.WorkspaceCheck managedContinuation = bindingService.verifyManagedContinuationWorkspace(request.location(), binding, assignedWorktree);
            if (managedContinuation.verified()) {
                workspaceCheck = managedContinuation;
            } else {
                ProviderSessionBindingService.WorkspaceCheck claimedContinuation = bindingService.verifyClaimedMutationContinuationWorkspace(request.location(), binding, assignedWorktree, request.relativePath());
                if (claimedContinuation.verified()) {
                    workspaceCheck = claimedContinuation;
                }
            }
        }
        if (!workspaceCheck.verified()) {
            return evaluateAndRecord(false,
                    Decision.WORKSPACE_UNVERIFIED,
                    workspaceCheck.code(),
                    "Assigned worktree verification failed: " + workspaceCheck.code(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // 3. Workspace trust check
        if (!"VERIFIED".equals(binding.providerTrustState())) {
            return evaluateAndRecord(false,
                    Decision.WORKSPACE_UNVERIFIED,
                    "WORKSPACE_NOT_VERIFIED",
                    "Workspace trust state is " + binding.providerTrustState() + ", expected VERIFIED",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // 4. Missing interception check
        if (!request.hookIntercepted()) {
            return evaluateAndRecord(false, Decision.INTERCEPTION_MISSING, "HOOK_NOT_INTERCEPTED", "Missing interception: HOOK_INTERCEPTED is false", decisionId, interceptionEvidence, request, binding, null);
        }

        // 7. Synthetic check does not count as real interception
        if (request.isSyntheticCheck()) {
            return evaluateAndRecord(false,
                    Decision.INTERCEPTION_MISSING,
                    "SYNTHETIC_CHECK_REJECTED",
                    "Synthetic hook execution does not count as real interception",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        if (request.createOnly() && request.expectedPreviousHash() != null) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "CONFLICTING_PRECONDITIONS",
                    "Create-only and previous-hash conditions cannot be combined", decisionId,
                    interceptionEvidence, request, binding, null);
        }
        if (request.expectedPreviousHash() != null
                && !request.expectedPreviousHash().matches("(?i)[0-9a-f]{64}")) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "INVALID_PREVIOUS_HASH",
                    "Previous content hash must be a SHA-256 value", decisionId,
                    interceptionEvidence, request, binding, null);
        }

        // 5. Action evaluation
        if (Path.of(request.relativePath()).isAbsolute()) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "ABSOLUTE_PATH_REJECTED", "Absolute target paths are rejected: " + request.relativePath(), decisionId, interceptionEvidence, request, binding, null);
        }
        if (request.relativePath().contains("..")) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "PATH_TRAVERSAL_REJECTED", "Path traversal rejected: " + request.relativePath(), decisionId, interceptionEvidence, request, binding, null);
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, request.relativePath());
        } catch (Exception failure) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "INVALID_PATH_FORMAT", "Invalid target relative path: " + failure.getMessage(), decisionId, interceptionEvidence, request, binding, null);
        }

        // Protected system/provider configuration files check
        if (isProtectedTarget(resolvedRelative)) {
            return evaluateAndRecord(false,
                    Decision.DENY_POLICY,
                    "PROTECTED_CONFIGURATION_TARGET",
                    "Target path is a protected configuration target: " + resolvedRelative,
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        ActionGuardrail.Request guardrailReq = new ActionGuardrail.Request(assignedWorktree, resolvedRelative, request.toolName(), "Workspace mutation");
        ActionGuardrail.Response guardrailResp = ActionGuardrail.evaluate(request.location().profile(), guardrailReq);

        if (guardrailResp.outcome() == ActionGuardrail.Outcome.BLOCKED) {
            return evaluateAndRecord(false, Decision.DENY_POLICY, "POLICY_BLOCKED", guardrailResp.message(), decisionId, interceptionEvidence, request, binding, null);
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.REQUEST_OWNER) {
            return evaluateAndRecord(false, Decision.REQUEST_OWNER, "REQUEST_OWNER", guardrailResp.message(), decisionId, interceptionEvidence, request, binding, null);
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.INVALID_INPUT || guardrailResp.outcome() == ActionGuardrail.Outcome.UNSUPPORTED) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "INVALID_GUARDRAIL_INPUT", guardrailResp.message(), decisionId, interceptionEvidence, request, binding, null);
        }

        // 6. Execute mutation in assigned worktree ONLY
        Path targetFile = assignedWorktree.resolve(resolvedRelative).toAbsolutePath().normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "PATH_ESCAPE_DETECTED", "Path escape detected outside assigned worktree", decisionId, interceptionEvidence, request, binding, null);
        }

        MutationLockLocation lockLocation;
        try {
            lockLocation = mutationLockLocation(request.location(), assignedWorktree);
        } catch (IOException unavailable) {
            return mutationLockFailure(request, binding, decisionId, interceptionEvidence);
        }
        try (BoundedFileLock lock = BoundedFileLock.acquire(lockLocation.lockFile(),
                MUTATION_LOCK_TIMEOUT)) {
            if (!lock.isHeld() || !assignedWorktree.toRealPath().equals(lockLocation.worktree())) {
                return mutationLockFailure(request, binding, decisionId, interceptionEvidence);
            }
            return applyTargetMutation(request, binding, lockLocation.worktree(), resolvedRelative,
                    targetFile, decisionId,
                    interceptionEvidence);
        } catch (IOException unavailable) {
            return mutationLockFailure(request, binding, decisionId, interceptionEvidence);
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
        String key = sha256(canonicalWorktree.toString().getBytes(StandardCharsets.UTF_8));
        return new MutationLockLocation(lockDirectory.resolve(key + ".lock"), canonicalWorktree);
    }

    private MutationResult applyTargetMutation(MutationRequest request,
            ProviderSessionBindingService.Binding binding, Path canonicalAssigned,
            String resolvedRelative, Path targetFile, String decisionId,
            String interceptionEvidence) {
        try {
            if (!targetResolvesWithinWorktree(canonicalAssigned, targetFile)) {
                return evaluateAndRecord(false, Decision.INVALID_TARGET, "SYMLINK_ESCAPE_REJECTED",
                        "Symlink escape rejected", decisionId, interceptionEvidence, request,
                        binding, null);
            }
        } catch (IOException failure) {
            return evaluateAndRecord(false, Decision.INVALID_TARGET, "SYMLINK_RESOLUTION_FAILED",
                    "Symlink resolution failed: " + failure.getMessage(), decisionId,
                    interceptionEvidence, request, binding, null);
        }

        if (request.createOnly() && Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
            return evaluateAndRecord(false, Decision.STALE_CONTEXT, "FILE_ALREADY_EXISTS",
                    "Create-only condition failed: target already exists", decisionId,
                    interceptionEvidence, request, binding, null);
        }
        if (request.expectedPreviousHash() != null) {
            if (!Files.isRegularFile(targetFile)) {
                return evaluateAndRecord(false, Decision.STALE_CONTEXT, "HASH_MISMATCH",
                        "Previous content hash mismatch", decisionId, interceptionEvidence,
                        request, binding, null);
            }
            try {
                String actualHash = sha256(targetFile);
                if (!request.expectedPreviousHash().equalsIgnoreCase(actualHash)) {
                    return evaluateAndRecord(false, Decision.STALE_CONTEXT, "HASH_MISMATCH",
                            "Previous content hash mismatch", decisionId, interceptionEvidence,
                            request, binding, null);
                }
            } catch (IOException unreadable) {
                return evaluateAndRecord(false, Decision.STALE_CONTEXT, "HASH_READ_FAILED",
                        "Previous content could not be verified", decisionId,
                        interceptionEvidence, request, binding, null);
            }
        }

        Path controlRoot = request.location().root().toAbsolutePath().normalize();
        Path controlFile = controlRoot.resolve(resolvedRelative).toAbsolutePath().normalize();
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
            return evaluateAndRecord(false, Decision.DENY_POLICY, "MUTATION_WRITE_FAILED", "Failed to apply mutation: " + failure.getMessage(), decisionId, interceptionEvidence, request, binding, null);
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
            return evaluateAndRecord(false, Decision.DENY_POLICY, "CONTROL_CHECKOUT_MODIFIED",
                    "Control checkout was modified during mutation", decisionId,
                    interceptionEvidence, request, binding, targetFile);
        }
        return evaluateAndRecord(true, Decision.ALLOW, "ALLOWED", "Mutation successful",
                decisionId, interceptionEvidence, request, binding, targetFile);
    }

    private MutationResult mutationLockFailure(MutationRequest request,
            ProviderSessionBindingService.Binding binding, String decisionId,
            String interceptionEvidence) {
        return evaluateAndRecord(false, Decision.WORKSPACE_UNVERIFIED, "MUTATION_LOCK_FAILED",
                "The assigned worktree mutation lock could not be acquired", decisionId,
                interceptionEvidence, request, binding, null);
    }

    private record MutationLockLocation(Path lockFile, Path worktree) {
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
        SESSION_UNBOUND,
        /**
         * Decision is unknown.
         */
        UNKNOWN
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
         * Preserves the original connection-aware request shape without preconditions.
         *
         * @param location initialized project location
         * @param provider provider identifier
         * @param connectionInstanceId exact connection identity
         * @param relativePath target repository-relative path
         * @param toolName tool or action identifier
         * @param newContent proposed file content
         * @param newContentBytes exact encoded bytes to persist, or {@code null} for UTF-8
         * @param hookIntercepted whether Synesis intercepted the proposed mutation
         * @param isSyntheticCheck whether execution is a synthetic check
         */
        public MutationRequest(ProjectLocation location, String provider,
                String connectionInstanceId, String relativePath, String toolName,
                String newContent, byte[] newContentBytes, boolean hookIntercepted,
                boolean isSyntheticCheck) {
            this(location, provider, connectionInstanceId, relativePath, toolName, newContent,
                    newContentBytes, hookIntercepted, isSyntheticCheck, null, false);
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
