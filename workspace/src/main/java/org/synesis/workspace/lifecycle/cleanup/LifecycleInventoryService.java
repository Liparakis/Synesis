package org.synesis.workspace.lifecycle.cleanup;

import org.synesis.workspace.lifecycle.GitProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Discovers and inventories all on-disk and virtual Synesis lifecycle resources for a project.
 *
 * <p>This service performs strictly read-only discovery over filesystem directories, session store records,
 * snapshot files, evidence logs, temporary files, and Git worktree registrations.
 *
 * @since 1.0
 */
public final class LifecycleInventoryService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;

    /**
     * Discovered raw lifecycle resource candidate before eligibility classification.
     *
     * @param type              type of lifecycle resource
     * @param id                stable candidate identifier
     * @param path              on-disk filesystem path, or {@code null} if virtual
     * @param durableReferences list of linked durable record IDs (session, task, request handle)
     * @param estimatedBytes    estimated size on disk in bytes
     * @param gitBranch         associated Git branch, if applicable
     * @param lastModifiedTime  last modified epoch millisecond timestamp
     * @param pid               associated process PID, if applicable
     */
    public record DiscoveredResource(
            LifecycleResourceType type,
            String id,
            Path path,
            List<String> durableReferences,
            long estimatedBytes,
            String gitBranch,
            long lastModifiedTime,
            Long pid
    ) {
        /**
         * Validates non-null invariants.
         */
        public DiscoveredResource {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(durableReferences, "durableReferences");
        }
    }

    /**
     * Creates an inventory service using default application services.
     */
    public LifecycleInventoryService() {
        this(new ProjectApplicationService(), new ProviderSessionBindingService());
    }

    /**
     * Creates an inventory service with explicit application services.
     *
     * @param projectService application service for project location
     * @param bindingService application service for provider session bindings
     */
    public LifecycleInventoryService(ProjectApplicationService projectService, ProviderSessionBindingService bindingService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    /**
     * Discovers all candidate lifecycle resources associated with the control project.
     *
     * @param controlRoot control project root directory
     * @return list of discovered candidate resources
     * @throws IOException if directory inspection fails
     */
    public List<DiscoveredResource> discoverResources(Path controlRoot) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectApplicationService.ProjectLocation location;
        try {
            location = projectService.locate(root);
        } catch (Exception ex) {
            return List.of();
        }

        List<DiscoveredResource> results = new ArrayList<>();
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);

        // 1. Session Bindings & Worker Worktrees
        Path sessionsDir = location.synesisDirectory().resolve("local").resolve("sessions");
        Map<String, ProviderSessionBindingService.Binding> activeSessions = new LinkedHashMap<>();
        if (Files.isDirectory(sessionsDir)) {
            try (var stream = Files.list(sessionsDir)) {
                for (Path sessionFile : stream.filter(p -> p.getFileName().toString().endsWith(".json") && !p.getFileName().toString().startsWith("verification-")).toList()) {
                    try {
                        ProviderSessionBindingService.Binding binding = readBinding(sessionFile);
                        activeSessions.put(binding.sessionId(), binding);
                        results.add(new DiscoveredResource(
                                LifecycleResourceType.PROVIDER_SESSION,
                                binding.sessionId(),
                                sessionFile,
                                List.of(binding.projectId(), binding.nodeId(), binding.provider()),
                                Files.size(sessionFile),
                                binding.branch(),
                                binding.lastSeenEpochMillis(),
                                null
                        ));

                        if (binding.worktreePath() != null) {
                            Path wtPath = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
                            long size = estimateDirectorySize(wtPath);
                            results.add(new DiscoveredResource(
                                    LifecycleResourceType.WORKER_WORKTREE,
                                    "worktree-" + binding.sessionId(),
                                    wtPath,
                                    List.of(binding.sessionId(), binding.projectId(), binding.provider()),
                                    size,
                                    binding.branch(),
                                    binding.lastSeenEpochMillis(),
                                    null
                            ));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 2. Discover External Workspace Subdirectories (Worker, Validation, Integration, Unlinked)
        if (Files.isDirectory(workspaceRoot)) {
            Path wtDir = workspaceRoot.resolve("worktrees");
            if (Files.isDirectory(wtDir)) {
                try (var stream = Files.list(wtDir)) {
                    for (Path dir : stream.filter(Files::isDirectory).toList()) {
                        String name = dir.getFileName().toString();
                        if (!activeSessions.containsKey(name)) {
                            results.add(new DiscoveredResource(
                                    LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE,
                                    "unlinked-wt-" + name,
                                    dir,
                                    List.of(name),
                                    estimateDirectorySize(dir),
                                    null,
                                    getLastModified(dir),
                                    null
                            ));
                        }
                    }
                }
            }

            Path valDir = workspaceRoot.resolve("validation");
            if (Files.isDirectory(valDir)) {
                try (var stream = Files.list(valDir)) {
                    for (Path dir : stream.filter(Files::isDirectory).toList()) {
                        String name = dir.getFileName().toString();
                        results.add(new DiscoveredResource(
                                LifecycleResourceType.VALIDATION_WORKTREE,
                                "val-" + name,
                                dir,
                                List.of(name),
                                estimateDirectorySize(dir),
                                null,
                                getLastModified(dir),
                                null
                        ));
                    }
                }
            }

            Path intgDir = workspaceRoot.resolve("integration");
            if (Files.isDirectory(intgDir)) {
                try (var stream = Files.list(intgDir)) {
                    for (Path dir : stream.filter(Files::isDirectory).toList()) {
                        String name = dir.getFileName().toString();
                        results.add(new DiscoveredResource(
                                LifecycleResourceType.INTEGRATION_WORKTREE,
                                "intg-" + name,
                                dir,
                                List.of(name),
                                estimateDirectorySize(dir),
                                null,
                                getLastModified(dir),
                                null
                        ));
                    }
                }
            }
        }

        // 3. Registered Git Worktrees & Dangling Worktree Entries
        try {
            List<GitWorktreeEntry> gitWorktrees = listGitWorktrees(root);
            for (GitWorktreeEntry gw : gitWorktrees) {
                Path wtPath = Path.of(gw.path()).toAbsolutePath().normalize();
                if (!Files.exists(wtPath)) {
                    results.add(new DiscoveredResource(
                            LifecycleResourceType.DANGLING_GIT_WORKTREE,
                            "dangling-git-wt-" + gw.branch(),
                            wtPath,
                            List.of(gw.branch(), gw.headCommit()),
                            0L,
                            gw.branch(),
                            0L,
                            null
                    ));
                }
            }
        } catch (Exception ignored) {
        }

        // 4. Snapshots (Task Snapshots & Implementation Snapshots)
        Path snapshotsDir = location.synesisDirectory().resolve("local").resolve("snapshots");
        if (Files.isDirectory(snapshotsDir)) {
            try (var stream = Files.list(snapshotsDir)) {
                for (Path snapshotFile : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    String filename = snapshotFile.getFileName().toString();
                    LifecycleResourceType type = filename.startsWith("task-")
                            ? LifecycleResourceType.TASK_SNAPSHOT
                            : LifecycleResourceType.IMPLEMENTATION_SNAPSHOT;
                    results.add(new DiscoveredResource(
                            type,
                            filename,
                            snapshotFile,
                            List.of(filename),
                            Files.size(snapshotFile),
                            null,
                            getLastModified(snapshotFile),
                            null
                    ));
                }
            }
        }

        // 5. Diagnostic Evidence Files
        Path evidenceDir = location.synesisDirectory().resolve("local").resolve("evidence");
        if (Files.isDirectory(evidenceDir)) {
            try (var stream = Files.walk(evidenceDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    results.add(new DiscoveredResource(
                            LifecycleResourceType.DIAGNOSTIC_EVIDENCE,
                            file.getFileName().toString(),
                            file,
                            List.of("evidence"),
                            Files.size(file),
                            null,
                            getLastModified(file),
                            null
                    ));
                }
            }
        }

        // 6. Temporary Patch/Workspace Files
        Path localDir = location.synesisDirectory().resolve("local");
        if (Files.isDirectory(localDir)) {
            try (var stream = Files.list(localDir)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().contains(".tmp-") || p.getFileName().toString().endsWith(".patch")).toList()) {
                    results.add(new DiscoveredResource(
                            LifecycleResourceType.TEMPORARY_FILE,
                            file.getFileName().toString(),
                            file,
                            List.of("temp"),
                            Files.size(file),
                            null,
                            getLastModified(file),
                            null
                    ));
                }
            }
        }

        return Collections.unmodifiableList(results);
    }

    private record GitWorktreeEntry(String path, String headCommit, String branch) {}

    private static List<GitWorktreeEntry> listGitWorktrees(Path controlRoot) {
        try {
            String output = GitProcessRunner.run(controlRoot, "worktree", "list", "--porcelain");

            List<GitWorktreeEntry> entries = new ArrayList<>();
            String currentPath = null;
            String currentHead = null;
            String currentBranch = null;

            for (String line : output.lines().toList()) {
                if (line.startsWith("worktree ")) {
                    if (currentPath != null) {
                        entries.add(new GitWorktreeEntry(currentPath, currentHead != null ? currentHead : "", currentBranch != null ? currentBranch : ""));
                    }
                    currentPath = line.substring("worktree ".length()).trim();
                    currentHead = null;
                    currentBranch = null;
                } else if (line.startsWith("HEAD ")) {
                    currentHead = line.substring("HEAD ".length()).trim();
                } else if (line.startsWith("branch refs/heads/")) {
                    currentBranch = line.substring("branch refs/heads/".length()).trim();
                }
            }
            if (currentPath != null) {
                entries.add(new GitWorktreeEntry(currentPath, currentHead != null ? currentHead : "", currentBranch != null ? currentBranch : ""));
            }
            return entries;
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static long estimateDirectorySize(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        if (Files.isRegularFile(path)) {
            try {
                return Files.size(path);
            } catch (IOException ex) {
                return 0L;
            }
        }
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException failure) {
            return 0L;
        }
    }

    private static long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException failure) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static ProviderSessionBindingService.Binding readBinding(Path path) throws IOException {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(Files.readString(path));
        return new ProviderSessionBindingService.Binding(
                ((Number) map.get("schemaVersion")).intValue(),
                (String) map.get("sessionId"),
                (String) map.get("projectId"),
                (String) map.get("nodeId"),
                (String) map.get("provider"),
                (String) map.get("providerInstanceFingerprint"),
                (String) map.get("supervisorId"),
                (String) map.get("workerId"),
                (String) map.get("worktreeId"),
                (String) map.get("worktreePath"),
                (String) map.get("controlCheckoutPath"),
                (String) map.get("branch"),
                (String) map.get("baseCommit"),
                (String) map.get("gitCommonDir"),
                (String) map.get("creationState"),
                (String) map.get("verificationState"),
                (String) map.get("lastSeenState"),
                (String) map.get("status"),
                ((Number) map.get("createdAtEpochMillis")).longValue(),
                ((Number) map.get("lastSeenEpochMillis")).longValue(),
                ((Number) map.get("lastVerifiedProjectSequence")).longValue(),
                (String) map.get("providerTrustState"),
                ((Number) map.get("bindingVersion")).intValue(),
                (String) map.get("completedAt")
        );
    }
}
