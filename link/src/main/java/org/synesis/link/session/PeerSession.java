package org.synesis.link.session;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;

/**
 * Immutable authenticated session snapshot safe to expose above transport.
 *
 * <p>Construction is package-private so only the authentication path can
 * publish a session. QUIC channels, TLS engines, native handles, and private
 * key material are deliberately absent from this API. Mutable liveness and
 * stream lifecycle APIs are added only by their corresponding later slices.
 *
 * @since 1.0
 */
public final class PeerSession {

    /** Transport-neutral callback for one bounded application payload. */
    @FunctionalInterface
    public interface ApplicationStreamHandler {
        /**
         * Handles one payload after authentication and control readiness.
         *
         * @param remoteNodeId authenticated remote node ID
         * @param payload bounded opaque payload
         * @return bounded response payload
         */
        CompletionStage<byte[]> handle(String remoteNodeId, byte[] payload);
    }

    /** Transport-neutral binding for one bounded application-stream exchange. */
    public interface ApplicationStreamBinding {
        /**
         * Sends one bounded payload on an authenticated application stream.
         *
         * @param payload opaque payload
         * @return bounded response or failure
         */
        CompletionStage<byte[]> exchange(byte[] payload);
    }

    /** Transport-neutral owner for the bounded demo-only work exchange. */
    public interface DemoWorkBinding {
        /**
         * Sends one request on an authenticated, control-ready application stream.
         *
         * @param request fixed bounded demo request
         * @return correlated result or failure
         */
        CompletionStage<DemoWorkResult> request(DemoWorkRequest request);
    }

    /**
     * Transport-neutral lifecycle bridge owned by the authenticated control stream.
     * Implementations must serialize writes on their transport event loop.
     */
    public interface ControlBinding {
        /**
         * Reports whether CONTROL_READY has been exchanged.
         *
         * @return whether the control stream is ready
         */
        boolean isReady();

        /**
         * Returns the optional demo-only application binding owned by this control stream.
         *
         * @return application binding, or {@code null} when no demo stream is installed
         */
        default DemoWorkBinding demoWorkBinding() { return null; }

        /**
         * Returns the optional bounded application-stream binding.
         *
         * @return application binding, or {@code null} when unavailable
         */
        default ApplicationStreamBinding applicationStreamBinding() { return null; }

        /**
         * Requests one shared graceful close operation.
         *
         * @param reason requested close reason
         * @return shared terminal completion
         */
        CompletionStage<Void> closeGracefully(SessionCloseReason reason);

        /**
         * Returns terminal transport completion.
         *
         * @return completion that finishes exactly once
         */
        CompletionStage<Void> terminalCompletion();

        /**
         * Returns the final close reason.
         *
         * @return final close reason, or {@code null} before terminal closure
         */
        SessionCloseReason closeReason();

        /**
         * Returns the current liveness state.
         *
         * @return immutable liveness state
         */
        LivenessState livenessState();

        /**
         * Returns safe liveness counters and diagnostics.
         *
         * @return immutable metrics snapshot
         */
        LivenessMetrics livenessMetrics();

        /**
         * Registers a bounded asynchronous transition listener.
         *
         * @param listener listener to retain until removal or terminal cleanup
         */
        void addLivenessListener(LivenessListener listener);

        /**
         * Removes a previously registered listener.
         *
         * @param listener listener to remove
         */
        void removeLivenessListener(LivenessListener listener);
    }

    private final String localNodeId;
    private final String remoteNodeId;
    private final byte[] remotePublicKey;
    private final UUID sessionId;
    private final long localEpoch;
    private final long remoteEpoch;
    private final ProtocolVersion version;
    private final Instant establishedAt;
    private volatile ControlBinding control;
    private volatile DemoWorkBinding demoWork;
    private volatile ApplicationStreamBinding applicationStream;

    PeerSession(String localNodeId, String remoteNodeId, byte[] remotePublicKey, UUID sessionId,
            long localEpoch, long remoteEpoch, ProtocolVersion version, Instant establishedAt) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "local node ID");
        this.remoteNodeId = Objects.requireNonNull(remoteNodeId, "remote node ID");
        this.remotePublicKey = remotePublicKey.clone();
        this.sessionId = Objects.requireNonNull(sessionId, "session ID");
        this.localEpoch = localEpoch;
        this.remoteEpoch = remoteEpoch;
        this.version = Objects.requireNonNull(version, "version");
        this.establishedAt = Objects.requireNonNull(establishedAt, "established at");
    }

    /**
     * Returns the local node ID.
     *
     * @return local node ID
     */
    public String localNodeId() { return localNodeId; }

    /**
     * Returns the authenticated remote node ID.
     *
     * @return remote node ID
     */
    public String remoteNodeId() { return remoteNodeId; }

    /**
     * Returns a copy of the authenticated remote X.509 public key.
     *
     * @return remote public-key bytes
     */
    public byte[] remotePublicKeyEncoded() { return remotePublicKey.clone(); }

    /**
     * Returns the unique session ID.
     *
     * @return session ID
     */
    public UUID sessionId() { return sessionId; }

    /**
     * Returns the local session epoch.
     *
     * @return local epoch
     */
    public long localEpoch() { return localEpoch; }

    /**
     * Returns the remote session epoch.
     *
     * @return remote epoch
     */
    public long remoteEpoch() { return remoteEpoch; }

    /**
     * Returns the negotiated protocol version.
     *
     * @return protocol version
     */
    public ProtocolVersion negotiatedVersion() { return version; }

    /**
     * Returns the local establishment timestamp.
     *
     * @return establishment time
     */
    public Instant establishedAt() { return establishedAt; }

    /**
     * Compares the authenticated remote key with supplied bytes.
     *
     * @param encoded candidate public key
     * @return whether the key is identical
     */
    public boolean hasRemotePublicKey(byte[] encoded) {
        return Arrays.equals(remotePublicKey, encoded);
    }

    /**
     * Installs the single authenticated control binding.
     *
     * @param binding control-stream lifecycle owner
     * @throws IllegalStateException if a binding is already installed
     */
    public synchronized void attachControl(ControlBinding binding) {
        Objects.requireNonNull(binding, "control binding");
        if (control != null) {
            throw new IllegalStateException("control binding already installed");
        }
        control = binding;
        demoWork = binding.demoWorkBinding();
        applicationStream = binding.applicationStreamBinding();
    }

    /**
     * Returns whether the control stream is ready and the session is usable.
     *
     * @return true only after CONTROL_READY validation
     */
    public boolean isUsable() {
        ControlBinding binding = control;
        return binding != null && binding.isReady();
    }

    /**
     * Sends one fixed demo request after reciprocal CONTROL_READY.
     *
     * <p>This is an example validation protocol, not a general RPC or production
     * Synesis cooperation API. It is non-blocking, bounded by the transport
     * binding, and rejects use before authentication/readiness or after close.
     *
     * @param request bounded demo request
     * @return correlated result completion
     * @throws IllegalStateException if the session is not control-ready or has no demo binding
     */
    public CompletionStage<DemoWorkResult> requestDemoWork(DemoWorkRequest request) {
        DemoWorkBinding binding = demoWork;
        if (!isUsable() || binding == null) {
            throw new IllegalStateException("demo work requires a control-ready session");
        }
        return binding.request(Objects.requireNonNull(request, "request"));
    }

    /**
     * Sends one bounded opaque payload after reciprocal control readiness.
     *
     * <p>Link does not interpret, persist, retry, or authorize the payload.
     * The transport binding owns framing, limits, deadlines, and cleanup.
     *
     * @param payload opaque bounded payload
     * @return bounded response completion
     * @throws IllegalStateException if the session is not usable or has no
     *                               application-stream binding
     */
    public CompletionStage<byte[]> requestApplication(byte[] payload) {
        ApplicationStreamBinding binding = applicationStream;
        if (!isUsable() || binding == null) {
            throw new IllegalStateException("application stream requires a control-ready session");
        }
        return binding.exchange(Objects.requireNonNull(payload, "payload"));
    }

    /**
     * Requests one bounded graceful close operation.
     *
     * <p>The operation is non-blocking and idempotent. Calls made before the
     * control stream is attached fail rather than pretending delivery occurred.
     *
     * @param reason local close reason
     * @return shared terminal completion
     */
    public CompletionStage<Void> closeGracefully(SessionCloseReason reason) {
        ControlBinding binding = control;
        return binding == null ? CompletableFuture.failedFuture(
                new IllegalStateException("control stream is not established"))
                : binding.closeGracefully(Objects.requireNonNull(reason, "reason"));
    }

    /**
     * Returns completion of terminal control/transport shutdown.
     *
     * @return terminal completion stage
     */
    public CompletionStage<Void> terminalCompletion() {
        ControlBinding binding = control;
        return binding == null ? CompletableFuture.failedFuture(
                new IllegalStateException("control stream is not established"))
                : binding.terminalCompletion();
    }

    /**
     * Returns the final close reason.
     *
     * @return close reason, or {@code null} while open
     */
    public SessionCloseReason closeReason() {
        ControlBinding binding = control;
        return binding == null ? null : binding.closeReason();
    }

    /**
     * Returns the current liveness state; an unattached authenticated snapshot
     * remains {@link LivenessState#CONNECTING}.
     *
     * @return current liveness state
     */
    public LivenessState livenessState() {
        ControlBinding binding = control;
        return binding == null ? LivenessState.CONNECTING : binding.livenessState();
    }

    /**
     * Returns an immutable safe liveness metrics snapshot.
     *
     * @return liveness metrics
     */
    public LivenessMetrics livenessMetrics() {
        ControlBinding binding = control;
        return binding == null ? new LivenessMetrics(0, 0, 0, 0, 0,
                java.time.Duration.ZERO, java.time.Duration.ZERO, 0, 0, 0, 0, 0)
                : binding.livenessMetrics();
    }

    /**
     * Registers a liveness listener through the authenticated control stream.
     * Listener callbacks are bounded and isolated from protocol state.
     *
     * @param listener listener to register
     * @throws IllegalStateException if the control stream is not established
     */
    public void addLivenessListener(LivenessListener listener) {
        ControlBinding binding = control;
        if (binding == null) throw new IllegalStateException("control stream is not established");
        binding.addLivenessListener(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a liveness listener.
     *
     * @param listener listener to remove
     * @throws IllegalStateException if the control stream is not established
     */
    public void removeLivenessListener(LivenessListener listener) {
        ControlBinding binding = control;
        if (binding == null) throw new IllegalStateException("control stream is not established");
        binding.removeLivenessListener(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Returns the same exactly-once terminal signal used for cancellation.
     *
     * @return terminal completion stage
     */
    public CompletionStage<Void> cancellation() {
        return terminalCompletion();
    }
}
