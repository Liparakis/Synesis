package org.synesis.workspace.application;

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
import org.synesis.workspace.guardrail.ProjectPathResolver;

/**
 * Application service for executing safe, bounded, session-verified workspace file reads.
 *
 * <p>Reads exclusively from the session's assigned Git worktree, enforcing path containment,
 * symlink safety, binary detection, UTF-8 bounding, and deterministic line-range slicing.
 *
 * @since 1.0
 */
public final class WorkspaceReadService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;

    /**
     * Creates a workspace read service instance.
     */
    public WorkspaceReadService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
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

    /**
     * Executes a session-bound file read.
     *
     * @param request read request parameters
     * @return concise agent response
     */
    public AgentResponse readFile(ReadRequest request) {
        if (request == null || request.relativePath() == null || request.relativePath().isBlank()) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 1. Session & Worktree Verification
        Path root = request.controlRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }
        if (!readiness.ready()) {
            return readiness.response();
        }
        Path assignedWorktree = readiness.worktree();

        // 2. Relative Path & Traversal Validation
        String rawPath = request.relativePath();
        if (Path.of(rawPath).isAbsolute() || rawPath.contains("..")) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        String resolvedRelative;
        try {
            resolvedRelative = ProjectPathResolver.resolve(assignedWorktree, rawPath);
        } catch (Exception ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // Protected internal path check (.synesis, .codex, .agents, .git)
        String normTarget = resolvedRelative.replace('\\', '/').toLowerCase();
        if (normTarget.startsWith(".synesis/") || normTarget.startsWith(".codex/")
                || normTarget.startsWith(".agents/") || normTarget.startsWith(".git/")
                || normTarget.equals(".synesis") || normTarget.equals(".codex")
                || normTarget.equals(".agents") || normTarget.equals(".git")) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        Path targetFile = assignedWorktree.resolve(resolvedRelative).toAbsolutePath().normalize();
        if (!targetFile.startsWith(assignedWorktree)) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // Symlink Escape Check
        try {
            Path canonicalAssigned = assignedWorktree.toRealPath();
            if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                return AgentResponse.blocked(AgentReason.INVALID_PATH);
            }
            if (!targetFile.toRealPath().startsWith(canonicalAssigned)) {
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

        // 4. Line Range & Bounded UTF-8 Output
        String fullContent = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = fullContent.lines().toList();

        int start = (request.startLine() == null || request.startLine() < 1) ? 1 : request.startLine();
        if (start > lines.size() && !lines.isEmpty()) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("path", resolvedRelative);
            res.put("content", "");
            res.put("contentHash", computeSha256Hex(bytes));
            res.put("truncated", true);
            return new AgentResponse(AgentStatus.COMPLETED, null, null, res);
        }

        int end = (request.endLine() == null || request.endLine() > lines.size()) ? lines.size() : request.endLine();
        if (end < start) {
            end = start - 1;
        }

        StringBuilder sb = new StringBuilder();
        boolean lineSliced = (start > 1) || (request.endLine() != null && request.endLine() < lines.size());
        for (int i = start - 1; i < end && i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        String slicedContent = sb.toString();

        int requestedMax = (request.maxBytes() == null || request.maxBytes() <= 0) ? 65536 : Math.min(request.maxBytes(), 65536);
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
        result.put("contentHash", computeSha256Hex(bytes));
        result.put("truncated", truncated);

        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    private static String computeSha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            return "";
        }
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
}
