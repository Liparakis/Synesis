package org.synesis.workspace.application.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.agent.AgentWorkspaceGuidance;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.ReviewSnapshotAccessService;
import org.synesis.workspace.infrastructure.filesystem.TextFileDocument;
import org.synesis.workspace.project.ProjectPathResolver;

/**
 * Application service for executing safe, bounded, session-verified workspace file reads.
 *
 * <p>Reads exclusively from the session's assigned Git worktree, enforcing path containment,
 * symlink safety, binary detection, UTF-8 bounding, and deterministic line-range slicing.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class WorkspaceReadService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final ReviewSnapshotAccessService reviewSnapshotAccessService;

    /**
     * Creates a workspace read service instance.
     */
    public WorkspaceReadService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.reviewSnapshotAccessService = new ReviewSnapshotAccessService();
    }

    private static boolean isInvalidOrProtectedPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        try {
            if (Path.of(path)
                    .isAbsolute() || path.contains("..")) {
                return true;
            }
        } catch (RuntimeException invalidPath) {
            return true;
        }
        String normalized = path.replace('\\', '/')
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith(".synesis/") || normalized.startsWith(".codex/")
                || normalized.startsWith(".git/")
                || normalized.equals(".synesis") || normalized.equals(".codex")
                || normalized.equals(".git");
    }

    private static void addReviewMetadata(Map<String, Object> result,
            ReviewSnapshotAccessService.Access reviewAccess) {
        if (reviewAccess == null) {
            return;
        }
        result.put("workspace", "immutable_review_snapshot");
        result.put("reviewGrantId",
                reviewAccess.grant()
                        .grantId()
                        .toString());
        result.put("reviewSnapshotId",
                reviewAccess.snapshot()
                        .snapshotId());
        result.put("reviewCommitSha",
                reviewAccess.snapshot()
                        .commitSha());
    }

    private static AgentResponse workspaceMismatch(Path controlRoot, Path assignedWorktree) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.WORKSPACE_MISMATCH, null,
                new AgentWorkspaceGuidance(controlRoot.toString(), assignedWorktree.toString(),
                        "The target exists in the control checkout, not this assigned worktree. "
                                + "Stop native mutations and relaunch the provider from assignedWorktree."));
    }

    private static boolean isBinary(byte[] bytes) {
        int checkLen = Math.min(bytes.length, 8192);
        for (int i = 0; i < checkLen; i++) {
            byte b = bytes[i];
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static String truncateUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Executes a session-bound file read.
     *
     * @param request read request parameters
     * @return concise agent response
     */
    public AgentResponse readFile(ReadRequest request) {
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

        String rawPath = request.relativePath();
        if (isInvalidOrProtectedPath(rawPath)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        ProjectApplicationService.ProjectLocation location;
        ReviewSnapshotAccessService.Access reviewAccess = null;
        Path assignedWorktree;
        try {
            location = projectService.locate(root);
            ReviewSnapshotAccessService.AccessResult reviewResolution = reviewSnapshotAccessService.resolve(
                    root, request.provider(), request.connectionInstanceId());
            if (reviewResolution.denied()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                        AgentNextAction.RETRY, Map.of("error", reviewResolution.error()));
            }
            if (reviewResolution.available()) {
                reviewAccess = reviewResolution.access();
                assignedWorktree = reviewAccess.worktreePath();
            } else {
                WorkspaceReadinessService.ReadinessResult readiness = readinessService.assess(
                        location, request.provider(), request.connectionInstanceId());
                if (!readiness.ready()) {
                    return readiness.response();
                }
                assignedWorktree = readiness.worktree();
            }
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED,
                    AgentReason.WORKSPACE_NOT_READY,
                    AgentNextAction.ENSURE_SESSION,
                    null);
        }

        // 2. Relative Path & Traversal Validation
        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, rawPath);
        } catch (Exception ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // Protected internal path check (.synesis, .codex, .git)
        if (isInvalidOrProtectedPath(resolvedRelative)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        Path targetFile = assignedWorktree.resolve(resolvedRelative)
                .toAbsolutePath()
                .normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }
        if (!Files.exists(targetFile) && Files.isRegularFile(root.resolve(resolvedRelative))) {
            return workspaceMismatch(root, assignedWorktree);
        }

        // A valid repository-relative target may be intentionally absent in a
        // fresh isolated lane. Report that state as createable rather than
        // misclassifying it as an invalid path; mutation authorization remains
        // enforced by WorkspacePatchService and the active claim epoch.
        if (!Files.exists(targetFile)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("path", resolvedRelative);
            missing.put("exists", false);
            missing.put("content", "");
            missing.put("contentHash", "");
            missing.put("truncated", false);
            missing.put("createAllowed", true);
            addReviewMetadata(missing, reviewAccess);
            return new AgentResponse(AgentStatus.COMPLETED, null, null, missing);
        }

        // Symlink Escape Check
        try {
            Path canonicalAssigned = assignedWorktree.toRealPath();
            if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
            if (!targetFile.toRealPath()
                    .startsWith(canonicalAssigned)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
        } catch (IOException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 3. Binary File Check
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(targetFile);
        } catch (IOException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        if (isBinary(bytes)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 4. Logical LF text with raw-byte revision preservation
        final TextFileDocument document;
        try {
            document = TextFileDocument.decode(bytes);
        } catch (IOException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }
        String fullContent = document.logicalText();
        List<String> lines = fullContent.lines()
                .toList();

        int start = (request.startLine() == null || request.startLine() < 1) ? 1 : request.startLine();
        if (start > lines.size() && !lines.isEmpty()) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("path", resolvedRelative);
            res.put("content", "");
            res.put("contentHash", document.revision());
            res.put("truncated", true);
            addReviewMetadata(res, reviewAccess);
            return new AgentResponse(AgentStatus.COMPLETED, null, null, res);
        }

        int end = (request.endLine() == null || request.endLine() > lines.size()) ? lines.size() : request.endLine();
        if (end < start) {
            end = start - 1;
        }

        boolean lineSliced = (start > 1) || (request.endLine() != null && request.endLine() < lines.size());
        String slicedContent;
        if (!lineSliced) {
            slicedContent = fullContent;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = start - 1; i < end && i < lines.size(); i++) {
                sb.append(lines.get(i))
                        .append("\n");
            }
            slicedContent = sb.toString();
        }

        int requestedMax =
                (request.maxBytes() == null || request.maxBytes() <= 0) ? 65536 : Math.min(request.maxBytes(), 65536);
        byte[] slicedBytes = slicedContent.getBytes(StandardCharsets.UTF_8);

        boolean truncated = lineSliced || (slicedBytes.length > requestedMax);
        String finalContent;
        if (slicedBytes.length > requestedMax) {
            finalContent = truncateUtf8(slicedContent, requestedMax);
        } else {
            finalContent = slicedContent;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", resolvedRelative);
        result.put("content", finalContent);
        result.put("contentHash", document.revision());
        result.put("truncated", truncated);
        addReviewMetadata(result, reviewAccess);

        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    /**
     * Request parameters for reading a repository file.
     *
     * @param controlRoot          canonical control project root path
     * @param provider             stable provider name
     * @param connectionInstanceId unique process connection instance ID
     * @param relativePath         repository-relative file path
     * @param startLine            1-based starting line number (optional)
     * @param endLine              1-based ending line number (optional)
     * @param maxBytes             maximum UTF-8 bytes to return (optional)
     */
    public record ReadRequest(
            Path controlRoot,
            String provider,
            String connectionInstanceId,
            String relativePath,
            Integer startLine,
            Integer endLine,
            Integer maxBytes
    ) {

        /**
         * Validates request parameters.
         */
        public ReadRequest {
            Objects.requireNonNull(controlRoot, "controlRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }
    }
}
