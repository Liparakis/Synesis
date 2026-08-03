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
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService;

/**
 * Owns project discovery, initialization, and project-local profile paths.
 *
 * <p>The service is synchronous, process-safe for one initializer, and does
 * not print or depend on a command-line framework. Shareable metadata is kept
 * in {@code project.json}; identity and runtime state stay below
 * {@code .synesis/local}.
 *
 * @since 1.0
 */
public final class ProjectApplicationService {

    /**
     * Current project metadata schema.
     */
    public static final int PROJECT_SCHEMA_VERSION = 2;
    private static final String SYNESIS_DIRECTORY = ".synesis";
    private static final String PROJECT_FILE = "project.json";
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String AGENTS_BEGIN = "<!-- SYNESIS-BEGIN -->";
    private static final String AGENTS_END = "<!-- SYNESIS-END -->";
    private static final String AGENTS_BODY = """
            ## Synesis

            This repository uses Synesis.

            - Use Synesis tools for project reads, file changes, and commands.
            - One persistent MCP connection owns one provider binding and one isolated worker context.
            - After synesis.ensure_session succeeds, keep the provider in the current project directory and use Synesis MCP for all reads, writes, and commands. Synesis applies those operations internally in the assigned worktree; do not switch branches, cd, or edit another worker's worktree manually. Native provider hooks are optional and may be unavailable in desktop harnesses, so never assume a native mutation was routed. If a native tool is used and Synesis reports workspace_mismatch, stop native mutations and verify state through synesis.read_file.
            - Reads carry revisions; provide the matching revision when applying a patch.
            - Do not modify the control checkout or another worker's files directly.
            - When Synesis reports an identity, ownership, freshness, or workspace failure, stop mutation and inspect read-only state.
            - The MCP surface currently contains exactly 10 tools; follow get_next_action's recommended tool and typed arguments.
            """;
    private final ManagedBaselineTransactionService baselineService;

    /**
     * Creates a project application service.
     */
    public ProjectApplicationService() {
        this(new ManagedBaselineTransactionService());
    }

    /**
     * Creates a project service with an explicit baseline transaction service.
     *
     * @param baselineService managed-baseline transaction service
     */
    public ProjectApplicationService(ManagedBaselineTransactionService baselineService) {
        this.baselineService = Objects.requireNonNull(baselineService, "baselineService");
    }

    private static ProjectLocation readLocation(Path root, Path synesis, Path metadata)
            throws ProjectApplicationException {
        try {
            String json = Files.readString(metadata, StandardCharsets.UTF_8);
            Object parsed = ProviderJson.parse(json);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("unsupported or unsafe project metadata");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) raw;
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
            return new ProjectLocation(root, synesis, metadata, synesis.resolve("local/profile"), projectId, createdAt,
                    validation);
        } catch (Exception failure) {
            throw new ProjectApplicationException("MALFORMED", "Project metadata is malformed", failure);
        }
    }

    private static String metadataJson(UUID projectId, Instant createdAt) {
        return "{\n" + "  \"schemaVersion\": 2,\n" + "  \"projectId\": \"" + projectId + "\",\n"
                + "  \"createdAt\": \"" + createdAt + "\"\n" + "}\n";
    }

    @SuppressWarnings("unchecked")
    private static ProjectCommandSpec parseValidation(Object raw) throws IOException {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("validation must be an object");
        }
        if (!Set.of("argv", "workingDirectory", "timeoutSeconds").containsAll(map.keySet())) {
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
        int timeoutSeconds = timeoutRaw == null ? ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS
                : integerNumber(timeoutRaw, "validation.timeoutSeconds");
        try {
            java.nio.file.Path relative = java.nio.file.Path.of(workingDirectory);
            if (looksLikeAbsolutePath(workingDirectory) || relative.isAbsolute()
                    || relative.normalize().startsWith(java.nio.file.Path.of(".."))) {
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
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            throw new IOException("non-integer " + key);
        }
        int result = number.intValue();
        if (result != asDouble) {
            throw new IOException("non-integer " + key);
        }
        return result;
    }

    private static boolean looksLikeAbsolutePath(String value) {
        return value.startsWith("/") || value.startsWith("\\\\")
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String text(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (!(result instanceof String text) || text.isBlank()) {
            throw new IOException("missing " + key);
        }
        return text;
    }

    private static void writeMetadata(Path metadata, UUID projectId) throws IOException {
        String json = metadataJson(projectId, Instant.now());
        Path temporary = metadata.resolveSibling("project.json.tmp-" + UUID.randomUUID());
        Files.writeString(temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, metadata, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, metadata);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Creates or replaces only the Synesis-managed section in the project root
     * {@code AGENTS.md}, preserving all unrelated text.
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
        if ((begin < 0) != (end < 0) || count(existing, AGENTS_BEGIN) > 1 || count(existing, AGENTS_END) > 1 || (
                begin >= 0 && end < begin)) {
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
        Files.writeString(temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
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
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate()
                .identity();
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
            throw new ProjectApplicationException("GIT_HEAD_UNAVAILABLE",
                    "Git HEAD is unavailable; Synesis will not fabricate a base commit outside a baseline transaction",
                    unavailable);
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
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git failed: " + output);
        }
        return output;
    }

    private static Path directory(Path path, String label) throws ProjectApplicationException {
        try {
            Path input = Objects.requireNonNull(path, label);
            if (input.toString()
                    .isBlank()) {
                throw new ProjectApplicationException("PROJECT_INVALID", "Invalid " + label);
            }
            Path normalized = input.toAbsolutePath()
                    .normalize();
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

    private static String string(String json, String key) throws IOException {
        String marker = "\"" + key + "\": \"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IOException("missing " + key);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new IOException("invalid " + key);
        }
        return json.substring(start, end);
    }

    @SuppressWarnings("SameParameterValue")
    private static int integer(String json, String key) throws IOException {
        String marker = "\"" + key + "\": ";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IOException("missing " + key);
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    /**
     * Walks from a directory toward the filesystem root to find project metadata.
     *
     * @param start directory from which discovery begins
     * @return discovered project location
     * @throws ProjectApplicationException if no project exists or metadata is malformed
     */
    public ProjectLocation locate(Path start) throws ProjectApplicationException {
        Path current = directory(start, "start directory");
        while (current != null) {
            Path synesis = current.resolve(SYNESIS_DIRECTORY);
            Path metadata = synesis.resolve(PROJECT_FILE);
            if (Files.exists(metadata)) {
                ProjectLocation location = readLocation(current, synesis, metadata);
                try {
                    RepositoryPrivateStateService.ensure(location.root());
                } catch (IOException failure) {
                    throw new ProjectApplicationException("PRIVATE_STATE_UNAVAILABLE",
                            "Could not maintain repository-private Synesis exclusions", failure);
                }
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
     */
    public ProjectLocation require(Path projectRoot) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        Path metadata = root.resolve(SYNESIS_DIRECTORY)
                .resolve(PROJECT_FILE);
        if (!Files.exists(metadata)) {
            throw new ProjectApplicationException("NOT_FOUND",
                    "No initialized Synesis project was found at the requested path");
        }
        ProjectLocation location = readLocation(root, root.resolve(SYNESIS_DIRECTORY), metadata);
        try {
            RepositoryPrivateStateService.ensure(location.root());
        } catch (IOException failure) {
            throw new ProjectApplicationException("PRIVATE_STATE_UNAVAILABLE",
                    "Could not maintain repository-private Synesis exclusions", failure);
        }
        return location;
    }

    /**
     * Initializes a project without overwriting existing state.
     *
     * @param projectRoot target directory
     * @return structured initialization result
     * @throws ProjectApplicationException if the target is invalid or conflicts with existing state
     */
    public InitResult init(Path projectRoot) throws ProjectApplicationException {
        return init(projectRoot, true);
    }

    /**
     * Initializes a project, optionally installing provider MCP configuration.
     *
     * <p>Internal fixture creation may disable provider configuration to avoid
     * mutating a user's provider profiles while running synthetic checks.</p>
     *
     * @param projectRoot target directory
     * @param configureProviderMcp whether provider MCP configuration should be ensured
     * @return structured initialization result
     * @throws ProjectApplicationException if the path or metadata is invalid
     */
    public InitResult init(Path projectRoot, boolean configureProviderMcp) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        Path synesis = root.resolve(SYNESIS_DIRECTORY);
        Path metadata = synesis.resolve(PROJECT_FILE);
        if (Files.exists(synesis)) {
            if (Files.exists(metadata)) {
                ProjectLocation existing = readLocation(root, synesis, metadata);
                try {
                    RepositoryPrivateStateService.ensure(root);
                    ensureAgentsFile(root);
                    if (configureProviderMcp) {
                        ProviderApplicationService providerService = new ProviderApplicationService();
                        Path launcher = providerService.launcherPath();
                        providerService.ensureMcpConfig(existing, org.synesis.workspace.provider.ProviderRegistry.find("codex"), launcher);
                        providerService.ensureMcpConfig(existing, org.synesis.workspace.provider.ProviderRegistry.find("antigravity"), launcher);
                    }
                    return new InitResult(InitStatus.ALREADY_INITIALIZED,
                            existing,
                            identity(existing.profile()),
                            false,
                            ensureGitHead(root));
                } catch (Exception failure) {
                    throw new ProjectApplicationException("CONFLICT", "Existing project identity is invalid", failure);
                }
            }
            throw new ProjectApplicationException("CONFLICT", "Existing .synesis state is partial or malformed");
        }

        UUID projectId = UUID.randomUUID();
        try {
            // Install the exact common-directory exclusions before creating
            // any local or coordination runtime paths.
            RepositoryPrivateStateService.ensure(root);
            Files.createDirectories(synesis);
            Path profile = synesis.resolve("local/profile");
            Instant createdAt = Instant.now();
            String metadataContent = metadataJson(projectId, createdAt);
            String agentsContent = managedAgentsContent(root);
            String gitHeadStatus;
            if (Files.exists(root.resolve(".git"))) {
                ManagedBaselineTransactionService.Result baseline = baselineService.prepare(root, Map.of(
                        ".synesis/project.json", metadataContent.getBytes(StandardCharsets.UTF_8),
                        AGENTS_FILE, agentsContent.getBytes(StandardCharsets.UTF_8)));
                gitHeadStatus = "UNBORN".equals(baseline.originalHead())
                        ? "GIT_INITIAL_COMMIT_CREATED" : "GIT_HEAD_VALID";
            } else {
                writeMetadata(metadata, projectId);
                ensureAgentsFile(root);
                gitHeadStatus = "GIT_HEAD_UNAVAILABLE";
            }
            Files.createDirectories(synesis.resolve("shared/records"));
            Files.createDirectories(synesis.resolve("local/providers"));
            Files.createDirectories(synesis.resolve("local/runtime"));
            Files.createDirectories(profile.resolve("records"));
            NodeIdentity identity = identity(profile);
            ProjectLocation location = readLocation(root, synesis, metadata);
            RepositoryPrivateStateService.ensure(root);
            try {
                if (configureProviderMcp) {
                    ProviderApplicationService providerService = new ProviderApplicationService();
                    Path launcher = providerService.launcherPath();
                    providerService.ensureMcpConfig(location, org.synesis.workspace.provider.ProviderRegistry.find("codex"), launcher);
                    providerService.ensureMcpConfig(location, org.synesis.workspace.provider.ProviderRegistry.find("antigravity"), launcher);
                }
            } catch (Exception ignored) {
            }
            return new InitResult(InitStatus.SUCCESS, location, identity, true, gitHeadStatus);
        } catch (ManagedBaselineTransactionService.BaselineFailure failure) {
            throw new ProjectApplicationException(failure.code(),
                    "Managed baseline transaction failed: " + failure.getMessage(), failure);
        } catch (Exception failure) {
            throw new ProjectApplicationException("CONFLICT", "Could not initialize project state", failure);
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
    public ProjectCreateResult createProject(ProjectLocation location, String peerNodeId)
            throws ProjectApplicationException {
        Objects.requireNonNull(location, "location");
        if (peerNodeId == null || !peerNodeId.matches("sl1-[0-9a-f]{64}")) {
            throw new ProjectApplicationException("PROJECT_INVALID", "Invalid peer node ID");
        }
        try {
            NodeIdentity identity = identity(location.profile());
            Path configPath = location.profile()
                    .resolve("project.conf");
            if (Files.exists(configPath)) {
                ProjectConfig existing = ProjectConfig.load(configPath);
                if (existing.peerNodeIds()
                        .size() == 1 && existing.peerNodeIds()
                        .contains(peerNodeId)) {
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
            throw new ProjectApplicationException("PROJECT_WRITE_FAILED",
                    "Project configuration could not be written",
                    failure);
        }
    }

    /**
     * Project initialization status.
     */
    public enum InitStatus {
        /**
         * State was created.
         */
        SUCCESS,
        /**
         * Valid existing state was found.
         */
        ALREADY_INITIALIZED
    }

    /**
     * Discovered project paths and shareable metadata.
     *
     * @param root             project root
     * @param synesisDirectory project state directory
     * @param metadataFile     shareable metadata file
     * @param profile          local profile directory
     * @param projectId        project identifier
     * @param createdAt        creation timestamp
     * @param validation       optional project-owned validation command
     */
    public record ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId,
                                  Instant createdAt, ProjectCommandSpec validation) {

        /**
         * Compatibility constructor for internal synthetic project locations
         * without validation.
         *
         * @param root project root
         * @param synesisDirectory Synesis directory
         * @param metadataFile project metadata file
         * @param profile local profile directory
         * @param projectId project identifier
         * @param createdAt creation timestamp
         */
        public ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId,
                Instant createdAt) {
            this(root, synesisDirectory, metadataFile, profile, projectId, createdAt, null);
        }

        /**
         * Validates and normalizes project paths.
         */
        public ProjectLocation {
            root = Objects.requireNonNull(root, "root")
                    .toAbsolutePath()
                    .normalize();
            synesisDirectory = Objects.requireNonNull(synesisDirectory, "synesis directory")
                    .toAbsolutePath()
                    .normalize();
            metadataFile = Objects.requireNonNull(metadataFile, "metadata file")
                    .toAbsolutePath()
                    .normalize();
            profile = Objects.requireNonNull(profile, "profile")
                    .toAbsolutePath()
                    .normalize();
            Objects.requireNonNull(projectId, "project ID");
            Objects.requireNonNull(createdAt, "created at");
        }
    }

    /**
     * Structured initialization result.
     *
     * @param status          initialization status
     * @param location        project location
     * @param identity        local node identity metadata
     * @param createdIdentity whether identity was created
     * @param gitHeadStatus   Git base-commit result
     */
    public record InitResult(InitStatus status, ProjectLocation location, NodeIdentity identity,
                             boolean createdIdentity, String gitHeadStatus) {

        /**
         * Validates the initialization result.
         */
        public InitResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(gitHeadStatus, "Git HEAD status");
        }
    }

    /**
     * Structured project creation result.
     *
     * @param projectId  project identifier
     * @param nodeId     local node identifier
     * @param peerNodeId configured peer identifier
     * @param created    whether configuration was created
     */
    public record ProjectCreateResult(UUID projectId, String nodeId, String peerNodeId, boolean created) {

        /**
         * Validates the project creation result.
         */
        public ProjectCreateResult {
            Objects.requireNonNull(projectId, "project ID");
            Objects.requireNonNull(nodeId, "node ID");
            Objects.requireNonNull(peerNodeId, "peer node ID");
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
