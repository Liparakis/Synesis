package org.synesis.workspace.application;

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

import org.synesis.workspace.guardrail.ActionGuardrail;
import org.synesis.workspace.guardrail.ProjectPathResolver;
import org.synesis.workspace.provider.ProviderJson;

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

    /** Outcome classification of a workspace mutation request. */
    public enum Decision {
        /** Mutation is allowed and applied to the assigned worktree. */
        ALLOW,
        /** Mutation is blocked by active project policy. */
        BLOCKED,
        /** Mutation decision is unknown or unmapped. */
        UNKNOWN,
        /** Mutation matches a warning constraint and is not explicitly allowed. */
        WARNING,
        /** Mutation is unsupported. */
        UNSUPPORTED,
        /** Workspace trust state is unverified. */
        WORKSPACE_UNVERIFIED,
        /** Hook interception is missing or synthetic. */
        INTERCEPTION_MISSING,
        /** Provider session is unbound. */
        SESSION_UNBOUND
    }

    /**
     * Workspace mutation request parameters.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @param relativePath target repository-relative path
     * @param toolName tool or action identifier
     * @param newContent new proposed file content
     * @param hookIntercepted whether Synesis intercepted the proposed mutation
     * @param isSyntheticCheck whether execution is a synthetic check
     */
    public record MutationRequest(
            ProjectApplicationService.ProjectLocation location,
            String provider,
            String relativePath,
            String toolName,
            String newContent,
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
    }

    /**
     * Result of processing a workspace mutation request.
     *
     * @param success whether the mutation was successfully applied
     * @param decision broker decision outcome
     * @param decisionId unique decision record identifier, or {@code null}
     * @param interceptionEvidence SHA-256 interception evidence hash, or {@code null}
     * @param message human readable status or denial reason
     * @param mutatedPath path of the mutated file in the assigned worktree, or {@code null}
     * @param controlCheckoutUnchanged whether the control checkout remained unchanged
     */
    public record MutationResult(
            boolean success,
            Decision decision,
            String decisionId,
            String interceptionEvidence,
            String message,
            Path mutatedPath,
            boolean controlCheckoutUnchanged
    ) {
        /**
         * Validates the result shape.
         */
        public MutationResult {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** Creates a workspace mutation broker. */
    public WorkspaceMutationBroker() {
    }

    /**
     * Evaluates and applies a workspace mutation request enforcing all workspace invariants.
     *
     * @param request workspace mutation request
     * @return mutation result
     */
    public synchronized MutationResult applyMutation(MutationRequest request) {
        Objects.requireNonNull(request, "request");

        // 1. Session binding check
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        ProviderSessionBindingService.Binding binding;
        try {
            var bindings = bindingService.list(request.location(), request.provider());
            if (bindings.isEmpty()) {
                return failure(Decision.SESSION_UNBOUND, null, null, "No bound session for provider: " + request.provider());
            }
            binding = bindings.getLast();
            if (!"BOUND".equals(binding.status())) {
                return failure(Decision.SESSION_UNBOUND, null, null, "Provider session status is " + binding.status() + ", expected BOUND");
            }
        } catch (Exception failure) {
            return failure(Decision.SESSION_UNBOUND, null, null, "Could not load provider session binding: " + failure.getMessage());
        }

        // 2. Assigned worktree verification
        if (binding.worktreePath() == null || binding.worktreePath().isBlank()) {
            return failure(Decision.WORKSPACE_UNVERIFIED, null, null, "Assigned worktree path is missing");
        }
        Path assignedWorktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
        ProviderSessionBindingService.WorkspaceCheck workspaceCheck = bindingService.verifyWorkspace(request.location(), binding, assignedWorktree);
        if (!workspaceCheck.verified()) {
            return failure(Decision.WORKSPACE_UNVERIFIED, null, null, "Assigned worktree verification failed: " + workspaceCheck.code());
        }

        // 3. Workspace trust check
        if (!"VERIFIED".equals(binding.providerTrustState())) {
            return failure(Decision.WORKSPACE_UNVERIFIED, null, null, "Workspace trust state is " + binding.providerTrustState() + ", expected VERIFIED");
        }

        // 4. Missing interception check
        if (!request.hookIntercepted()) {
            return failure(Decision.INTERCEPTION_MISSING, null, null, "Missing interception: HOOK_INTERCEPTED is false");
        }

        // 7. Synthetic check does not count as real interception
        if (request.isSyntheticCheck()) {
            return failure(Decision.INTERCEPTION_MISSING, null, null, "Synthetic hook execution does not count as real interception");
        }

        // 5. Action evaluation
        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, request.relativePath());
        } catch (Exception failure) {
            return failure(Decision.BLOCKED, null, null, "Invalid target relative path: " + failure.getMessage());
        }

        ActionGuardrail.Request guardrailReq = new ActionGuardrail.Request(assignedWorktree, resolvedRelative, request.toolName(), "Workspace mutation");
        ActionGuardrail.Response guardrailResp = ActionGuardrail.evaluate(request.location().profile(), guardrailReq);

        Decision decision;
        if (guardrailResp.outcome() == ActionGuardrail.Outcome.ALLOWED) {
            decision = Decision.ALLOW;
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.BLOCKED) {
            decision = Decision.BLOCKED;
        } else if (guardrailResp.outcome() == ActionGuardrail.Outcome.WARNING) {
            decision = Decision.WARNING;
        } else {
            decision = Decision.UNKNOWN;
        }

        if (decision != Decision.ALLOW) {
            return failure(decision, null, null, "Action evaluation denied mutation: " + guardrailResp.message());
        }

        // 6. Execute mutation in assigned worktree ONLY
        Path targetFile = assignedWorktree.resolve(resolvedRelative).toAbsolutePath().normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return failure(Decision.BLOCKED, null, null, "Path escape detected outside assigned worktree");
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

        String evidenceHash;
        String decisionId = "dec-" + UUID.randomUUID();
        try {
            Files.createDirectories(targetFile.getParent());
            byte[] contentBytes = request.newContent() == null ? new byte[0] : request.newContent().getBytes(StandardCharsets.UTF_8);
            atomicWrite(targetFile, contentBytes);
            evidenceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contentBytes));

            recordEvidence(request.location(), binding, decisionId, evidenceHash, resolvedRelative);
        } catch (Exception failure) {
            return failure(Decision.BLOCKED, null, null, "Failed to apply mutation: " + failure.getMessage());
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
            return failure(Decision.BLOCKED, decisionId, evidenceHash, "Control checkout was modified during mutation");
        }

        return new MutationResult(true, Decision.ALLOW, decisionId, evidenceHash, "Mutation successful", targetFile, true);
    }

    private static void recordEvidence(ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding, String decisionId, String evidenceHash, String relativePath) throws IOException {
        Path evidenceDir = location.synesisDirectory().resolve("local").resolve("evidence").resolve(binding.provider());
        Files.createDirectories(evidenceDir);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("decisionId", decisionId);
        record.put("sessionId", binding.sessionId());
        record.put("provider", binding.provider());
        record.put("interceptionEvidence", evidenceHash);
        record.put("relativePath", relativePath);
        record.put("hookIntercepted", true);
        record.put("decision", "ALLOW");
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

    private static MutationResult failure(Decision decision, String decisionId, String evidence, String message) {
        return new MutationResult(false, decision, decisionId, evidence, message, null, true);
    }
}
