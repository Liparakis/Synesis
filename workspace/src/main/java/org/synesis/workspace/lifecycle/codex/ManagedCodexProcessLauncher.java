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
    private final Map<String, PreparedLaunch> prepared = new ConcurrentHashMap<>();

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
        this.location = Objects.requireNonNull(location, "location");
        this.synesisLauncher = Objects.requireNonNull(synesisLauncher, "synesisLauncher")
                .toAbsolutePath().normalize();
        this.attachmentService = Objects.requireNonNull(attachmentService, "attachmentService");
    }

    /**
     * Prepares a first managed attachment after the exact provider thread exists.
     *
     * @param authority existing exact binding authority
     * @param threadId exact Codex thread
     * @return durable attachment metadata
     * @throws Exception when authentication or durable setup is unavailable
     */
    public ManagedAttachmentRecord prepareFirst(LifecycleControlRequestEnvelope.AuthorityContext authority,
            String threadId) throws Exception {
        requireAuthority(authority, threadId);
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, authority.bindingSessionId());
        CodexManagedAuthentication.Strategy authentication = CodexManagedAuthentication.inspect(
                CodexManagedAuthentication.userHome());
        CodexManagedRuntimeHome home = CodexManagedRuntimeHome.create(authority.projectId());
        try {
            home.writeConfiguration(synesisLauncher, location.root(), authentication);
            ManagedAttachmentService.IssuedAttachment issued = attachmentService.issue(location,
                    authority.projectId(), authority.provider(), authority.bindingSessionId(), threadId, home.homeId(),
                    store);
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
     * @param threadId exact resumed thread
     * @return replacement metadata
     * @throws Exception when the old attachment is live/ambiguous or stale
     */
    public ManagedAttachmentRecord prepareReplacement(LifecycleControlRequestEnvelope.AuthorityContext authority,
            String currentProof, long expectedGeneration, String threadId) throws Exception {
        requireAuthority(authority, threadId);
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, authority.bindingSessionId());
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> new IOException("attachment_missing"));
        CodexManagedRuntimeHome home = CodexManagedRuntimeHome.create(authority.projectId());
        try {
            RuntimeAuthenticator.AttachmentRequest request = new RuntimeAuthenticator.AttachmentRequest(
                    authority.provider(), authority.bindingSessionId(), prior.threadId(), expectedGeneration,
                    currentProof);
            CodexManagedAuthentication.Strategy authentication = CodexManagedAuthentication.inspect(
                    CodexManagedAuthentication.userHome());
            home.writeConfiguration(synesisLauncher, location.root(), authentication);
            ManagedAttachmentService.IssuedAttachment issued = attachmentService.reattach(location, request,
                    authority.projectId(), threadId, home.homeId(), store, true);
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
        if (launch == null || launch.record().status() != ManagedAttachmentRecord.Status.ACTIVE
                || launch.record().generation() != attachmentGeneration) {
            throw new IOException("managed_attachment_not_prepared");
        }
        List<String> command = configuredCommand();
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Path.of(authority.realWorktree()).toFile())
                .redirectError(ProcessBuilder.Redirect.PIPE);
        Map<String, String> environment = builder.environment();
        environment.put("CODEX_HOME", launch.home().path().toString());
        environment.put("SYNESIS_MCP_PROJECT", authority.controlProjectRoot());
        environment.put("SYNESIS_MCP_CONNECTION_INSTANCE_ID", authority.connectionInstanceId());
        environment.put("SYNESIS_MCP_PROVIDER", authority.provider());
        environment.put(CodexManagedRuntimeHome.ATTACHMENT_PROOF_ENV, launch.proof());
        Process process = builder.start();
        ProcessHandle.Info info = ProcessHandle.of(process.pid()).map(ProcessHandle::info).orElse(null);
        String executable = info == null ? command.getFirst() : info.command().orElse(command.getFirst());
        String identity = info == null ? executable : info.commandLine().orElse(executable);
        long started = info == null ? System.currentTimeMillis()
                : info.startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        return new CodexAppServerLifecycleService.AppServerProcess(process, executable, identity, started);
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

    private static List<String> configuredCommand() {
        String configured = System.getenv("SYNESIS_CODEX_APP_SERVER_COMMAND");
        if (configured == null || configured.isBlank()) {
            return List.of("codex", "app-server", "--stdio");
        }
        return List.of(configured.trim().split("\\s+"));
    }

    private static void requireAuthority(LifecycleControlRequestEnvelope.AuthorityContext authority, String threadId) {
        Objects.requireNonNull(authority, "authority");
        if (!"codex".equals(authority.provider()) || threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("managed Codex attachment requires exact thread");
        }
    }

    private record PreparedLaunch(CodexManagedRuntimeHome home, String proof, ManagedAttachmentRecord record) {
    }
}
