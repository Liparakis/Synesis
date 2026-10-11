package org.synesis.workspace.application.workspace.mutation;

import org.synesis.workspace.application.workspace.ReadinessResult;

import org.synesis.workspace.application.workspace.WorkspacePathPolicy;

import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;
import org.synesis.workspace.project.ProjectPathResolver;

/**
 * Application service for evaluating and applying structured file creation and modification
 * patches.
 *
 * <p>Supports creation and multi-edit modification modes. Evaluates proposed content atomically
 * through {@link WorkspaceMutationBroker} and translates decisions concisely via
 * {@link AgentOutcomeTranslator}.
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
        if (request == null || request.relativePath() == null || request.relativePath().isBlank()) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 1. Session & Worktree Verification
        Path root = request.controlRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return WorkspaceReadinessService.workspaceNotReady();
        }

        ProjectLocation location;
        ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            readiness = readinessService.assessMutation(location, request.provider(), request.connectionInstanceId(), request.relativePath());
        } catch (Exception ex) {
            return WorkspaceReadinessService.workspaceNotReady();
        }
        if (!readiness.ready()) {
            return readiness.response();
        }
        Path assignedWorktree = readiness.worktree();

        // 2. Relative Path & Traversal Validation
        String rawPath = request.relativePath();
        if (WorkspacePathPolicy.isAbsolute(rawPath) || WorkspacePathPolicy.containsTraversal(rawPath)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, rawPath);
        } catch (Exception ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // Protected internal target check (.synesis, .codex, .git)
        if (WorkspacePathPolicy.isProtectedTarget(resolvedRelative)) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.PROTECTED_CONFIGURATION, null, null);
        }

        String collaborationReason = collaborationService.mutationReason(root, request.provider(), request.connectionInstanceId(), resolvedRelative);
        if (!"allowed".equals(collaborationReason)) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.fromValue(collaborationReason), AgentNextAction.ENSURE_SESSION, null);
        }

        Path targetFile = assignedWorktree.resolve(resolvedRelative).toAbsolutePath().normalize();
        try {
            if (!WorkspacePathPolicy.targetResolvesWithinWorktree(assignedWorktree,
                    targetFile)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
        } catch (IOException invalidTarget) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }
        if (!Files.exists(targetFile) && Files.exists(root.resolve(resolvedRelative))) {
            return WorkspaceReadinessService.workspaceMismatch(root, assignedWorktree);
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

            if (request.expectedHash() == null || request.expectedHash().isBlank()) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.PATCH_PRECONDITION_REQUIRED, AgentNextAction.RETRY,
                        Map.of("instruction", "Changing an existing file needs expectedHash: the contentHash read_file returned for it."));
            }

            if (request.edits() == null || request.edits().isEmpty()) {
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

            if (!actualHash.equalsIgnoreCase(request.expectedHash().trim())) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.FILE_REVISION_STALE, AgentNextAction.RETRY,
                        Map.of("instruction", "The file changed after that hash was read. Read it again with read_file and use the new contentHash."));
            }

            // Apply edits against one deterministic snapshot
            String snapshot = currentContent;
            for (int index = 0; index < request.edits().size(); index++) {
                PatchEdit edit = request.edits().get(index);
                int count = countOccurrences(snapshot, edit.find());
                if (count != edit.expectedOccurrences()) {
                    // Which edit missed, and by how much, is what the caller needs to repair it.
                    Map<String, Object> mismatch = new java.util.LinkedHashMap<>();
                    mismatch.put("editIndex", index);
                    mismatch.put("expectedOccurrences", edit.expectedOccurrences());
                    mismatch.put("actualOccurrences", count);
                    mismatch.put("instruction", "The find text of this edit does not occur the expected number of times. Read the file again and copy the text to replace exactly, "
                            + "or set expectedOccurrences to the number of places it should change. Nothing was written.");
                    return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.PATCH_CONTEXT_MISMATCH, AgentNextAction.RETRY, mismatch);
                }
                snapshot = snapshot.replace(edit.find(), edit.replace());
            }
            if (document.lineEndingStyle() == TextFileDocument.LineEndingStyle.MIXED) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.MIXED_LINE_ENDINGS_REQUIRES_REVIEW, AgentNextAction.RETRY, null);
            }
            final byte[] proposedBytes;
            try {
                proposedBytes = document.encode(snapshot);
            } catch (IOException ex) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.MIXED_LINE_ENDINGS_REQUIRES_REVIEW, AgentNextAction.RETRY, null);
            }
            proposedNewContent = snapshot;

            WorkspaceMutationBroker.MutationRequest mutReq = new WorkspaceMutationBroker.MutationRequest(location,
                    request.provider(),
                    request.connectionInstanceId(),
                    resolvedRelative,
                    "synesis.apply_patch",
                    proposedNewContent,
                    proposedBytes,
                    true,
                    false,
                    request.expectedHash().trim(),
                    false);
            WorkspaceMutationBroker.MutationResult mutResult = mutationBroker.applyMutation(mutReq);
            TranslatedOutcome outcome = translator.translateMutationResult(mutResult, resolvedRelative);
            return outcome.publicResponse();
        }

        // 3. Evaluate and Apply Mutation through WorkspaceMutationBroker
        WorkspaceMutationBroker.MutationRequest mutReq = new WorkspaceMutationBroker.MutationRequest(location,
                request.provider(),
                request.connectionInstanceId(),
                resolvedRelative,
                "synesis.apply_patch",
                proposedNewContent,
                null,
                true,  // hookIntercepted = true
                false, // isSyntheticCheck = false
                null,
                true
        );

        WorkspaceMutationBroker.MutationResult mutResult = mutationBroker.applyMutation(mutReq);
        TranslatedOutcome outcome = translator.translateMutationResult(mutResult, resolvedRelative);
        return outcome.publicResponse();
    }

}
