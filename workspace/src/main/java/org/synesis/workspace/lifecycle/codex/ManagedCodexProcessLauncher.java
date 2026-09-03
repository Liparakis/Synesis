package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentStore;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipRecord;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipStore;
import org.synesis.workspace.application.provider.continuity.RuntimeAuthenticator;

/**
 * Stock-Codex App Server launcher for the explicit managed-continuity mode.
 *
 * <p>The launcher is opt-in and prepared only with an exact existing thread.
 * It retains raw proof in trusted process memory until the child process owns
 * it, while the durable attachment record stores only the proof hash.</p>
 */
public final class ManagedCodexProcessLauncher implements CodexAppServerLifecycleService.ProcessLauncher {

    private final ProjectApplicationService.ProjectLocation location;
    private final Path synesisLauncher;
    private final ManagedAttachmentService attachmentService;
    private final ProviderThreadOwnershipStore ownershipStore;
    private final ManagedCodexRuntimeMode runtimeMode;
    private final ManagedProcessTreeSupervisor processTreeSupervisor;
    private final Map<String, PreparedLaunch> prepared = new ConcurrentHashMap<>();
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    /**
     * Creates a managed launcher using the normal Codex executable.
     *
     * @param location initialized project location
     * @param synesisLauncher Synesis MCP executable
     */
    public ManagedCodexProcessLauncher(ProjectApplicationService.ProjectLocation location, Path synesisLauncher) {
        this(location, synesisLauncher, new ManagedAttachmentService());
    }

    /**
     * Creates an injectable managed launcher.
     *
     * @param location          initialized project location
     * @param synesisLauncher   Synesis MCP executable
     * @param attachmentService managed attachment service
     */
    public ManagedCodexProcessLauncher(ProjectApplicationService.ProjectLocation location, Path synesisLauncher,
            ManagedAttachmentService attachmentService) {
        this(location, synesisLauncher, attachmentService, ManagedCodexRuntimeMode.NORMAL_PROVIDER_HOME_MANAGED);
    }

    /**
     * Creates an injectable managed launcher with an explicit runtime mode.
     *
     * @param location initialized project location
     * @param synesisLauncher Synesis MCP executable
     * @param attachmentService managed attachment service
     * @param runtimeMode managed runtime mode
     */
    public ManagedCodexProcessLauncher(ProjectApplicationService.ProjectLocation location, Path synesisLauncher,
            ManagedAttachmentService attachmentService, ManagedCodexRuntimeMode runtimeMode) {
        this(location, synesisLauncher, attachmentService, runtimeMode, ManagedProcessTreeSupervisor.platformDefault());
    }

    /**
     * Creates a launcher with an explicit owned-process supervisor.
     *
     * @param location initialized project location
     * @param synesisLauncher Synesis MCP executable
     * @param attachmentService managed attachment service
     * @param runtimeMode managed runtime mode
     * @param processTreeSupervisor verified process-tree supervisor
     */
    public ManagedCodexProcessLauncher(ProjectApplicationService.ProjectLocation location, Path synesisLauncher,
            ManagedAttachmentService attachmentService, ManagedCodexRuntimeMode runtimeMode,
            ManagedProcessTreeSupervisor processTreeSupervisor) {
        this.location = Objects.requireNonNull(location, "location");
        this.synesisLauncher = Objects.requireNonNull(synesisLauncher, "synesisLauncher")
                .toAbsolutePath().normalize();
        this.attachmentService = Objects.requireNonNull(attachmentService, "attachmentService");
        this.ownershipStore = ProviderThreadOwnershipStore.storeFor(location);
        this.runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
        this.processTreeSupervisor = Objects.requireNonNull(processTreeSupervisor, "processTreeSupervisor");
    }

    /**
     * Prepares a first managed attachment after the exact provider thread exists.
     *
     * @param authority existing exact binding authority
     * @return durable attachment metadata
     * @throws Exception when authentication or durable setup is unavailable
     */
    public ManagedAttachmentRecord prepareFirst(LifecycleControlRequestEnvelope.AuthorityContext authority)
            throws Exception {
        requireAuthority(authority);
        ManagedCodexThreadBroker broker = brokerFor(authority);
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, authority.bindingSessionId());
        CodexManagedRuntimeHome home = runtimeHome(authority.projectId());
        try {
            configureHome(home);
            ManagedAttachmentService.IssuedAttachment issued = attachmentService.issueFromOwnership(location,
                    authority.projectId(), authority.provider(), authority.bindingSessionId(), home.homeId(),
                    broker.ownership(), store);
            prepared.put(authority.bindingSessionId(), new PreparedLaunch(home, issued.proof(), issued.record()));
            return issued.record();
        } catch (Exception failure) {
            home.deleteAfterTerminal();
            throw failure;
        }
    }

    /**
     * Prepares a replacement after the old process has been proven stopped.
     *
     * @param authority exact existing binding authority
     * @param currentProof current raw proof held by the trusted launcher
     * @param expectedGeneration current attachment generation
     * @return replacement metadata
     * @throws Exception when the old attachment is live/ambiguous or stale
     */
    public ManagedAttachmentRecord prepareReplacement(LifecycleControlRequestEnvelope.AuthorityContext authority,
            String currentProof, long expectedGeneration) throws Exception {
        requireAuthority(authority);
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, authority.bindingSessionId());
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> new IOException("attachment_missing"));
        ManagedCodexThreadBroker broker = brokerFor(authority);
        CodexManagedRuntimeHome home = runtimeHome(authority.projectId());
        try {
            RuntimeAuthenticator.AttachmentRequest request = new RuntimeAuthenticator.AttachmentRequest(
                    authority.provider(), authority.bindingSessionId(), prior.threadId(), expectedGeneration,
                    currentProof);
            configureHome(home);
            ManagedAttachmentService.IssuedAttachment issued = attachmentService.reattachFromOwnership(location,
                    request, authority.projectId(), broker.ownership(), home.homeId(), store, true);
            prepared.put(authority.bindingSessionId(), new PreparedLaunch(home, issued.proof(), issued.record()));
            return issued.record();
        } catch (Exception failure) {
            home.deleteAfterTerminal();
            throw failure;
        }
    }

    /**
     * Launches the already prepared exact managed runtime.
     *
     * @param authority exact binding authority
     * @param attachmentGeneration lifecycle process generation
     * @return owned App Server process
     * @throws IOException when no authenticated preparation exists
     */
    @Override
    public CodexAppServerLifecycleService.AppServerProcess launch(
            LifecycleControlRequestEnvelope.AuthorityContext authority, long attachmentGeneration)
            throws IOException {
        PreparedLaunch launch = prepared.get(authority.bindingSessionId());
        if (launch == null || (launch.record().status() != ManagedAttachmentRecord.Status.ACTIVE
                && launch.record().status() != ManagedAttachmentRecord.Status.PENDING_ACTIVATION)
                || launch.record().generation() != attachmentGeneration) {
            throw new IOException("managed_attachment_not_prepared");
        }
        List<String> command = configuredCommand(launch.home(), synesisLauncher, location.root());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Path.of(authority.realWorktree()).toFile())
                .redirectError(ProcessBuilder.Redirect.PIPE);
        Map<String, String> environment = builder.environment();
        environment.put("CODEX_HOME", launch.home().path().toString());
        environment.put("SYNESIS_MCP_PROJECT", authority.controlProjectRoot());
        environment.put("SYNESIS_MCP_CONNECTION_INSTANCE_ID", authority.connectionInstanceId());
        environment.put("SYNESIS_MCP_PROVIDER", authority.provider());
        environment.put(CodexManagedRuntimeHome.ATTACHMENT_PROOF_ENV, launch.proof());
        Process process = processTreeSupervisor.launch(command, Path.of(authority.realWorktree()), environment);
        if (!processTreeSupervisor.owns(process)) {
            try {
                processTreeSupervisor.teardownAndProveEmpty(process);
            } catch (IOException ignored) {
                process.destroyForcibly();
            }
            throw new IOException("managed_process_tree_ownership_unproven");
        }
        ProcessHandle.Info info = ProcessHandle.of(process.pid()).map(ProcessHandle::info).orElse(null);
        String executable = info == null ? command.getFirst() : info.command().orElse(command.getFirst());
        String identity = info == null ? executable : info.commandLine().orElse(executable);
        long started = info == null ? System.currentTimeMillis()
                : info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        activeProcesses.put(authority.bindingSessionId(), process);
        return new CodexAppServerLifecycleService.AppServerProcess(process, executable, identity, started,
                processTreeSupervisor);
    }

    /**
     * Supplies the exact broker pin for the initial lifecycle operation.
     *
     * @param authority exact binding authority
     * @param attachmentGeneration managed generation
     * @return immutable provider thread selector
     * @throws IOException when the durable owner is unavailable
     */
    @Override
    public String expectedInitialThreadId(LifecycleControlRequestEnvelope.AuthorityContext authority,
            long attachmentGeneration) throws IOException {
        requireAuthority(authority);
        return brokerFor(authority).pinnedThreadId();
    }

    /**
     * Verifies the provider result before managed authority can be considered
     * joined to this lifecycle generation.
     *
     * @param authority exact binding authority
     * @param attachmentGeneration managed generation
     * @param returnedThreadId provider-returned thread selector
     * @throws IOException when the result is not the immutable pin
     */
    @Override
    public void verifyReturnedThread(LifecycleControlRequestEnvelope.AuthorityContext authority,
            long attachmentGeneration, String returnedThreadId) throws IOException {
        requireAuthority(authority);
        try {
            brokerFor(authority).verifyReturnedThread(returnedThreadId);
        } catch (RuntimeException mismatch) {
            throw new IOException(mismatch.getMessage(), mismatch);
        }
    }

    /**
     * Commits the pending proof activation after the exact thread join.
     *
     * @param authority exact binding authority
     * @param attachmentGeneration managed generation
     * @throws IOException when activation is stale or unavailable
     */
    @Override
    public void activateManagedAttachment(LifecycleControlRequestEnvelope.AuthorityContext authority,
            long attachmentGeneration) throws IOException {
        requireAuthority(authority);
        try {
            attachmentService.activate(ManagedAttachmentService.storeFor(location, authority.bindingSessionId()),
                    attachmentGeneration);
        } catch (Exception failure) {
            throw new IOException("managed_attachment_activation_failed", failure);
        }
    }

    /**
     * Tears down one exact managed process tree and only then marks its
     * attachment disconnected.
     *
     * @param authority exact binding authority
     * @param expectedGeneration generation being torn down
     * @return true when the owned tree was proven empty
     * @throws Exception when ownership, generation, or liveness is ambiguous
     */
    public boolean teardownAndProveEmpty(LifecycleControlRequestEnvelope.AuthorityContext authority,
            long expectedGeneration) throws Exception {
        requireAuthority(authority);
        PreparedLaunch preparedLaunch = prepared.get(authority.bindingSessionId());
        Process process = activeProcesses.get(authority.bindingSessionId());
        if (preparedLaunch == null || process == null || preparedLaunch.record().generation() != expectedGeneration) {
            throw new IOException("managed_process_tree_missing_or_stale");
        }
        if (!processTreeSupervisor.owns(process) || !processTreeSupervisor.teardownAndProveEmpty(process)) {
            throw new IOException("managed_process_tree_death_ambiguous");
        }
        managedTreeStopped(authority, expectedGeneration);
        return true;
    }

    /**
     * Records a definitively empty process tree and removes the in-memory
     * process handle from the active generation.
     *
     * @param authority exact binding authority
     * @param attachmentGeneration stopped generation
     * @throws IOException when managed state cannot be updated
     */
    @Override
    public void managedTreeStopped(LifecycleControlRequestEnvelope.AuthorityContext authority,
            long attachmentGeneration) throws IOException {
        requireAuthority(authority);
        PreparedLaunch launch = prepared.get(authority.bindingSessionId());
        if (launch != null && launch.record().generation() == attachmentGeneration) {
            try {
                ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location,
                        authority.bindingSessionId());
                ManagedAttachmentRecord record = store.read().orElseThrow(() -> new IOException("attachment_missing"));
                if (record.status() != ManagedAttachmentRecord.Status.DISCONNECTED
                        && record.status() != ManagedAttachmentRecord.Status.TERMINAL) {
                    attachmentService.markDisconnected(store);
                }
            } catch (Exception failure) {
                throw new IOException("managed_attachment_disconnect_failed", failure);
            }
        }
        activeProcesses.remove(authority.bindingSessionId());
    }

    /**
     * Terminalizes an attachment and releases its provider-thread tombstone
     * only after terminal state is durably recorded.
     *
     * @param authority exact binding authority
     * @throws Exception when terminalization or exact ownership release fails
     */
    public void terminalizeAndRelease(LifecycleControlRequestEnvelope.AuthorityContext authority) throws Exception {
        requireAuthority(authority);
        ManagedAttachmentService attachment = attachmentService;
        attachment.terminalize(ManagedAttachmentService.storeFor(location, authority.bindingSessionId()));
        ProviderThreadOwnershipRecord ownership = brokerFor(authority).ownership();
        ownershipStore.release(ownership.provider(), ownership.providerThreadId(), authority.bindingSessionId());
    }

    /**
     * Returns current prepared metadata without exposing raw proof.
     *
     * @param bindingSessionId exact existing binding session
     * @return prepared metadata, or {@code null} when absent
     */
    public ManagedAttachmentRecord preparedRecord(String bindingSessionId) {
        PreparedLaunch launch = prepared.get(bindingSessionId);
        return launch == null ? null : launch.record();
    }

    /**
     * Records a provider-returned thread in the durable owner before attachment.
     *
     * @param authority exact binding authority
     * @param providerReturnedThreadId exact provider result
     * @return active ownership record
     * @throws IOException when another binding already owns the thread
     */
    public ProviderThreadOwnershipRecord observeAndClaimProviderThread(
            LifecycleControlRequestEnvelope.AuthorityContext authority, String providerReturnedThreadId)
            throws IOException {
        requireAuthority(authority);
        return ownershipStore.acquire(authority.projectId(), authority.provider(), providerReturnedThreadId,
                authority.bindingSessionId());
    }

    private ManagedCodexThreadBroker brokerFor(LifecycleControlRequestEnvelope.AuthorityContext authority)
            throws IOException {
        return new ManagedCodexThreadBroker(ownershipStore.findByBinding("codex", authority.bindingSessionId())
                .orElseThrow(() -> new IOException("provider_thread_ownership_missing")));
    }

    private void configureHome(CodexManagedRuntimeHome home) throws IOException {
        CodexManagedAuthentication.Strategy authentication = CodexManagedAuthentication.forMode(runtimeMode,
                CodexManagedAuthentication.userHome());
        if (runtimeMode == ManagedCodexRuntimeMode.ISOLATED_DEDICATED_HOME) {
            home.writeConfiguration(synesisLauncher, location.root(), authentication);
        } else if (authentication != CodexManagedAuthentication.Strategy.NORMAL_PROVIDER_HOME) {
            throw new IOException("normal_provider_home_strategy_unavailable");
        }
    }

    private CodexManagedRuntimeHome runtimeHome(String projectId) throws IOException {
        return runtimeMode == ManagedCodexRuntimeMode.NORMAL_PROVIDER_HOME_MANAGED
                ? CodexManagedRuntimeHome.normalProviderHome() : CodexManagedRuntimeHome.create(projectId);
    }

    private static List<String> configuredCommand(CodexManagedRuntimeHome home, Path launcher, Path projectRoot) {
        String configured = System.getenv("SYNESIS_CODEX_APP_SERVER_COMMAND");
        List<String> command = configured == null || configured.isBlank()
                ? new ArrayList<>(List.of("codex", "app-server", "--stdio"))
                : new ArrayList<>(List.of(configured.trim().split("\\s+")));
        command.add("-c");
        command.add("mcp_servers.synesis.command=" + tomlString(launcher));
        command.add("-c");
        command.add("mcp_servers.synesis.args=[\"mcp\",\"--provider\",\"codex\",\"--project\","
                + tomlString(projectRoot) + "]");
        command.add("-c");
        command.add("mcp_servers.synesis.env_vars=[\"" + CodexManagedRuntimeHome.ATTACHMENT_PROOF_ENV + "\"]");
        // The normal home is provider-owned. The home value is deliberately
        // only carried in the process environment, never in this config.
        if (home.path() == null) {
            throw new IllegalStateException("managed_runtime_home_missing");
        }
        return List.copyOf(command);
    }

    private static String tomlString(Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void requireAuthority(LifecycleControlRequestEnvelope.AuthorityContext authority) {
        Objects.requireNonNull(authority, "authority");
        if (!"codex".equals(authority.provider())) {
            throw new IllegalArgumentException("managed Codex attachment requires Codex provider");
        }
    }

    private record PreparedLaunch(CodexManagedRuntimeHome home, String proof, ManagedAttachmentRecord record) {
    }
}
