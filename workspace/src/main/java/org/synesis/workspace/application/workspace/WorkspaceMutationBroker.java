package org.synesis.workspace.application.workspace;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.workspace.project.ActionGuardrail;
import org.synesis.workspace.project.ProjectPathResolver;
import org.synesis.workspace.infrastructure.json.ProviderJson;

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

    /**
     * Creates a workspace mutation broker.
     */
    public WorkspaceMutationBroker() {
    }

    private static String computeEvidence(MutationRequest request) {
        try {
            String payload =
                    request.relativePath() + "\n" + request.toolName() + "\n" + (request.newContent() == null ? ""
                            : request.newContent());
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return HexFormat.of()
                    .formatHex(new byte[32]);
        }
    }

    private static MutationResult evaluateAndRecord(
            boolean success,
            Decision decision,
            String reasonCode,
            String message,
            String decisionId,
            String interceptionEvidence,
            MutationRequest request,
            ProviderSessionBindingService.Binding binding,
            Path mutatedPath
    ) {
        try {
            recordEvidence(request.location(),
                    request.provider(),
                    binding,
                    decisionId,
                    interceptionEvidence,
                    request.relativePath(),
                    decision,
                    reasonCode,
                    message);
        } catch (Exception ignored) {
        }
        String updatedRevision = null;
        if (success && mutatedPath != null) {
            try {
                updatedRevision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(mutatedPath)));
            } catch (Exception ignored) {
                updatedRevision = null;
            }
        }
        return new MutationResult(success,
                decision,
                reasonCode,
                message,
                decisionId,
                interceptionEvidence,
                mutatedPath,
                true,
                updatedRevision);
    }

    private static void recordEvidence(
            ProjectApplicationService.ProjectLocation location,
            String provider,
            ProviderSessionBindingService.Binding binding,
            String decisionId,
            String evidenceHash,
            String relativePath,
            Decision decision,
            String reasonCode,
            String message
    ) throws IOException {
        Path evidenceDir = location.synesisDirectory()
                .resolve("local")
                .resolve("evidence")
                .resolve(provider);
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
        ProviderSessionBindingService.Binding binding = null;
        try {
            var bindings = request.connectionInstanceId() == null
                    ? bindingService.list(request.location(), request.provider())
                    : bindingService.find(request.location(), request.provider(), request.connectionInstanceId())
                            .map(java.util.List::of).orElseGet(java.util.List::of);
            if (bindings.isEmpty()) {
                return evaluateAndRecord(false,
                        Decision.SESSION_UNBOUND,
                        "NO_BOUND_SESSION",
                        "No bound session for provider: " + request.provider(),
                        decisionId,
                        interceptionEvidence,
                        request,
                        null,
                        null);
            }
            binding = bindings.getLast();
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
            return evaluateAndRecord(false,
                    Decision.SESSION_UNBOUND,
                    "SESSION_LOAD_FAILED",
                    "Could not load provider session binding: " + failure.getMessage(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    null,
                    null);
        }

        // 2. Assigned worktree verification
        if (binding.worktreePath() == null || binding.worktreePath()
                .isBlank()) {
            return evaluateAndRecord(false,
                    Decision.WORKSPACE_UNVERIFIED,
                    "WORKTREE_PATH_MISSING",
                    "Assigned worktree path is missing",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }
        Path assignedWorktree = Path.of(binding.worktreePath())
                .toAbsolutePath()
                .normalize();
        ProviderSessionBindingService.WorkspaceCheck workspaceCheck = bindingService.verifyWorkspace(request.location(),
                binding,
                assignedWorktree);
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
            return evaluateAndRecord(false,
                    Decision.INTERCEPTION_MISSING,
                    "HOOK_NOT_INTERCEPTED",
                    "Missing interception: HOOK_INTERCEPTED is false",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
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

        // 5. Action evaluation
        if (Path.of(request.relativePath())
                .isAbsolute()) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "ABSOLUTE_PATH_REJECTED",
                    "Absolute target paths are rejected: " + request.relativePath(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }
        if (request.relativePath()
                .contains("..")) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "PATH_TRAVERSAL_REJECTED",
                    "Path traversal rejected: " + request.relativePath(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, request.relativePath());
        } catch (Exception failure) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "INVALID_PATH_FORMAT",
                    "Invalid target relative path: " + failure.getMessage(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // Protected system/provider configuration files check
        String normalizedTarget = resolvedRelative.replace('\\', '/')
                .toLowerCase();
        if (normalizedTarget.startsWith(".synesis/") || normalizedTarget.startsWith(".codex/")
                || normalizedTarget.startsWith(".agents/") || normalizedTarget.startsWith(".git/")
                || normalizedTarget.equals(".synesis") || normalizedTarget.equals(".codex") || normalizedTarget.equals(
                ".agents") || normalizedTarget.equals(".git")) {
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

        ActionGuardrail.Request guardrailReq = new ActionGuardrail.Request(assignedWorktree,
                resolvedRelative,
                request.toolName(),
                "Workspace mutation");
        ActionGuardrail.Response guardrailResp = ActionGuardrail.evaluate(request.location()
                .profile(), guardrailReq);

        if (guardrailResp.outcome() == ActionGuardrail.Outcome.BLOCKED) {
            return evaluateAndRecord(false,
                    Decision.DENY_POLICY,
                    "POLICY_BLOCKED",
                    guardrailResp.message(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.REQUEST_OWNER) {
            return evaluateAndRecord(false,
                    Decision.REQUEST_OWNER,
                    "REQUEST_OWNER",
                    guardrailResp.message(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.INVALID_INPUT
                || guardrailResp.outcome() == ActionGuardrail.Outcome.UNSUPPORTED) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "INVALID_GUARDRAIL_INPUT",
                    guardrailResp.message(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // 6. Execute mutation in assigned worktree ONLY
        Path targetFile = assignedWorktree.resolve(resolvedRelative)
                .toAbsolutePath()
                .normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "PATH_ESCAPE_DETECTED",
                    "Path escape detected outside assigned worktree",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // Check symlink escape
        try {
            Path canonicalAssigned = assignedWorktree.toRealPath();
            Path existingParent = targetFile;
            while (!Files.exists(existingParent) && existingParent.getParent() != null) {
                existingParent = existingParent.getParent();
            }
            if (!existingParent.toRealPath()
                    .startsWith(canonicalAssigned)) {
                return evaluateAndRecord(false,
                        Decision.INVALID_TARGET,
                        "SYMLINK_ESCAPE_REJECTED",
                        "Symlink escape rejected",
                        decisionId,
                        interceptionEvidence,
                        request,
                        binding,
                        null);
            }
        } catch (IOException failure) {
            return evaluateAndRecord(false,
                    Decision.INVALID_TARGET,
                    "SYMLINK_RESOLUTION_FAILED",
                    "Symlink resolution failed: " + failure.getMessage(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        Path controlRoot = request.location()
                .root()
                .toAbsolutePath()
                .normalize();
        Path controlFile = controlRoot.resolve(resolvedRelative)
                .toAbsolutePath()
                .normalize();
        byte[] controlBefore = null;
        if (Files.exists(controlFile)) {
            try {
                controlBefore = Files.readAllBytes(controlFile);
            } catch (IOException ignored) {
            }
        }

        try {
            Files.createDirectories(targetFile.getParent());
            byte[] contentBytes = request.newContentBytes() != null
                    ? request.newContentBytes().clone()
                    : (request.newContent() == null ? new byte[0] : request.newContent()
                                                                               .getBytes(StandardCharsets.UTF_8));
            atomicWrite(targetFile, contentBytes);
        } catch (Exception failure) {
            return evaluateAndRecord(false,
                    Decision.DENY_POLICY,
                    "MUTATION_WRITE_FAILED",
                    "Failed to apply mutation: " + failure.getMessage(),
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    null);
        }

        // Verify control checkout remains unchanged
        boolean controlUnchanged = true;
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
            return evaluateAndRecord(false,
                    Decision.DENY_POLICY,
                    "CONTROL_CHECKOUT_MODIFIED",
                    "Control checkout was modified during mutation",
                    decisionId,
                    interceptionEvidence,
                    request,
                    binding,
                    targetFile);
        }

        return evaluateAndRecord(true,
                Decision.ALLOW,
                "ALLOWED",
                "Mutation successful",
                decisionId,
                interceptionEvidence,
                request,
                binding,
                targetFile);
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
     * @param location         initialized project location
     * @param provider         provider identifier
     * @param connectionInstanceId exact provider connection identity, or {@code null}
     * @param relativePath     target repository-relative path
     * @param toolName         tool or action identifier
     * @param newContent       new proposed file content
     * @param newContentBytes  exact encoded bytes to persist, or {@code null} for UTF-8 encoding
     * @param hookIntercepted  whether Synesis intercepted the proposed mutation
     * @param isSyntheticCheck whether execution is a synthetic check
     */
    public record MutationRequest(
            ProjectApplicationService.ProjectLocation location,
            String provider,
            String connectionInstanceId,
            String relativePath,
            String toolName,
            String newContent,
            byte[] newContentBytes,
            boolean hookIntercepted,
            boolean isSyntheticCheck
    ) {

        /**
         * Validates the request fields.
         */
        public MutationRequest {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(toolName, "toolName");
        }

        /** Backward-compatible constructor for internal callers without a connection identity.
         * @param location initialized project location
         * @param provider provider identifier
         * @param relativePath target repository-relative path
         * @param toolName tool or action identifier
         * @param newContent proposed file content
         * @param hookIntercepted whether Synesis intercepted the mutation
         * @param isSyntheticCheck whether execution is synthetic
         */
        public MutationRequest(ProjectApplicationService.ProjectLocation location, String provider,
                String relativePath, String toolName, String newContent, boolean hookIntercepted,
                boolean isSyntheticCheck) {
            this(location, provider, null, relativePath, toolName, newContent, null, hookIntercepted,
                    isSyntheticCheck);
        }

        /**
         * Backward-compatible connection-aware constructor using default UTF-8 persistence.
         *
         * @param location initialized project location
         * @param provider provider identifier
         * @param connectionInstanceId exact connection identity
         * @param relativePath target repository-relative path
         * @param toolName tool or action identifier
         * @param newContent proposed file content
         * @param hookIntercepted whether Synesis intercepted the mutation
         * @param isSyntheticCheck whether execution is synthetic
         */
        public MutationRequest(ProjectApplicationService.ProjectLocation location, String provider,
                String connectionInstanceId, String relativePath, String toolName, String newContent,
                boolean hookIntercepted, boolean isSyntheticCheck) {
            this(location, provider, connectionInstanceId, relativePath, toolName, newContent, null,
                    hookIntercepted, isSyntheticCheck);
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
     * @param mutatedPath              path of the mutated file in the assigned worktree, or {@code null}
     * @param controlCheckoutUnchanged whether the control checkout remained unchanged
     * @param updatedRevision          updated opaque file revision, or {@code null}
     */
    public record MutationResult(
            boolean success,
            Decision decision,
            String reasonCode,
            String message,
            String decisionId,
            String interceptionEvidence,
            Path mutatedPath,
            boolean controlCheckoutUnchanged,
            String updatedRevision
    ) {

        /** Backward-compatible constructor for callers without a returned revision.
         * @param success whether mutation succeeded
         * @param decision broker decision
         * @param reasonCode bounded reason code
         * @param message bounded message
         * @param decisionId internal decision identifier
         * @param interceptionEvidence internal evidence
         * @param mutatedPath mutated path
         * @param controlCheckoutUnchanged whether control checkout stayed unchanged
         */
        public MutationResult(boolean success, Decision decision, String reasonCode, String message,
                String decisionId, String interceptionEvidence, Path mutatedPath,
                boolean controlCheckoutUnchanged) {
            this(success, decision, reasonCode, message, decisionId, interceptionEvidence,
                    mutatedPath, controlCheckoutUnchanged, null);
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
