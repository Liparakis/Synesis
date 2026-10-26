package org.synesis.workspace.application;

import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.projectrecord.domain.ProjectConfig;

import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.project.GitRepositoryFacts;
import org.synesis.workspace.application.project.LegacyAgentsSection;
import org.synesis.workspace.application.project.ProjectCreateResult;
import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectInitStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.ProjectMetadataCodec;
import org.synesis.workspace.application.project.ProjectValidationChange;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.filesystem.ProjectMetadataStorage;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;

/**
 * Owns project discovery and initialization.
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
    public static final int PROJECT_SCHEMA_VERSION = ProjectMetadataCodec.SCHEMA_VERSION;
    private static final String SYNESIS_DIRECTORY = ProjectMetadataStorage.DIRECTORY_NAME;
    private static final String PROJECT_FILE = ProjectMetadataStorage.PROJECT_METADATA_FILE;
    private final ManagedBaselineTransactionService baselineService;
    private final ProjectMetadataStorage metadataStorage;

    /**
     * Creates a project application service.
     */
    public ProjectApplicationService() {
        this(new ManagedBaselineTransactionService(), new ProjectMetadataStorage());
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
            return ProjectMetadataCodec.read(root, synesis, metadata);
        } catch (Exception failure) {
            throw new ProjectApplicationException("MALFORMED", "Project metadata is malformed", failure);
        }
    }

    /**
     * Not read-only: restores the Hidden attribute on {@code .synesis} and the Synesis entries in the
     * repository's Git {@code info/exclude} when they are missing.
     */
    private ProjectLocation readLocationAndRepairLocalState(Path root, Path synesis, Path metadata) throws ProjectApplicationException, IOException {
        GitProcessRunner.trustControlRoot(root);
        ensureMetadataStorage(root);
        ProjectLocation location = readLocation(root, synesis, metadata);
        RepositoryPrivateStateService.ensure(location.root());
        return location;
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
            return GitRepositoryFacts.headStatus(root);
        } catch (Exception unavailable) {
            throw new ProjectApplicationException("GIT_HEAD_UNAVAILABLE", "Git HEAD is unavailable; Synesis will not fabricate a base commit outside a baseline transaction", unavailable);
        }
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
                return readLocationAndRepairLocalState(current, synesis, metadata);
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
        return readLocationAndRepairLocalState(root, root.resolve(SYNESIS_DIRECTORY), metadata);
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
        if (!GitRepositoryFacts.hasGitMetadata(root)) {
            throw new ProjectApplicationException("GIT_REQUIRED", "synesis init requires a Git repository; run git init before retrying");
        }
        try {
            GitRepositoryFacts.requireReadableMetadata(root);
        } catch (Exception failure) {
            throw new ProjectApplicationException("GIT_REQUIRED", "synesis init requires a usable Git repository; Git metadata could not be read", failure);
        }
    }

    /**
     * Brings the Synesis entry of Codex's user-wide configuration up to date, for a project in which
     * Codex is connected, with the launcher built for it.
     *
     * <p>Initialization and {@code provider install} both write this entry. They must name the
     * same launcher, or whichever ran last silently decides how every Codex session starts its
     * server: the command launcher is the one people type, not the stdio server entry point.</p>
     *
     * <p>Initialization used to write the entry whether or not Codex was installed, which changed
     * configuration nobody asked to touch (ADR-0103 decision 2). Now a project without the Codex
     * provider installed gets nothing, and an entry that exists is left alone; connecting Codex is
     * {@code provider install codex}, which {@code synesis init} runs for an agent the person chose.</p>
     */
    private static void refreshConnectedCodexMcpServer(ProjectLocation location) {
        ProviderApplicationService providerService = new ProviderApplicationService();
        boolean connected = providerService.list(location).stream().anyMatch(row -> "codex".equals(row.id()) && "INSTALLED".equals(row.status()));
        if (connected) {
            providerService.ensureMcpConfig(location, org.synesis.workspace.application.provider.ProviderRegistry.find("codex"), providerService.mcpLauncherPath());
        }
    }

    /**
     * Tells whether a directory already has Git metadata, so a caller can offer {@code git init}
     * before {@link #requireGitRepository(Path)} refuses.
     *
     * @param projectRoot explicit project directory
     * @return true when the directory is a Git repository or worktree root
     * @throws ProjectApplicationException with code {@code PROJECT_INVALID} when the directory is not usable
     */
    public boolean hasGitRepository(Path projectRoot) throws ProjectApplicationException {
        return GitRepositoryFacts.hasGitMetadata(directory(projectRoot, "project directory"));
    }

    /**
     * Runs {@code git init} in a directory that has no Git metadata; leaves a repository as it is.
     * It never stages or commits: the managed baseline commit of {@link #init(Path)} stays the only
     * commit Synesis makes.
     *
     * @param projectRoot target directory
     * @throws ProjectApplicationException with code {@code GIT_INIT_FAILED} when Git cannot initialize the folder
     */
    public void initGit(Path projectRoot) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        if (!GitRepositoryFacts.hasGitMetadata(root)) {
            try {
                GitProcessRunner.run(root, "init");
            } catch (Exception failure) {
                throw new ProjectApplicationException("GIT_INIT_FAILED", "Could not initialize Git in the selected folder", failure);
            }
        }
    }

    /**
     * Removes what earlier versions wrote into the project for the agents: Synesis's section of
     * {@code AGENTS.md} and the provider rule files it owns. The agent instructions live in the
     * installed {@code synesis-provider-manual} skill now (ADR-0103 decision 9), so initialization
     * leaves the user's own text and files alone and removes only what carries Synesis's marker.
     *
     * <p>Best effort: this is cleanup that every later initialization repeats, so a file that cannot
     * be changed must not stop the project from being initialized.</p>
     */
    private static List<String> retireLegacyInstructions(Path root) {
        List<String> retired = new java.util.ArrayList<>();
        try {
            if (LegacyAgentsSection.retire(root)) {
                retired.add(LegacyAgentsSection.FILE_NAME);
            }
            retired.addAll(new ProviderApplicationService().retireProjectInstructions(root));
        } catch (IOException unchanged) {
            // The file stays as it was; the next initialization tries again.
        }
        return List.copyOf(retired);
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
        return initForRegistration(projectRoot, true);
    }

    /**
     * Initializes a directory as {@link #initForRegistration(Path)} does, optionally without
     * provider MCP configuration.
     *
     * @param projectRoot          target directory
     * @param configureProviderMcp whether provider MCP configuration should be ensured
     * @return structured initialization result
     * @throws ProjectApplicationException if Git or Synesis initialization fails
     */
    public ProjectInitResult initForRegistration(Path projectRoot, boolean configureProviderMcp) throws ProjectApplicationException {
        Path root = directory(projectRoot, "project directory");
        initGit(root);
        return init(root, configureProviderMcp);
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
                    List<String> retired = retireLegacyInstructions(root);
                    if (configureProviderMcp) {
                        refreshConnectedCodexMcpServer(existing);
                    }
                    return new ProjectInitResult(ProjectInitStatus.ALREADY_INITIALIZED, existing, identity(existing.profile()), false, ensureGitHead(root), retired);
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
            String metadataContent = ProjectMetadataCodec.json(projectIdentity);
            String gitHeadStatus;
            if (GitRepositoryFacts.hasGitMetadata(root)) {
                ManagedBaselineTransactionService.Result baseline = baselineService.prepare(root,
                        Map.of(".synesis/project.json", metadataContent.getBytes(StandardCharsets.UTF_8)));
                gitHeadStatus = "UNBORN".equals(baseline.originalHead()) ? "GIT_INITIAL_COMMIT_CREATED" : GitRepositoryFacts.HEAD_VALID;
            } else {
                // Internal non-Git fixtures intentionally bypass the
                // user-facing Git preflight and therefore have no baseline
                // transaction to create the project directory.
                ensureMetadataStorage(root);
                ProjectMetadataCodec.write(metadata, projectIdentity, false);
                gitHeadStatus = GitRepositoryFacts.HEAD_UNAVAILABLE;
            }
            ensureMetadataStorage(root);
            initializeLocalState(synesis);
            NodeIdentity nodeIdentity = identity(profile);
            ProjectLocation location = readLocation(root, synesis, metadata);
            RepositoryPrivateStateService.ensure(root);
            List<String> retired = retireLegacyInstructions(root);
            try {
                if (configureProviderMcp) {
                    refreshConnectedCodexMcpServer(location);
                }
            } catch (Exception ignored) {
            }
            return new ProjectInitResult(ProjectInitStatus.SUCCESS, location, nodeIdentity, true, gitHeadStatus, retired);
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
                ProjectMetadataCodec.write(metadata, projectIdentity, false);
            }
            RepositoryPrivateStateService.ensure(root);
            List<String> retired = retireLegacyInstructions(root);
            initializeLocalState(synesis);
            NodeIdentity nodeIdentity = identity(synesis.resolve("local/profile"));
            ProjectLocation location = readLocation(root, synesis, metadata);
            if (configureProviderMcp) {
                try {
                    refreshConnectedCodexMcpServer(location);
                } catch (Exception ignored) {
                    // Provider installation is optional; canonical identity and local state are not.
                }
            }
            String gitHeadStatus = ensureGitHead(root);
            return new ProjectInitResult(ProjectInitStatus.SUCCESS, location, nodeIdentity, true, gitHeadStatus, retired);
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
    ProjectInitResult fork(Path projectRoot, boolean configureProviderMcp) throws ProjectApplicationException {
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
            ProjectLocation copied = readLocation(root, synesis, metadata);
            if (hasReplicaPrivateState(synesis)) {
                throw new ProjectApplicationException("PRIVATE_STATE_PRESENT", "Fork input contains private Synesis state");
            }
            ProjectIdentity forkIdentity = new ProjectIdentity(UUID.randomUUID(), Instant.now());
            // A fork is a new project with the policy of the one it was copied from. Written with
            // the identity alone, it lost its validation command and its review policy unnoticed.
            ProjectMetadataCodec.rewrite(copied, forkIdentity, copied.validation(), copied.validationAlsoInLane());
            ensureMetadataStorage(root);
            return initReplica(root, forkIdentity, configureProviderMcp);
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("CONFLICT", "Could not create project fork", failure);
        }
    }

    /**
     * Declares the validation command of an initialized project, replacing any it declared.
     *
     * <p>Only the metadata file is rewritten; the project's identity and review policy are kept.
     * Declaring what the project already declares changes nothing. The whole operation holds the
     * project append lock, which integration holds for a whole attempt, so the command an attempt
     * runs and the head it advances are never changed under it.</p>
     *
     * <p>Where Git tracks the file, the rewrite is committed, that one path alone: integration
     * refuses a checkout that holds an uncommitted change (ADR-0091), so a rewrite left for the
     * owner to commit stopped every integration until it was. For the same reason a checkout that
     * already holds changes Synesis did not make is refused with
     * {@code CONTROL_CHECKOUT_FOREIGN_CHANGES}, by the rule integration uses, and when the commit
     * fails the file is put back as it was. Untracked files a provider installs do not count.
     * Outside Git, or where Git does not track the file, the file is rewritten and nothing is
     * committed.</p>
     *
     * @param projectRoot initialized project directory
     * @param validation  command that must pass before work is integrated
     * @param alsoInLane  whether the command also runs in a lane alone before its finish is published
     * @return what was done, with the project as its metadata now reads
     * @throws ProjectApplicationException if the project is not initialized, the checkout holds foreign changes, or
     *                                     the command cannot be written or committed
     * @throws IOException                 if repository-private exclusions cannot be maintained
     */
    public ProjectValidationChange setValidation(Path projectRoot, ProjectCommandSpec validation, boolean alsoInLane) throws ProjectApplicationException, IOException {
        return rewriteValidation(projectRoot, Objects.requireNonNull(validation, "validation"), alsoInLane);
    }

    /**
     * Removes the validation command of an initialized project, keeping its identity and review
     * policy, under the conditions {@link #setValidation} states.
     *
     * @param projectRoot initialized project directory
     * @return what was done, with the project as its metadata now reads
     * @throws ProjectApplicationException if the project is not initialized, the checkout holds foreign changes, or
     *                                     its metadata cannot be written or committed
     * @throws IOException                 if repository-private exclusions cannot be maintained
     */
    public ProjectValidationChange clearValidation(Path projectRoot) throws ProjectApplicationException, IOException {
        return rewriteValidation(projectRoot, null, false);
    }

    private ProjectValidationChange rewriteValidation(Path projectRoot, ProjectCommandSpec validation, boolean alsoInLane) throws ProjectApplicationException, IOException {
        Path coordination = require(projectRoot).coordinationDirectory();
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(coordination)) {
            if (!lock.isHeld()) {
                throw new ProjectApplicationException("PROJECT_WRITE_FAILED", "The project lock was lost before the project file was written");
            }
            // Read under the lock: what is declared is compared, not the text of the file, which
            // another writer or a hand may have laid out differently.
            ProjectLocation current = require(projectRoot);
            if (Objects.equals(current.validation(), validation) && current.validationAlsoInLane() == alsoInLane) {
                return new ProjectValidationChange(current, false, "");
            }
            String file = SYNESIS_DIRECTORY + "/" + PROJECT_FILE;
            if (!GitRepositoryFacts.hasGitMetadata(current.root()) || GitProcessRunner.runResult(current.root(), "ls-files", "--error-unmatch", "--", file).exitCode() != 0) {
                return new ProjectValidationChange(ProjectMetadataCodec.rewrite(current, current.identity(), validation, alsoInLane), true, "");
            }
            List<String> foreign = ControlBranchAdvancementService.foreignChanges(current.root());
            if (!foreign.isEmpty()) {
                throw new ProjectApplicationException("CONTROL_CHECKOUT_FOREIGN_CHANGES",
                        "The project checkout holds changes Synesis did not make, so the project file cannot be committed. Commit them or remove them first: " + String.join("; ", foreign));
            }
            byte[] previous = Files.readAllBytes(current.metadataFile());
            ProjectLocation written;
            try {
                written = ProjectMetadataCodec.rewrite(current, current.identity(), validation, alsoInLane);
                GitProcessRunner.run(current.root(), "commit", "--no-verify", "--only", "-m", "Synesis project validation policy", "--", file);
            } catch (IOException notCommitted) {
                // Left rewritten and uncommitted, the file is a foreign change of the checkout:
                // nothing would be integrated, and this command would refuse to run again.
                Files.write(current.metadataFile(), previous);
                throw new ProjectApplicationException("PROJECT_COMMIT_FAILED", "The project file could not be committed and was put back as it was", notCommitted);
            }
            return new ProjectValidationChange(written, true, GitProcessRunner.run(current.root(), "rev-parse", "--short", "HEAD"));
        } catch (ProjectApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProjectApplicationException("PROJECT_WRITE_FAILED", "Project validation could not be written", failure);
        }
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
        if (!NodeIdentity.isNodeId(peerNodeId)) {
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
