package org.synesis.workspace.application;

import org.synesis.workspace.application.ProjectApplicationService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.command.DotNetProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.GradleProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.MavenProjectCommandAdapter;
import org.synesis.workspace.infrastructure.command.NpmProjectCommandAdapter;
import org.synesis.workspace.infrastructure.git.GitProjectCommandAdapter;

/**
 * Application service for executing bounded, session-verified project build and git commands
 * inside an assigned worker worktree.
 *
 * @since 1.0
 */
public final class ProjectCommandService {

    private static final int MAX_OUTPUT_BYTES = 65536;

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final List<ProjectCommandAdapter> adapters;

    /**
     * Creates a project command application service with default adapters.
     */
    public ProjectCommandService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.adapters = List.of(
                new GitProjectCommandAdapter(),
                new GradleProjectCommandAdapter(),
                new MavenProjectCommandAdapter(),
                new DotNetProjectCommandAdapter(),
                new NpmProjectCommandAdapter()
        );
    }

    /**
     * Request payload for project command execution.
     *
     * @param projectRoot          control project checkout directory
     * @param provider             provider identifier (e.g., "antigravity", "codex")
     * @param connectionInstanceId active MCP connection identifier
     * @param intent               structured command intent
     */
    public record CommandRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            ProjectCommandIntent intent
    ) {
        /**
         * Validates non-null request components.
         */
        public CommandRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(intent, "intent");
        }
    }

    /**
     * Executes an approved project command intent inside the session's assigned worktree.
     *
     * @param request command request
     * @return concise agent response
     */
    public AgentResponse runCommand(CommandRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
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

        // 2. Validate Command Intent Type
        ProjectCommandIntent intent = request.intent();
        String type = intent.type().toLowerCase(Locale.ROOT);
        boolean isGitCommand = type.startsWith("git_");
        if (!isGitCommand && !List.of("build", "test", "lint", "format_check").contains(type)) {
            return AgentResponse.blocked(AgentReason.TOOL_UNAVAILABLE);
        }

        // 3. Adapter Resolution
        ProjectCommandAdapter selectedAdapter = null;
        if (isGitCommand) {
            selectedAdapter = adapters.stream()
                    .filter(a -> "git".equals(a.id()))
                    .findFirst()
                    .orElse(null);
        } else {
            selectedAdapter = adapters.stream()
                    .filter(a -> !"git".equals(a.id()) && a.supports(assignedWorktree))
                    .findFirst()
                    .orElse(null);
        }

        boolean scriptTest = "test".equals(type) && "run-tests.cmd".equalsIgnoreCase(intent.target())
                && Files.isRegularFile(assignedWorktree.resolve("run-tests.cmd"));
        if (selectedAdapter == null && !scriptTest) {
            return AgentResponse.blocked(AgentReason.TOOL_UNAVAILABLE);
        }

        List<String> commandTokens;
        try {
            if (scriptTest) {
                commandTokens = new ArrayList<>(List.of("cmd.exe", "/d", "/c", "run-tests.cmd"));
            } else {
                commandTokens = new ArrayList<>(selectedAdapter.buildCommandTokens(assignedWorktree, intent));
                if (intent.arguments() != null && !intent.arguments().isEmpty()) {
                    commandTokens.addAll(intent.arguments());
                }
            }
        } catch (IllegalArgumentException ex) {
            return AgentResponse.blocked(AgentReason.INVALID_PATH);
        }

        // 4. Capture Pre-execution Snapshot of Control Checkout & Protected Files
        long controlLastModified = getDirectoryLastModified(location.root());

        // 5. Execute Process
        int timeoutSeconds = isGitCommand ? 15 : 60;
        Process process = null;
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();

        try {
            ProcessBuilder pb = new ProcessBuilder(commandTokens);
            pb.directory(assignedWorktree.toFile());

            // Filtered environment
            Map<String, String> env = pb.environment();
            List<String> keysToRemove = new ArrayList<>();
            for (String key : env.keySet()) {
                String upper = key.toUpperCase(Locale.ROOT);
                if (upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("KEY") || upper.contains("AUTH")) {
                    keysToRemove.add(key);
                }
            }
            for (String k : keysToRemove) {
                env.remove(k);
            }

            process = pb.start();

            InputStream procOut = process.getInputStream();
            InputStream procErr = process.getErrorStream();

            Thread outThread = new Thread(() -> readBoundedStream(procOut, stdoutBuffer));
            Thread errThread = new Thread(() -> readBoundedStream(procErr, stderrBuffer));
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
                outThread.interrupt();
                errThread.interrupt();
                return new AgentResponse(AgentStatus.FAILED, AgentReason.COMMAND_TIMEOUT, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            outThread.join(1000);
            errThread.join(1000);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                killProcessTree(process);
            }
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        } catch (Exception ex) {
            if (process != null) {
                killProcessTree(process);
            }
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }

        // 6. Post-execution Control Checkout Non-mutation Verification
        long controlAfterModified = getDirectoryLastModified(location.root());
        if (controlAfterModified > controlLastModified) {
            return AgentResponse.blocked(AgentReason.PROTECTED_CONFIGURATION);
        }

        int exitCode = process.exitValue();
        String stdoutText = sanitizeOutput(stdoutBuffer.toString(StandardCharsets.UTF_8), location.root(), assignedWorktree);
        String stderrText = sanitizeOutput(stderrBuffer.toString(StandardCharsets.UTF_8), location.root(), assignedWorktree);

        // 7. Result Construction & Concise Response Serialization
        if (exitCode == 0) {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("command", intent.type());
            resultMap.put("exitCode", 0);
            if (isGitCommand) {
                resultMap.put("output", stdoutText.isBlank() ? stderrText : stdoutText);
            } else {
                String summary = extractSummary(intent.type(), stdoutText, stderrText);
                resultMap.put("summary", summary);
            }
            return new AgentResponse(AgentStatus.COMPLETED, null, null, resultMap);
        } else {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("command", intent.type());
            resultMap.put("exitCode", exitCode);
            String summary = extractSummary(intent.type(), stdoutText, stderrText);
            resultMap.put("summary", summary.isBlank() ? "Command failed with exit code " + exitCode : summary);
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COMMAND_FAILED, null, resultMap);
        }
    }

    private static void killProcessTree(Process process) {
        if (process == null) return;
        try {
            process.descendants().forEach(ph -> ph.destroyForcibly());
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static void readBoundedStream(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                if (total + n <= MAX_OUTPUT_BYTES) {
                    out.write(buf, 0, n);
                    total += n;
                } else {
                    int remaining = MAX_OUTPUT_BYTES - total;
                    if (remaining > 0) {
                        out.write(buf, 0, remaining);
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static long getDirectoryLastModified(Path path) {
        try {
            if (!Files.exists(path)) return 0L;
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static String sanitizeOutput(String raw, Path controlRoot, Path assignedWorktree) {
        if (raw == null) return "";
        String text = raw;
        if (controlRoot != null) {
            text = text.replace(controlRoot.toAbsolutePath().normalize().toString(), "[PROJECT_ROOT]");
        }
        if (assignedWorktree != null) {
            text = text.replace(assignedWorktree.toAbsolutePath().normalize().toString(), "[WORKTREE_ROOT]");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            text = text.replace(home, "~");
        }
        if (text.length() > 4096) {
            text = text.substring(0, 4096) + "\n...[truncated]";
        }
        return text.trim();
    }

    private static String extractSummary(String type, String stdout, String stderr) {
        String combined = (stdout + "\n" + stderr).trim();
        if (combined.isBlank()) {
            return type + " completed successfully";
        }
        String[] lines = combined.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isBlank() && !line.startsWith("BUILD") && !line.startsWith("Progress")) {
                if (line.length() > 128) {
                    return line.substring(0, 128);
                }
                return line;
            }
        }
        return type + " completed";
    }
}
