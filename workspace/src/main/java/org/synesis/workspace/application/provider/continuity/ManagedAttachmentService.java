package org.synesis.workspace.application.provider.continuity;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Issues, authenticates, and atomically rotates managed attachment proofs.
 *
 * <p>This service is deliberately independent of provider coordination
 * identity. The shared-home production path accepts an active durable
 * provider-thread owner and derives the selector from it; the legacy direct
 * selector overload remains only for the earlier isolated-home adapter.</p>
 */
public final class ManagedAttachmentService implements RuntimeAuthenticator {

    private static final int PROOF_BYTES = 32;
    private final SecureRandom random;

    /** Creates a service using a cryptographically secure random source. */
    public ManagedAttachmentService() {
        this(new SecureRandom());
    }

    /**
     * Creates a service with an injectable random source for tests.
     *
     * @param random cryptographically secure proof source
     */
    public ManagedAttachmentService(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Issues the first proof and durable attachment record.
     *
     * @param location project location
     * @param projectId durable project ID
     * @param provider provider identifier
     * @param bindingSessionId existing binding session
     * @param threadId exact provider thread
     * @param runtimeHomeId opaque managed runtime-home ID
     * @param store durable adapter-private store
     * @return raw proof for trusted process setup and its durable record
     * @throws Exception when a conflicting record exists
     */
    public synchronized IssuedAttachment issue(ProjectApplicationService.ProjectLocation location, String projectId,
            String provider, String bindingSessionId, String threadId, String runtimeHomeId,
            ManagedAttachmentStore store) throws Exception {
        Objects.requireNonNull(location, "location");
        requireCodex(provider);
        Objects.requireNonNull(store, "store");
        if (!location.projectId().toString().equals(projectId)) {
            throw new IllegalArgumentException("managed attachment project mismatch");
        }
        if (store.read().isPresent()) {
            throw new IllegalStateException("managed attachment already exists");
        }
        String proof = randomProof();
        ManagedAttachmentRecord record = new ManagedAttachmentRecord(
                ManagedAttachmentRecord.CURRENT_SCHEMA_VERSION, projectId, provider,
                ProviderContinuityMode.MANAGED_CONTINUITY, bindingSessionId, threadId, 1L,
                hash(proof), runtimeHomeId, ManagedAttachmentRecord.Status.ACTIVE, 1L,
                System.currentTimeMillis());
        store.write(record);
        return new IssuedAttachment(proof, record);
    }

    /**
     * Issues an attachment from trusted durable provider-thread ownership.
     *
     * <p>This is the production path for shared normal-home mode. The thread
     * selector is taken from the ownership record rather than a caller or
     * model argument.</p>
     *
     * @param location project location
     * @param projectId durable project ID
     * @param provider provider identifier
     * @param bindingSessionId existing binding session
     * @param runtimeHomeId provider-owned runtime-home identity
     * @param ownership active durable provider-thread owner
     * @param store durable attachment store
     * @return raw proof for trusted process setup and its durable record
     * @throws Exception when ownership or durable setup is invalid
     */
    public synchronized IssuedAttachment issueFromOwnership(ProjectApplicationService.ProjectLocation location,
            String projectId, String provider, String bindingSessionId, String runtimeHomeId,
            ProviderThreadOwnershipRecord ownership, ManagedAttachmentStore store) throws Exception {
        verifyOwnership(ownership, projectId, provider, bindingSessionId);
        IssuedAttachment issued = issue(location, projectId, provider, bindingSessionId, ownership.providerThreadId(),
                runtimeHomeId, store);
        ManagedAttachmentRecord pending = withStatus(issued.record(), ManagedAttachmentRecord.Status.PENDING_ACTIVATION);
        store.write(pending);
        return new IssuedAttachment(issued.proof(), pending);
    }

    /**
     * Authenticates an exact managed attachment without changing state.
     *
     * @param location project location
     * @param request exact provider/binding/thread/proof request
     * @return authenticated existing runtime
     * @throws Exception when any exact predicate fails
     */
    @Override
    public synchronized AuthenticatedRuntime authenticate(ProjectApplicationService.ProjectLocation location,
            AttachmentRequest request) throws Exception {
        return authenticate(location, request, location.projectId().toString(),
                defaultStore(location, request.bindingSessionId()));
    }

    /**
     * Authenticates against an explicitly selected adapter-private store.
     *
     * @param location project location
     * @param request exact provider/binding/thread/proof request
     * @param expectedProjectId project identity
     * @param store durable attachment store
     * @return authenticated existing runtime
     * @throws Exception when any exact predicate fails
     */
    public synchronized AuthenticatedRuntime authenticate(ProjectApplicationService.ProjectLocation location,
            AttachmentRequest request, String expectedProjectId, ManagedAttachmentStore store) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(store, "store");
        ManagedAttachmentRecord record = store.read().orElseThrow(() -> failure("attachment_missing"));
        verifyRecord(record, request, expectedProjectId, false, false);
        return new AuthenticatedRuntime(record.provider(), record.bindingSessionId(), record.mode(),
                "synesis-managed-proof", record.generation());
    }

    /**
     * Authenticates a managed transport before authority activation.
     *
     * <p>This is deliberately separate from {@link #authenticate}:
     * {@code PENDING_ACTIVATION} proves the launch scope but is not an
     * authority-bearing runtime. Callers must keep the returned record
     * quarantined until the trusted lifecycle changes it to {@code ACTIVE}.
     * Terminal, stale, wrong-scope, and proof-less requests remain rejected.</p>
     *
     * @param location project location
     * @param request exact provider/binding/thread/proof request
     * @param expectedProjectId project identity
     * @param store durable attachment store
     * @return the authenticated non-secret attachment record
     * @throws Exception when the launch proof or scope is invalid
     */
    public synchronized ManagedAttachmentRecord authenticateTransport(
            ProjectApplicationService.ProjectLocation location, AttachmentRequest request,
            String expectedProjectId, ManagedAttachmentStore store) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(store, "store");
        ManagedAttachmentRecord record = store.read().orElseThrow(() -> failure("attachment_missing"));
        verifyRecord(record, request, expectedProjectId, false, true);
        return record;
    }

    /**
     * Acquires the one process-local transport slot for an attachment
     * generation.
     *
     * <p>The lock is separate from the durable record lock so lifecycle
     * activation can still update the record while the authenticated MCP
     * process remains connected. It contains no proof or authority data and
     * is released by the operating system if the process exits.</p>
     *
     * @param location project location
     * @param bindingSessionId exact managed binding
     * @param generation attachment generation
     * @return held transport slot
     * @throws IOException when another process already owns the slot
     */
    public TransportLease acquireTransport(ProjectApplicationService.ProjectLocation location,
            String bindingSessionId, long generation) throws IOException {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(bindingSessionId, "bindingSessionId");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        ManagedAttachmentStore store = storeFor(location, bindingSessionId);
        ManagedAttachmentRecord record = store.read().orElseThrow(() -> failure("attachment_missing"));
        if (record.generation() != generation
                || (record.status() != ManagedAttachmentRecord.Status.ACTIVE
                        && record.status() != ManagedAttachmentRecord.Status.PENDING_ACTIVATION)) {
            throw failure("managed_transport_generation_rejected");
        }
        Path lockPath = store.file().resolveSibling(store.file().getFileName() + ".generation-"
                + generation + ".transport.lock");
        Files.createDirectories(lockPath.getParent());
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw failure("managed_transport_already_connected");
            }
            return new TransportLease(channel, lock);
        } catch (OverlappingFileLockException | IOException failure) {
            try {
                channel.close();
            } catch (IOException ignored) {
                failure.addSuppressed(ignored);
            }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("managed_transport_already_connected", failure);
            }
            throw failure;
        }
    }

    private static void verifyRecord(ManagedAttachmentRecord record, AttachmentRequest request,
            String expectedProjectId, boolean replacement, boolean allowPending) throws Exception {
        boolean statusValid = replacement ? record.status() != ManagedAttachmentRecord.Status.TERMINAL
                : (record.status() == ManagedAttachmentRecord.Status.ACTIVE
                        || (allowPending && record.status() == ManagedAttachmentRecord.Status.PENDING_ACTIVATION));
        if (!expectedProjectId.equals(record.projectId()) || !request.provider().equals(record.provider())
                || !request.bindingSessionId().equals(record.bindingSessionId())
                || !request.threadId().equals(record.threadId())
                || request.expectedGeneration() != record.generation()
                || !statusValid
                || !constantTimeEquals(record.proofHash(), hash(request.proof()))) {
            throw failure("attachment_rejected");
        }
    }

    /**
     * Atomically consumes the current proof and replaces the attachment.
     *
     * @param location project location
     * @param request exact current attachment request
     * @param expectedProjectId project identity
     * @param newThreadId exact replacement thread
     * @param runtimeHomeId replacement runtime home
     * @param store durable attachment store
     * @param priorAttachmentProvenStopped whether the previous process is independently proven stopped
     * @return new raw proof and durable record
     * @throws Exception when the current state is stale, terminal, or invalid
     */
    public synchronized IssuedAttachment reattach(ProjectApplicationService.ProjectLocation location,
            AttachmentRequest request, String expectedProjectId, String newThreadId, String runtimeHomeId,
            ManagedAttachmentStore store, boolean priorAttachmentProvenStopped) throws Exception {
        if (!priorAttachmentProvenStopped) {
            throw failure("attachment_live_or_ambiguous");
        }
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> failure("attachment_missing"));
        verifyRecord(prior, request, expectedProjectId, true, false);
        String proof = randomProof();
        ManagedAttachmentRecord next = new ManagedAttachmentRecord(
                ManagedAttachmentRecord.CURRENT_SCHEMA_VERSION, prior.projectId(), prior.provider(), prior.mode(),
                prior.bindingSessionId(), newThreadId, prior.generation() + 1L, hash(proof), runtimeHomeId,
                ManagedAttachmentRecord.Status.ACTIVE, prior.revision() + 1L, System.currentTimeMillis());
        if (!store.compareAndReplace(prior.generation(), prior.proofHash(), next)) {
            throw failure("attachment_replacement_race");
        }
        return new IssuedAttachment(proof, next);
    }

    /**
     * Reattaches to the same provider-thread owner while rotating runtime proof.
     *
     * @param location project location
     * @param request exact current attachment request
     * @param expectedProjectId project identity
     * @param ownership active durable provider-thread owner
     * @param runtimeHomeId provider-owned runtime-home identity
     * @param store durable attachment store
     * @param priorAttachmentProvenStopped whether the prior tree is proven dead
     * @return fresh proof and generation record
     * @throws Exception when ownership, liveness, or fencing validation fails
     */
    public synchronized IssuedAttachment reattachFromOwnership(ProjectApplicationService.ProjectLocation location,
            AttachmentRequest request, String expectedProjectId, ProviderThreadOwnershipRecord ownership,
            String runtimeHomeId, ManagedAttachmentStore store, boolean priorAttachmentProvenStopped) throws Exception {
        verifyOwnership(ownership, expectedProjectId, request.provider(), request.bindingSessionId());
        if (!ownership.providerThreadId().equals(request.threadId())) {
            throw failure("managed_provider_thread_mismatch");
        }
        IssuedAttachment issued = reattach(location, request, expectedProjectId, ownership.providerThreadId(),
                runtimeHomeId, store, priorAttachmentProvenStopped);
        ManagedAttachmentRecord pending = withStatus(issued.record(), ManagedAttachmentRecord.Status.PENDING_ACTIVATION);
        store.write(pending);
        return new IssuedAttachment(issued.proof(), pending);
    }

    /**
     * Activates a pending generation after the trusted lifecycle has verified
     * the exact provider-thread response and readback.
     *
     * @param store exact attachment store
     * @param expectedGeneration generation verified by the broker
     * @throws Exception when the record is missing, stale, or already fenced
     */
    public synchronized void activate(ManagedAttachmentStore store, long expectedGeneration) throws Exception {
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> failure("attachment_missing"));
        if (prior.generation() != expectedGeneration
                || prior.status() != ManagedAttachmentRecord.Status.PENDING_ACTIVATION) {
            throw failure("managed_attachment_activation_rejected");
        }
        store.write(withStatus(prior, ManagedAttachmentRecord.Status.ACTIVE));
    }

    /**
     * Marks an exact attachment disconnected without lowering its generation.
     *
     * @param store durable attachment store
     * @throws Exception when the record is missing, terminal, or unwritable
     */
    public synchronized void markDisconnected(ManagedAttachmentStore store) throws Exception {
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> failure("attachment_missing"));
        if (prior.status() == ManagedAttachmentRecord.Status.TERMINAL) {
            throw failure("attachment_terminal");
        }
        store.write(new ManagedAttachmentRecord(prior.schemaVersion(), prior.projectId(), prior.provider(),
                prior.mode(), prior.bindingSessionId(), prior.threadId(), prior.generation(), prior.proofHash(),
                prior.runtimeHomeId(), ManagedAttachmentRecord.Status.DISCONNECTED, prior.revision() + 1L,
                System.currentTimeMillis()));
    }

    /**
     * Permanently fences an attachment.
     *
     * @param store durable attachment store
     * @throws Exception when the record is missing or unwritable
     */
    public synchronized void terminalize(ManagedAttachmentStore store) throws Exception {
        ManagedAttachmentRecord prior = store.read().orElseThrow(() -> failure("attachment_missing"));
        if (prior.status() == ManagedAttachmentRecord.Status.TERMINAL) {
            return;
        }
        store.write(new ManagedAttachmentRecord(prior.schemaVersion(), prior.projectId(), prior.provider(),
                prior.mode(), prior.bindingSessionId(), prior.threadId(), prior.generation(), prior.proofHash(),
                prior.runtimeHomeId(), ManagedAttachmentRecord.Status.TERMINAL, prior.revision() + 1L,
                System.currentTimeMillis()));
    }

    private String randomProof() {
        byte[] bytes = new byte[PROOF_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String proof) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(proof.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireCodex(String provider) {
        if (!"codex".equals(provider)) {
            throw new IllegalArgumentException("managed continuity currently supports codex only");
        }
    }

    private static void verifyOwnership(ProviderThreadOwnershipRecord ownership, String projectId, String provider,
            String bindingSessionId) {
        Objects.requireNonNull(ownership, "ownership");
        if (ownership.status() != ProviderThreadOwnershipRecord.Status.ACTIVE
                || !projectId.equals(ownership.projectId())
                || !provider.equals(ownership.provider())
                || !bindingSessionId.equals(ownership.bindingSessionId())) {
            throw new IllegalStateException("provider_thread_ownership_rejected");
        }
    }

    private static ManagedAttachmentRecord withStatus(ManagedAttachmentRecord prior,
            ManagedAttachmentRecord.Status status) {
        return new ManagedAttachmentRecord(prior.schemaVersion(), prior.projectId(), prior.provider(), prior.mode(),
                prior.bindingSessionId(), prior.threadId(), prior.generation(), prior.proofHash(),
                prior.runtimeHomeId(), status, prior.revision() + 1L, System.currentTimeMillis());
    }

    private static IllegalStateException failure(String diagnostic) {
        return new IllegalStateException(diagnostic);
    }

    private static ManagedAttachmentStore defaultStore(ProjectApplicationService.ProjectLocation location,
            String bindingSessionId) {
        return new ManagedAttachmentStore(location.synesisDirectory().resolve("local/runtime/managed-continuity")
                .resolve(bindingSessionId + ".json"));
    }

    /**
     * Returns the conventional adapter-private store for one existing binding.
     *
     * @param location project location
     * @param bindingSessionId exact existing binding session
     * @return managed attachment store
     */
    public static ManagedAttachmentStore storeFor(ProjectApplicationService.ProjectLocation location,
            String bindingSessionId) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(bindingSessionId, "bindingSessionId");
        return defaultStore(location, bindingSessionId);
    }

    /**
     * Raw proof plus durable public metadata; do not log the proof.
     *
     * @param proof  raw proof for trusted process setup
     * @param record durable non-secret attachment metadata
     */
    public record IssuedAttachment(String proof, ManagedAttachmentRecord record) {
        /** Validates the trusted handoff result. */
        public IssuedAttachment {
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(record, "record");
            if (proof.length() != PROOF_BYTES * 2) {
                throw new IllegalArgumentException("invalid attachment proof");
            }
        }
    }

    /**
     * Operating-system released slot for one managed transport generation.
     */
    public static final class TransportLease implements Closeable {

        private final FileChannel channel;
        private final FileLock lock;

        private TransportLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /**
         * Releases the transport slot and its file handle.
         *
         * @throws IOException when the operating-system resources cannot close
         */
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
