package org.synesis.workspace.application.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentWorkspaceGuidance;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;
import org.synesis.workspace.project.ProjectPathResolver;

/**
 * Application service for evaluating and applying structured file creation and modification patches.
 *
 * <p>Supports creation and multi-edit modification modes. Evaluates proposed content atomically
 * through {@link WorkspaceMutationBroker} and translates decisions concisely via {@link AgentOutcomeTranslator}.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class WorkspacePatchService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final WorkspaceMutationBroker mutationBroker;
    private final AgentOutcomeTranslator translator;
    private final WorkspaceCollaborationService collaborationService;

    /**
     * Creates a workspace patch service instance.
     */
    public WorkspacePatchService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.mutationBroker = new WorkspaceMutationBroker();
        this.translator = new AgentOutcomeTranslator();
        this.collaborationService = new WorkspaceCollaborationService();
    }

    private static AgentResponse workspaceMismatch(Path controlRoot, Path assignedWorktree) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_MISMATCH, null,
                new AgentWorkspaceGuidance(controlRoot.toString(), assignedWorktree.toString(),
                        "The target exists in the control checkout, not this assigned worktree. "
                                + "Stop native mutations and relaunch the provider from assignedWorktree."));
    }

    private static int countOccurrences(String text, String find) {
        if (text == null || find == null || find.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) != -1) {
            count++;
            idx += find.length();
        }
        return count;
    }

    /**
     * Evaluates and applies a patch request.
     *
     * @param request patch parameters
     * @return concise agent response
     */
    public AgentResponse applyPatch(PatchRequest request) {
        if (request == null || request.relativePath() == null || request.relativePath()
                .isBlank()) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 1. Session & Worktree Verification
        Path root = request.controlRoot()
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        }
        if (!readiness.ready()) {
            return readiness.response();
        }
        Path assignedWorktree = readiness.worktree();

        // 2. Relative Path & Traversal Validation
        String rawPath = request.relativePath();
        if (Path.of(rawPath)
                .isAbsolute() || rawPath.contains("..")) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, rawPath);
        } catch (Exception ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // Protected internal target check (.synesis, .codex, .agents, .git)
        String normTarget = resolvedRelative.replace('\\', '/')
                .toLowerCase();
        if (normTarget.startsWith(".synesis/") || normTarget.startsWith(".codex/")
                || normTarget.startsWith(".agents/") || normTarget.startsWith(".git/")
                || normTarget.equals(".synesis") || normTarget.equals(".codex")
                || normTarget.equals(".agents") || normTarget.equals(".git")) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROTECTED_CONFIGURATION, null, null);
        }

        String collaborationReason = collaborationService.mutationReason(root, request.provider(),
                request.connectionInstanceId(), resolvedRelative);
        if (!"allowed".equals(collaborationReason)) {
            return new AgentResponse(AgentStatus.BLOCKED,
                    AgentReason.fromValue(collaborationReason),
                    AgentNextAction.ENSURE_SESSION, null);
        }

        Path targetFile = assignedWorktree.resolve(resolvedRelative)
                .toAbsolutePath()
                .normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }
        if (!Files.exists(targetFile) && Files.exists(root.resolve(resolvedRelative))) {
            return workspaceMismatch(root, assignedWorktree);
        }

        // Symlink Escape Check on Parent
        try {
            Path canonicalAssigned = assignedWorktree.toRealPath();
            Path existingParent = targetFile;
            while (!Files.exists(existingParent) && existingParent.getParent() != null) {
                existingParent = existingParent.getParent();
            }
            if (!existingParent.toRealPath()
                    .startsWith(canonicalAssigned)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
        } catch (IOException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        String proposedNewContent;

        if (request.create()) {
            // Mode 1: Create
            if (Files.exists(targetFile)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
            proposedNewContent = request.content() == null ? "" : request.content();
        } else {
            // Mode 2: Modify
            if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }

            if (request.expectedHash() == null || request.expectedHash()
                    .isBlank()) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.PATCH_PRECONDITION_REQUIRED,
                        AgentNextAction.RETRY, null);
            }

            if (request.edits() == null || request.edits()
                    .isEmpty()) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }

            byte[] currentBytes;
            try {
                currentBytes = Files.readAllBytes(targetFile);
            } catch (IOException ex) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }

            final TextFileDocument document;
            try {
                document = TextFileDocument.decode(currentBytes);
            } catch (IOException ex) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
            String currentContent = document.logicalText();
            String actualHash = document.revision();

            if (!actualHash.equalsIgnoreCase(request.expectedHash()
                    .trim())) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                        AgentReason.FILE_REVISION_STALE,
                        AgentNextAction.RETRY,
                        null);
            }

            // Apply edits against one deterministic snapshot
            String snapshot = currentContent;
            for (PatchEdit edit : request.edits()) {
                int count = countOccurrences(snapshot, edit.find());
                if (count != edit.expectedOccurrences()) {
                    return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                            AgentReason.PATCH_CONTEXT_MISMATCH,
                            AgentNextAction.RETRY,
                            null);
                }
                snapshot = snapshot.replace(edit.find(), edit.replace());
            }
            if (document.lineEndingStyle() == TextFileDocument.LineEndingStyle.MIXED) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                        AgentReason.MIXED_LINE_ENDINGS_REQUIRES_REVIEW, AgentNextAction.RETRY, null);
            }
            final byte[] proposedBytes;
            try {
                proposedBytes = document.encode(snapshot);
            } catch (IOException ex) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                        AgentReason.MIXED_LINE_ENDINGS_REQUIRES_REVIEW, AgentNextAction.RETRY, null);
            }
            proposedNewContent = snapshot;

            WorkspaceMutationBroker.MutationRequest mutReq = new WorkspaceMutationBroker.MutationRequest(
                    location,
                    request.provider(),
                    request.connectionInstanceId(),
                    resolvedRelative,
                    "synesis.apply_patch",
                    proposedNewContent,
                    proposedBytes,
                    true,
                    false);
            WorkspaceMutationBroker.MutationResult mutResult = mutationBroker.applyMutation(mutReq);
            TranslatedOutcome outcome = translator.translateMutationResult(mutResult, resolvedRelative);
            return outcome.publicResponse();
        }

        // 3. Evaluate and Apply Mutation through WorkspaceMutationBroker
        WorkspaceMutationBroker.MutationRequest mutReq = new WorkspaceMutationBroker.MutationRequest(
                location,
                request.provider(),
                request.connectionInstanceId(),
                resolvedRelative,
                "synesis.apply_patch",
                proposedNewContent,
                null,
                true,  // hookIntercepted = true
                false  // isSyntheticCheck = false
        );

        WorkspaceMutationBroker.MutationResult mutResult = mutationBroker.applyMutation(mutReq);
        TranslatedOutcome outcome = translator.translateMutationResult(mutResult, resolvedRelative);
        return outcome.publicResponse();
    }

    /**
     * Single text edit instruction for modification mode.
     *
     * @param find                text to find
     * @param replace             replacement text
     * @param expectedOccurrences expected exact count of occurrences
     */
    public record PatchEdit(
            String find,
            String replace,
            int expectedOccurrences
    ) {

        /**
         * Validates edit parameters.
         */
        public PatchEdit {
            Objects.requireNonNull(find, "find");
            Objects.requireNonNull(replace, "replace");
            if (expectedOccurrences < 1) {
                throw new IllegalArgumentException("expectedOccurrences must be at least 1");
            }
        }
    }

    /**
     * Parameters for a patch application request.
     *
     * @param controlRoot          canonical control project root path
     * @param provider             stable provider identifier
     * @param connectionInstanceId process connection instance identifier
     * @param relativePath         repository-relative target path
     * @param create               {@code true} for create mode, {@code false} for modify mode
     * @param content              new proposed full content (for create mode)
     * @param expectedHash         SHA-256 hex string of existing content (required for modify mode)
     * @param edits                ordered list of replacement edits (for modify mode)
     */
    public record PatchRequest(
            Path controlRoot,
            String provider,
            String connectionInstanceId,
            String relativePath,
            boolean create,
            String content,
            String expectedHash,
            List<PatchEdit> edits
    ) {

        /**
         * Validates patch request fields.
         */
        public PatchRequest {
            Objects.requireNonNull(controlRoot, "controlRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(relativePath, "relativePath");
        }
    }
}
