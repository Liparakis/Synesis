package org.synesis.workspace.application;

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.projectrecord.domain.ProjectConfig;

import org.synesis.workspace.application.project.ProjectCommandSpec;
import org.synesis.workspace.application.project.ProjectCreateResult;
import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectInitStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.filesystem.ProjectMetadataStorage;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;

/**
 * Owns project discovery, initialization, and project-local profile paths.
 *
 * <p>The service is synchronous, process-safe for one initializer, and does
 * not print or depend on a command-line framework. Shareable metadata is kept in
 * {@code project.json}; identity and runtime state stay below {@code .synesis/local}.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class ProjectApplicationService {

    /**
     * Current project metadata schema.
     */
    public static final int PROJECT_SCHEMA_VERSION = 2;
    private static final String SYNESIS_DIRECTORY = ProjectMetadataStorage.DIRECTORY_NAME;
    private static final String PROJECT_FILE = ProjectMetadataStorage.PROJECT_METADATA_FILE;
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String AGENTS_BEGIN = "<!-- SYNESIS-BEGIN -->";
    private static final String AGENTS_END = "<!-- SYNESIS-END -->";
    private static final String AGENTS_BODY = """
            ## Synesis

            This repository uses Synesis.

            - Use Synesis tools for project reads, file changes, and commands.
            - One persistent MCP connection owns one provider binding and one isolated worker context.
            - Before the first MCP session after `synesis init`, install the integration matching the responding harness in the control project directory: Claude Code uses `synesis provider install claude`, and Codex uses `synesis provider install codex`. The matching `claude` or `codex` executable must be installed and available to that harness. Once `ensure_session` is ready, continue with that provider binding; do not rerun provider installation from the assigned isolated worktree, which may not contain the control project profile.
            - After synesis.ensure_session succeeds, keep the provider in the current project directory and use Synesis MCP for all reads, writes, and commands. Synesis applies those operations internally in the assigned worktree; do not switch branches, cd, or edit another worker's worktree manually. Native provider hooks are optional and may be unavailable in desktop harnesses, but they do not replace the required Synesis integration, so never assume a native mutation was routed. If a native tool is used and Synesis reports workspace_mismatch, stop native mutations and verify state through synesis.read_file.
            - Reads carry revisions; provide the matching revision when applying a patch.
            - Do not modify the control checkout or another worker's files directly.
            - When Synesis reports an identity, ownership, freshness, or workspace failure, stop mutation and inspect read-only state.
            - The MCP surface currently contains exactly 10 tools; follow get_next_action's recommended tool and typed arguments.
            - A ready session is not yet an announced collaboration intent. Before mutating visible task files, call ensure_session with task.goal, task.acceptance, and task.claims containing the exact repository-relative files or subtrees assigned to this responsibility. Use claim kind path_exact or path_subtree, keep claims disjoint, and do not guess or overlap ownership. This is the existing intent/claim announcement; there is no separate announce tool. likelyScopes is descriptive only and does not acquire ownership.
            - Call get_next_action with no arguments to read the durable coordination inbox. Its optional integrationCheck input only evaluates explicitly supplied compatibility facts; it never advances a lane or WorkGroup, cannot close or validate work, and must not replace empty-argument polling.
            - A mutating Synesis tool may return a required continuation. When its result contains a concrete nextAction/recommendedTool and typed arguments, execute that exact tool with those exact arguments before ending the turn or choosing another Synesis lifecycle action; the continuation is part of the durable protocol, not an optional suggestion.
            - After any completed Synesis mutation, call get_next_action again before ending the turn; a successful coordination or publication mutation may create the next durable inbox action even when the mutation result has no explicit continuation.
            - When get_next_action reports IMPLEMENT without a concrete recommendedTool and typed arguments, continue the visible assigned coding work normally; do not call finish_lane or another lifecycle tool merely because the coding appears complete. Execute lifecycle actions only when get_next_action projects the exact tool and arguments, then return to get_next_action after coding progress.
            - If the assigned visible work is complete or blocked and the WorkGroup is still active or another participant remains active, do not end the session when IMPLEMENT has no concrete action. Perform a bounded wait and call get_next_action again until Synesis projects an exact lifecycle action or a terminal state; do not invent a lifecycle action.
            - When get_next_action projects WAIT with recommendedTool=get_next_action and arguments {}, execute that continuation after a bounded wait and remain engaged while the WorkGroup is active or grants, snapshots, review decisions, or coordination requests remain unresolved. Do not report success or stop merely because your own lane is complete.
            """;
    private final ManagedBaselineTransactionService baselineService;
    private final ProjectMetadataStorage metadataStorage;

    /**
     * Creates a project application service.
     */
    public ProjectApplicationService() {
        this(new ManagedBaselineTransactionService(), new ProjectMetadataStorage());
    }

    /**
     * Creates a project service with an explicit baseline transaction service.
     *
     * @param baselineService managed-baseline transaction service
     */
    public ProjectApplicationService(ManagedBaselineTransactionService baselineService) {
        this(baselineService, new ProjectMetadataStorage());
    }

    ProjectApplicationService(ManagedBaselineTransactionService baselineService, ProjectMetadataStorage metadataStorage) {
        this.baselineService = Objects.requireNonNull(baselineService, "baselineService");
        this.metadataStorage = Objects.requireNonNull(metadataStorage, "metadataStorage");
    }

    private void ensureMetadataStorage(Path projectRoot) throws ProjectApplicationException {
        try {
            metadataStorage.ensure(projectRoot);
        } catch (IOException failure) {
            throw new ProjectApplicationException("METADATA_ROOT_UNAVAILABLE", "Synesis metadata root is unavailable or unsafe", failure);
        }
    }

    private static ProjectLocation readLocation(Path root, Path synesis, Path metadata) throws ProjectApplicationException {
        try {
            String json = Files.readString(metadata, StandardCharsets.UTF_8);
            Object parsed = ProviderJson.parse(json);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("unsupported or unsafe project metadata");
            }
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) raw;
            int schema = integerNumber(value.get("schemaVersion"), "schemaVersion");
            if (schema != 1 && schema != PROJECT_SCHEMA_VERSION) {
                throw new IOException("unsupported project metadata schema");
            }
            if (!Set.of("schemaVersion", "projectId", "createdAt", "validation").containsAll(value.keySet())) {
                throw new IOException("unsupported project metadata field");
            }
            UUID projectId = UUID.fromString(text(value, "projectId"));
            Instant createdAt = Instant.parse(text(value, "createdAt"));
            if (value.containsKey("validation") && value.get("validation") == null) {
                throw new IOException("validation must be an object when present");
            }
            ProjectCommandSpec validation = parseValidation(value.get("validation"));
            return new ProjectLocation(root, synesis, metadata, synesis.resolve("local/profile"), projectId, createdAt, validation);
        } catch (Exception failure) {
            throw new ProjectApplicationException("MALFORMED", "Project metadata is malformed", failure);
        }
    }

    private static String metadataJson(ProjectIdentity identity) {
        return "{\n" + "  \"schemaVersion\": 2,\n" + "  \"projectId\": \"" + identity.projectId() + "\",\n" + "  \"createdAt\": \"" + identity.createdAt() + "\"\n" + "}\n";
    }

    private static ProjectCommandSpec parseValidation(Object raw) throws IOException {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("validation must be an object");
        }
        if (map.keySet().stream().anyMatch(key -> !(key instanceof String text) || !Set.of("argv", "workingDirectory", "timeoutSeconds").contains(text))) {
            throw new IOException("validation contains unsupported fields");
        }
        Object argvRaw = map.get("argv");
        if (!(argvRaw instanceof List<?> list)) {
            throw new IOException("validation.argv must be an array");
        }
        List<String> argv = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String argument)) {
                throw new IOException("validation.argv entries must be strings");
            }
            argv.add(argument);
        }
        Object workingDirectoryRaw = map.get("workingDirectory");
        if (map.containsKey("workingDirectory") && !(workingDirectoryRaw instanceof String)) {
            throw new IOException("validation.workingDirectory must be a string");
        }
        String workingDirectory = workingDirectoryRaw instanceof String value ? value : ".";
        Object timeoutRaw = map.get("timeoutSeconds");
        if (map.containsKey("timeoutSeconds") && timeoutRaw == null) {
            throw new IOException("validation.timeoutSeconds must be an integer");
        }
        int timeoutSeconds = timeoutRaw == null ? ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS : integerNumber(timeoutRaw, "validation.timeoutSeconds");
        try {
            java.nio.file.Path relative = java.nio.file.Path.of(workingDirectory);
            if (looksLikeAbsolutePath(workingDirectory) || relative.isAbsolute() || relative.normalize().startsWith(java.nio.file.Path.of(".."))) {
                throw new IOException("validation workingDirectory must remain relative");
            }
            return new ProjectCommandSpec(argv, workingDirectory, timeoutSeconds);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid project validation command", failure);
        }
    }

    private static int integerNumber(Object value, String key) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("missing or non-integer " + key);
        }
        if (number instanceof Double || number instanceof Float) {
            throw new IOException("non-integer " + key);
        }
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble) || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw new IOException("non-integer " + key);
        }
        int result = number.intValue();
        if (result != asDouble) {
            throw new IOException("non-integer " + key);
        }
        return result;
    }

    private static boolean looksLikeAbsolutePath(String value) {
        return value.startsWith("/") || value.startsWith("\\\\") || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String text(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (!(result instanceof String text) || text.isBlank()) {
            throw new IOException("missing " + key);
        }
        return text;
    }

    private static void writeMetadata(Path metadata, ProjectIdentity identity, boolean replaceExisting) throws IOException {
        String json = metadataJson(identity);
        Path temporary = metadata.resolveSibling("project.json.tmp-" + UUID.randomUUID());
        Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try {
                if (replaceExisting) {
                    Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                if (replaceExisting) {
                    Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, metadata);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Creates or replaces only the Synesis-managed section in the project root {@code AGENTS.md},
     * preserving all unrelated text.
     *
     * @param root project root
     * @throws IOException if the file cannot be read or written, or its markers are malformed
     */
    private static String managedAgentsContent(Path root) throws IOException {
        Path agents = root.resolve(AGENTS_FILE);
        if (Files.isSymbolicLink(agents)) {
            throw new IOException("AGENTS.md must not be a symbolic link");
        }
        String existing = Files.exists(agents) ? Files.readString(agents, StandardCharsets.UTF_8) : "";
        String separator = existing.contains("\r\n") ? "\r\n" : "\n";
        int begin = existing.indexOf(AGENTS_BEGIN);
        int end = existing.indexOf(AGENTS_END);
        if ((begin < 0) != (end < 0) || count(existing, AGENTS_BEGIN) > 1 || count(existing, AGENTS_END) > 1 || (begin >= 0 && end < begin)) {
            throw new IOException("AGENTS.md contains malformed Synesis markers");
        }

        String section = managedAgentsSection(separator);
        String content;
        if (begin >= 0) {
            content = existing.substring(0, begin) + section + existing.substring(end + AGENTS_END.length());
        } else if (existing.isEmpty()) {
            content = section + separator;
        } else {
            String prefix = existing.endsWith("\n") ? separator : separator + separator;
            content = existing + prefix + section + separator;
        }
        return content;
    }

    private static void ensureAgentsFile(Path root) throws IOException {
        Path agents = root.resolve(AGENTS_FILE);
        String existing = Files.exists(agents) ? Files.readString(agents, StandardCharsets.UTF_8) : "";
        String content = managedAgentsContent(root);
        if (!content.equals(existing)) {
            writeTextAtomically(agents, content);
        }
    }

    private static String managedAgentsSection(String separator) {
        return AGENTS_BEGIN + separator + AGENTS_BODY.replace("\n", separator) + AGENTS_END;
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int index = value.indexOf(token); index >= 0; index = value.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    private static void writeTextAtomically(Path path, String content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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

    private static NodeIdentity identity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
    }

    private static void initializeLocalState(Path synesis) throws IOException {
        Files.createDirectories(synesis.resolve("shared/records"));
        Files.createDirectories(synesis.resolve("local/providers"));
        Files.createDirectories(synesis.resolve("local/runtime"));
        Files.createDirectories(synesis.resolve("local/profile/records"));
    }

    private static boolean hasReplicaPrivateState(Path synesis) throws IOException {
        try (var children = Files.list(synesis)) {
            return children.anyMatch(child -> !PROJECT_FILE.equals(child.getFileName().toString()));
        }
    }

    private static String ensureGitHead(Path root) throws ProjectApplicationException {
        try {
            if (!Files.exists(root.resolve(".git"))) {
                return "GIT_HEAD_UNAVAILABLE";
            }
            String head = git(root, "rev-parse", "--verify", "HEAD");
            if (head.matches("[0-9a-fA-F]{40}")) {
                return "GIT_HEAD_VALID";
            }
            throw new IOException("invalid Git HEAD");
        } catch (Exception unavailable) {
            throw new ProjectApplicationException("GIT_HEAD_UNAVAILABLE", "Git HEAD is unavailable; Synesis will not fabricate a base commit outside a baseline transaction", unavailable);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static String git(Path root, String... arguments) throws Exception {
        String output = runGit(root, arguments).trim();
        if (output.isBlank()) {
            throw new IOException("empty Git output");
        }
        return output;
    }

    private static String runGit(Path root, String... arguments) throws Exception {
        return org.synesis.workspace.lifecycle.process.GitProcessRunner.run(root, arguments);
    }

    private static Path directory(Path path, String label) throws ProjectApplicationException {
        try {
            Path input = Objects.requireNonNull(path, label);
            if (input.toString().isBlank()) {
                throw new ProjectApplicationException("PROJECT_INVALID", "Invalid " + label);
            }
            Path normalized = input.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized) || normalized.getParent() == null) {
                throw new ProjectApplicationException("PROJECT_INVALID", "Invalid " + label);
            }
            return normalized;
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("PROJECT_INVALID", "Invalid " + label, failure);
        }
    }

    /**
     * Walks from a directory toward the filesystem root to find project metadata.
     *
     * @param start directory from which discovery begins
     * @return discovered project location
     * @throws ProjectApplicationException if no project exists or metadata is malformed
     * @throws IOException if repository-private exclusions cannot be maintained
     */
    public ProjectLocation locate(Path start) throws ProjectApplicationException, IOException {
        Path current = directory(start, "start directory");
        while (current != null) {
            Path synesis = current.resolve(SYNESIS_DIRECTORY);
            Path metadata = synesis.resolve(PROJECT_FILE);
            if (Files.exists(metadata)) {
                ensureMetadataStorage(current);
                ProjectLocation location = readLocation(current, synesis, metadata);
                RepositoryPrivateStateService.ensure(location.root());
                return location;
            }
            if (Files.exists(synesis)) {
                throw new ProjectApplicationException("CONFLICT", "Partial .synesis state is missing project.json");
            }
            Path parent = current.getParent();
            current = parent == null || parent.equals(current) ? null : parent;
        }
        throw new ProjectApplicationException("NOT_FOUND", "No Synesis project was found");
    }

    /**
     * Resolves an explicit project directory and requires valid metadata.
     *
     * @param projectRoot explicit project directory
     * @return discovered project location
     * @throws ProjectApplicationException if the path or metadata is invalid
     * @throws IOException if repository-private exclusions cannot be maintained
     */
    public ProjectLocation require(Path projectRoot) throws ProjectApplicationException, IOException {
        Path root = directory(projectRoot, "project directory");
        Path metadata = root.resolve(SYNESIS_DIRECTORY).resolve(PROJECT_FILE);
        if (!Files.exists(metadata)) {
            throw new ProjectApplicationException("NOT_FOUND", "No initialized Synesis project was found at the requested path");
        }
        ensureMetadataStorage(root);
        ProjectLocation location = readLocation(root, root.resolve(SYNESIS_DIRECTORY), metadata);
        RepositoryPrivateStateService.ensure(location.root());
        return location;
    }

    /**
     * Verifies the explicit project directory is a usable Git repository.
     *
     * <p>An unborn repository is valid here: {@link #init(Path)} can create
     * Synesis's documented initial baseline commit for it. This method only rejects a missing or
     * invalid repository, and performs no project or Git mutation.</p>
     *
     * @param projectRoot explicit project directory
     * @throws ProjectApplicationException with code {@code GIT_REQUIRED} when the directory is not a
     *                                     usable Git repository
     */
    public void requireGitRepository(Path projectRoot) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        if (!Files.exists(root.resolve(".git"))) {
            throw new ProjectApplicationException("GIT_REQUIRED", "synesis init requires a Git repository; run git init before retrying");
        }
        try {
            git(root, "rev-parse", "--git-dir");
        } catch (Exception failure) {
            throw new ProjectApplicationException("GIT_REQUIRED", "synesis init requires a usable Git repository; Git metadata could not be read", failure);
        }
    }

    /**
     * Initializes a project without overwriting existing state.
     *
     * <p>For a Git repository, the managed baseline safety gate runs before
     * project-local state is created. A rejected checkout therefore remains uninitialized and can be
     * corrected and retried without cleanup.</p>
     *
     * @param projectRoot target directory
     * @return structured initialization result
     * @throws ProjectApplicationException if the target is invalid or conflicts with existing state
     */
    public ProjectInitResult init(Path projectRoot) throws ProjectApplicationException {
        return init(projectRoot, true);
    }

    /**
     * Initializes a user-selected directory for registry registration.
     *
     * <p>An existing Git repository is preserved and passed through the normal
     * Synesis initialization path. A directory without Git metadata receives a
     * direct, non-interactive {@code git init} first; Synesis then creates its
     * normal baseline and local identity. No shell command is used.</p>
     *
     * @param projectRoot target directory selected by the operator
     * @return structured initialization result
     * @throws ProjectApplicationException if Git or Synesis initialization fails
     */
    public ProjectInitResult initForRegistration(Path projectRoot) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        if (!Files.exists(root.resolve(".git"))) {
            try {
                GitProcessRunner.run(root, "init");
            } catch (Exception failure) {
                throw new ProjectApplicationException("GIT_INIT_FAILED", "Could not initialize Git in the selected folder", failure);
            }
        }
        return init(root);
    }

    /**
     * Initializes a project, optionally installing provider MCP configuration.
     *
     * <p>Internal fixture creation may disable provider configuration to avoid
     * mutating a user's provider profiles while running synthetic checks.</p>
     *
     * @param projectRoot          target directory
     * @param configureProviderMcp whether provider MCP configuration should be ensured
     * @return structured initialization result
     * @throws ProjectApplicationException if the path or metadata is invalid
     */
    public ProjectInitResult init(Path projectRoot, boolean configureProviderMcp) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        Path synesis = root.resolve(SYNESIS_DIRECTORY);
        Path metadata = synesis.resolve(PROJECT_FILE);
        if (Files.exists(synesis)) {
            ensureMetadataStorage(root);
            if (Files.exists(metadata)) {
                ProjectLocation existing = readLocation(root, synesis, metadata);
                try {
                    RepositoryPrivateStateService.ensure(root);
                    ensureAgentsFile(root);
                    if (configureProviderMcp) {
                        ProviderApplicationService providerService = new ProviderApplicationService();
                        Path launcher = providerService.launcherPath();
                        providerService.ensureMcpConfig(existing, org.synesis.workspace.provider.ProviderRegistry.find("codex"), launcher);
                    }
                    return new ProjectInitResult(ProjectInitStatus.ALREADY_INITIALIZED, existing, identity(existing.profile()), false, ensureGitHead(root));
                } catch (Exception failure) {
                    throw new ProjectApplicationException("CONFLICT", "Existing project identity is invalid", failure);
                }
            }
            throw new ProjectApplicationException("CONFLICT", "Existing .synesis state is partial or malformed");
        }

        ProjectIdentity projectIdentity = new ProjectIdentity(UUID.randomUUID(), Instant.now());
        try {
            // Install the exact common-directory exclusions before creating
            // any project-local or coordination runtime paths. The exclusions
            // are Git-private and do not create .synesis state.
            RepositoryPrivateStateService.ensure(root);
            Path profile = synesis.resolve("local/profile");
            String metadataContent = metadataJson(projectIdentity);
            String agentsContent = managedAgentsContent(root);
            String gitHeadStatus;
            if (Files.exists(root.resolve(".git"))) {
                ManagedBaselineTransactionService.Result baseline = baselineService.prepare(root,
                        Map.of(".synesis/project.json", metadataContent.getBytes(StandardCharsets.UTF_8), AGENTS_FILE, agentsContent.getBytes(StandardCharsets.UTF_8)));
                gitHeadStatus = "UNBORN".equals(baseline.originalHead()) ? "GIT_INITIAL_COMMIT_CREATED" : "GIT_HEAD_VALID";
            } else {
                // Internal non-Git fixtures intentionally bypass the
                // user-facing Git preflight and therefore have no baseline
                // transaction to create the project directory.
                ensureMetadataStorage(root);
                writeMetadata(metadata, projectIdentity, false);
                ensureAgentsFile(root);
                gitHeadStatus = "GIT_HEAD_UNAVAILABLE";
            }
            ensureMetadataStorage(root);
            initializeLocalState(synesis);
            NodeIdentity nodeIdentity = identity(profile);
            ProjectLocation location = readLocation(root, synesis, metadata);
            RepositoryPrivateStateService.ensure(root);
            try {
                if (configureProviderMcp) {
                    ProviderApplicationService providerService = new ProviderApplicationService();
                    Path launcher = providerService.launcherPath();
                    providerService.ensureMcpConfig(location, org.synesis.workspace.provider.ProviderRegistry.find("codex"), launcher);
                }
            } catch (Exception ignored) {
            }
            return new ProjectInitResult(ProjectInitStatus.SUCCESS, location, nodeIdentity, true, gitHeadStatus);
        } catch (ManagedBaselineTransactionService.BaselineFailure failure) {
            throw new ProjectApplicationException(failure.code(), "Managed baseline transaction failed: " + failure.getMessage(), failure);
        } catch (Exception failure) {
            throw new ProjectApplicationException("CONFLICT", "Could not initialize project state", failure);
        }
    }

    /**
     * Materializes a local replica with optional provider configuration.
     *
     * @param projectRoot          target project directory
     * @param projectIdentity      existing portable project identity
     * @param configureProviderMcp whether provider MCP configuration should be ensured
     * @return initialization result with fresh local node/profile state
     * @throws ProjectApplicationException if the target is malformed, mismatched, or private state
     *                                     is present
     */
    public ProjectInitResult initReplica(Path projectRoot, ProjectIdentity projectIdentity, boolean configureProviderMcp) throws ProjectApplicationException {
        Objects.requireNonNull(projectIdentity, "project identity");
        Path root = directory(projectRoot, "project directory");
        Path synesis = root.resolve(SYNESIS_DIRECTORY);
        Path metadata = synesis.resolve(PROJECT_FILE);
        try {
            if (Files.exists(synesis)) {
                ensureMetadataStorage(root);
                if (!Files.exists(metadata)) {
                    throw new ProjectApplicationException("CONFLICT", "Existing .synesis state is partial or malformed");
                }
                ProjectLocation existing = readLocation(root, synesis, metadata);
                if (!existing.identity().matches(projectIdentity)) {
                    throw new ProjectApplicationException("IDENTITY_MISMATCH", "Replica identity does not match canonical project identity");
                }
                if (hasReplicaPrivateState(synesis)) {
                    throw new ProjectApplicationException("PRIVATE_STATE_PRESENT", "Replica target already contains private Synesis state");
                }
            } else {
                ensureMetadataStorage(root);
                writeMetadata(metadata, projectIdentity, false);
            }
            RepositoryPrivateStateService.ensure(root);
            ensureAgentsFile(root);
            initializeLocalState(synesis);
            NodeIdentity nodeIdentity = identity(synesis.resolve("local/profile"));
            ProjectLocation location = readLocation(root, synesis, metadata);
            if (configureProviderMcp) {
                try {
                    ProviderApplicationService providerService = new ProviderApplicationService();
                    providerService.ensureMcpConfig(location, org.synesis.workspace.provider.ProviderRegistry.find("codex"), providerService.launcherPath());
                } catch (Exception ignored) {
                    // Provider installation is optional; canonical identity and local state are not.
                }
            }
            String gitHeadStatus = Files.exists(root.resolve(".git")) ? ensureGitHead(root) : "GIT_HEAD_UNAVAILABLE";
            return new ProjectInitResult(ProjectInitStatus.SUCCESS, location, nodeIdentity, true, gitHeadStatus);
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("CONFLICT", "Could not materialize project replica", failure);
        }
    }

    /**
     * Explicitly forks an initialized copy with optional provider configuration.
     *
     * @param projectRoot          initialized copy to fork
     * @param configureProviderMcp whether provider MCP configuration should be ensured
     * @return initialization result for the new logical project
     * @throws ProjectApplicationException if the target is not a safe fork input
     */
    public ProjectInitResult fork(Path projectRoot, boolean configureProviderMcp) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        Path synesis = root.resolve(SYNESIS_DIRECTORY);
        Path metadata = synesis.resolve(PROJECT_FILE);
        try {
            if (Files.exists(synesis)) {
                ensureMetadataStorage(root);
            }
            if (!Files.exists(metadata)) {
                throw new ProjectApplicationException("NOT_FOUND", "An initialized project copy is required for an explicit fork");
            }
            readLocation(root, synesis, metadata);
            if (hasReplicaPrivateState(synesis)) {
                throw new ProjectApplicationException("PRIVATE_STATE_PRESENT", "Fork input contains private Synesis state");
            }
            ProjectIdentity forkIdentity = new ProjectIdentity(UUID.randomUUID(), Instant.now());
            writeMetadata(metadata, forkIdentity, true);
            ensureMetadataStorage(root);
            return initReplica(root, forkIdentity, configureProviderMcp);
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("CONFLICT", "Could not create project fork", failure);
        }
    }

    /**
     * Returns the default local profile for a discovered project.
     *
     * @param location discovered project location
     * @return normalized local profile directory
     */
    public Path profile(ProjectLocation location) {
        return location.profile();
    }

    /**
     * Creates the existing one-peer project configuration in local state.
     *
     * @param location   project location
     * @param peerNodeId authenticated peer node ID
     * @return structured project-creation result
     * @throws ProjectApplicationException if configuration is invalid or conflicts
     */
    public ProjectCreateResult createProject(ProjectLocation location, String peerNodeId) throws ProjectApplicationException {
        Objects.requireNonNull(location, "location");
        if (peerNodeId == null || !peerNodeId.matches("sl1-[0-9a-f]{64}")) {
            throw new ProjectApplicationException("PROJECT_INVALID", "Invalid peer node ID");
        }
        try {
            NodeIdentity identity = identity(location.profile());
            Path configPath = location.profile().resolve("project.conf");
            if (Files.exists(configPath)) {
                ProjectConfig existing = ProjectConfig.load(configPath);
                if (existing.projectId().equals(location.projectId()) && existing.peerNodeIds().size() == 1 && existing.peerNodeIds().contains(peerNodeId)) {
                    return new ProjectCreateResult(existing.projectId(), identity.nodeId(), peerNodeId, false);
                }
                throw new ProjectApplicationException("PROJECT_MISMATCH", "Existing project peer does not match");
            }
            ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peerNodeId));
            config.save(configPath);
            return new ProjectCreateResult(config.projectId(), identity.nodeId(), peerNodeId, true);
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("PROJECT_WRITE_FAILED", "Project configuration could not be written", failure);
        }
    }

    /**
     * Safe application failure with a stable code.
     */
    public static final class ProjectApplicationException extends Exception {

        /**
         * Serialized exception identifier.
         */
        @Serial
        private static final long serialVersionUID = 1L;
        /**
         * Stable application failure code.
         */
        private final String code;

        /**
         * Creates an application failure.
         *
         * @param code    stable failure code
         * @param message safe diagnostic
         */
        public ProjectApplicationException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Creates an application failure with an internal cause.
         *
         * @param code    stable failure code
         * @param message safe diagnostic
         * @param cause   underlying failure
         */
        public ProjectApplicationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * Returns the stable failure code.
         *
         * @return failure code
         */
        public String code() {
            return code;
        }
    }
}
