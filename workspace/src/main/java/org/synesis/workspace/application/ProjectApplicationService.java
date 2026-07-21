package org.synesis.workspace.application;

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.ProjectConfig;

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
    public static final int PROJECT_SCHEMA_VERSION = 1;
    private static final String SYNESIS_DIRECTORY = ".synesis";
    private static final String PROJECT_FILE = "project.json";

    /**
     * Creates a project application service.
     */
    public ProjectApplicationService() {
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
                return readLocation(current, synesis, metadata);
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
        return readLocation(root, root.resolve(SYNESIS_DIRECTORY), metadata);
    }

    /**
     * Initializes a project without overwriting existing state.
     *
     * @param projectRoot target directory
     * @return structured initialization result
     * @throws ProjectApplicationException if the target is invalid or conflicts with existing state
     */
    public InitResult init(Path projectRoot) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        Path synesis = root.resolve(SYNESIS_DIRECTORY);
        Path metadata = synesis.resolve(PROJECT_FILE);
        if (Files.exists(synesis)) {
            if (Files.exists(metadata)) {
                ProjectLocation existing = readLocation(root, synesis, metadata);
                try {
                    return new InitResult(InitStatus.ALREADY_INITIALIZED,
                            existing,
                            identity(existing.profile()),
                            false);
                } catch (Exception failure) {
                    throw new ProjectApplicationException("CONFLICT", "Existing project identity is invalid", failure);
                }
            }
            throw new ProjectApplicationException("CONFLICT", "Existing .synesis state is partial or malformed");
        }

        UUID projectId = UUID.randomUUID();
        try {
            Files.createDirectories(synesis.resolve("shared/records"));
            Files.createDirectories(synesis.resolve("local/providers"));
            Files.createDirectories(synesis.resolve("local/runtime"));
            Path profile = synesis.resolve("local/profile");
            Files.createDirectories(profile.resolve("records"));
            NodeIdentity identity = identity(profile);
            writeMetadata(metadata, projectId);
            ProjectLocation location = readLocation(root, synesis, metadata);
            return new InitResult(InitStatus.SUCCESS, location, identity, true);
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

    private static ProjectLocation readLocation(Path root, Path synesis, Path metadata)
            throws ProjectApplicationException {
        try {
            String json = Files.readString(metadata, StandardCharsets.UTF_8);
            int schema = integer(json, "schemaVersion");
            UUID projectId = UUID.fromString(string(json, "projectId"));
            Instant createdAt = Instant.parse(string(json, "createdAt"));
            String lower = json.toLowerCase(java.util.Locale.ROOT);
            if (schema != PROJECT_SCHEMA_VERSION || lower.contains("identity") || lower.contains("provider")
                    || lower.contains("private") || lower.contains("absolute") || lower.contains("runtime")
                    || lower.contains("profile") || lower.contains("secret") || lower.contains("path")) {
                throw new IOException("unsupported or unsafe project metadata");
            }
            return new ProjectLocation(root, synesis, metadata, synesis.resolve("local/profile"), projectId, createdAt);
        } catch (Exception failure) {
            throw new ProjectApplicationException("MALFORMED", "Project metadata is malformed", failure);
        }
    }

    private static void writeMetadata(Path metadata, UUID projectId) throws IOException {
        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"projectId\": \"" + projectId + "\",\n"
                + "  \"createdAt\": \"" + Instant.now() + "\"\n"
                + "}\n";
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

    private static NodeIdentity identity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate()
                .identity();
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
     */
    public record ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile,
                                  UUID projectId, Instant createdAt) {

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
     */
    public record InitResult(InitStatus status, ProjectLocation location, NodeIdentity identity,
                             boolean createdIdentity) {

        /**
         * Validates the initialization result.
         */
        public InitResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(identity, "identity");
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
