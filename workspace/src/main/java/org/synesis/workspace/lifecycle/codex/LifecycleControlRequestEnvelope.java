package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Signed, immutable, Codex-only lifecycle-control request.
 *
 * <p>The canonical request contains the complete semantic authority context.
 * Transport retries must reuse the same request object and absolute deadline;
 * changing any field changes the digest and is a different logical request.
 * The record is immutable and thread-safe. It contains identifiers and bounded
 * user input, but callers should still avoid logging the complete payload.
 *
 * @since 1.0
 */
@SuppressWarnings("ClassCanBeRecord")
public final class LifecycleControlRequestEnvelope {

    /**
     * Maximum UTF-8 bytes retained for one bounded text field.
     */
    public static final int MAX_TEXT_BYTES = 8_192;
    /**
     * Maximum UTF-8 bytes retained for the entire lifecycle envelope.
     */
    public static final int MAX_ENVELOPE_BYTES = 64 * 1024;
    /**
     * Maximum caller deadline horizon accepted for one lifecycle request.
     */
    public static final long MAX_CALLER_DEADLINE_MILLIS = 30L * 60L * 1_000L;
    private final UUID requestId;
    private final String hostInstanceId;
    private final AuthorityContext authority;
    private final Operation operation;
    private final long expectedLifecycleRevision;
    private final String expectedThreadId;
    private final String expectedTurnId;
    private final boolean continuation;
    private final String input;
    private final long callerDeadlineEpochMillis;
    private final Map<String, String> options;
    /**
     * Creates an immutable lifecycle request.
     *
     * @param requestId                 request/idempotency identity
     * @param hostInstanceId            production owner instance identity
     * @param authority                 exact Synesis authority context
     * @param operation                 lifecycle operation
     * @param expectedLifecycleRevision lifecycle revision observed by caller
     * @param expectedThreadId          exact expected thread, or {@code null} when not applicable
     * @param expectedTurnId            exact expected turn, or {@code null} when not applicable
     * @param continuation              whether the request explicitly requests model continuation
     * @param input                     bounded continuation or steering input
     * @param callerDeadlineEpochMillis original absolute caller deadline
     * @param options                   bounded semantic operation options
     */
    public LifecycleControlRequestEnvelope(UUID requestId, String hostInstanceId, AuthorityContext authority,
            Operation operation, long expectedLifecycleRevision, String expectedThreadId, String expectedTurnId,
            boolean continuation, String input, long callerDeadlineEpochMillis, Map<String, String> options) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.hostInstanceId = requireText(hostInstanceId, "hostInstanceId");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.operation = Objects.requireNonNull(operation, "operation");
        if (expectedLifecycleRevision < 0) {
            throw new IllegalArgumentException("expected lifecycle revision must not be negative");
        }
        this.expectedLifecycleRevision = expectedLifecycleRevision;
        this.expectedThreadId = optionalText(expectedThreadId, "expectedThreadId");
        this.expectedTurnId = optionalText(expectedTurnId, "expectedTurnId");
        this.continuation = continuation;
        this.input = optionalText(input, "input");
        if (callerDeadlineEpochMillis <= 0) {
            throw new IllegalArgumentException("caller deadline must be positive");
        }
        if (callerDeadlineEpochMillis - System.currentTimeMillis() > MAX_CALLER_DEADLINE_MILLIS) {
            throw new IllegalArgumentException("caller deadline exceeds bound");
        }
        this.callerDeadlineEpochMillis = callerDeadlineEpochMillis;
        Map<String, String> copy = new java.util.TreeMap<>(Objects.requireNonNull(options, "options"));
        if (copy.size() > 32) {
            throw new IllegalArgumentException("options exceed bound");
        }
        copy.forEach((key, value) -> {
            requireText(key, "option key");
            requireText(value, "option value");
        });
        this.options = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(copy));
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("lifecycle_control_invalid_utf8", failure);
        }
    }

    private static LifecycleControlRequestEnvelope fromMap(Map<String, Object> value) {
        Map<String, Object> authority = castMap(value.get("authority"));
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, Object> rawOptions = castMap(value.get("options"));
        rawOptions.forEach((key, item) -> options.put(key, String.valueOf(item)));
        return new LifecycleControlRequestEnvelope(
                UUID.fromString(string(value, "requestId")),
                string(value, "hostInstanceId"),
                new AuthorityContext(string(authority, "projectId"), string(authority, "controlProjectRoot"),
                        string(authority, "provider"), string(authority, "connectionInstanceId"),
                        string(authority, "bindingSessionId"), string(authority, "bindingFingerprint"),
                        integer(authority),
                        string(authority, "participant"), string(authority, "workIntentId"),
                        number(authority, "laneEpoch"), string(authority, "canonicalWorktree"),
                        string(authority, "realWorktree"), string(authority, "gitCommonDirectory"),
                        string(authority, "branch"), string(authority, "baseCommit"),
                        string(authority, "supervisorId"), string(authority, "workerId")),
                Operation.valueOf(string(value, "operation")), number(value, "expectedLifecycleRevision"),
                nullableString(value.get("expectedThreadId")), nullableString(value.get("expectedTurnId")),
                Boolean.TRUE.equals(value.get("continuation")), nullableString(value.get("input")),
                number(value, "callerDeadlineEpochMillis"), options);
    }

    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("object expected");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String string(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return text;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number number)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return number.longValue();
    }

    private static int integer(Map<String, Object> value) {
        return Math.toIntExact(number(value, "bindingVersion"));
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " exceeds bound");
        }
        return value;
    }

    private static String optionalText(String value, String label) {
        return value == null ? null : requireText(value, label);
    }

    /**
     * Returns the request/idempotency identity.
     *
     * @return request identity
     */
    public UUID requestId() {
        return requestId;
    }

    /**
     * Returns the production owner instance identity.
     *
     * @return owner identity
     */
    public String hostInstanceId() {
        return hostInstanceId;
    }

    /**
     * Returns the frozen authority context.
     *
     * @return authority context
     */
    public AuthorityContext authority() {
        return authority;
    }

    /**
     * Returns the requested lifecycle operation.
     *
     * @return operation
     */
    public Operation operation() {
        return operation;
    }

    /**
     * Returns the request classification.
     *
     * @return classification
     */
    public Classification classification() {
        return switch (operation) {
            case STATUS, WAIT -> Classification.READ_ONLY;
            default -> Classification.STATE_CHANGING;
        };
    }

    /**
     * Returns the expected lifecycle revision.
     *
     * @return lifecycle revision
     */
    public long expectedLifecycleRevision() {
        return expectedLifecycleRevision;
    }

    /**
     * Returns the expected exact thread identity, or {@code null}.
     *
     * @return thread identity
     */
    public String expectedThreadId() {
        return expectedThreadId;
    }

    /**
     * Returns the expected exact turn identity, or {@code null}.
     *
     * @return turn identity
     */
    public String expectedTurnId() {
        return expectedTurnId;
    }

    /**
     * Returns the explicit continuation flag.
     *
     * @return continuation flag
     */
    public boolean continuation() {
        return continuation;
    }

    /**
     * Returns bounded input, or {@code null}.
     *
     * @return input
     */
    public String input() {
        return input;
    }

    /**
     * Returns the original absolute caller deadline in epoch milliseconds.
     *
     * @return deadline
     */
    public long callerDeadlineEpochMillis() {
        return callerDeadlineEpochMillis;
    }

    /**
     * Returns immutable semantic options.
     *
     * @return semantic options
     */
    public Map<String, String> options() {
        return options;
    }

    /**
     * Returns the canonical semantic bytes used for digest and signature.
     *
     * @return deterministic UTF-8 JSON bytes
     */
    public byte[] canonicalBytes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestId", requestId.toString());
        value.put("hostInstanceId", hostInstanceId);
        value.put("operation", operation.name());
        value.put("expectedLifecycleRevision", expectedLifecycleRevision);
        value.put("expectedThreadId", expectedThreadId);
        value.put("expectedTurnId", expectedTurnId);
        value.put("continuation", continuation);
        value.put("input", input);
        value.put("callerDeadlineEpochMillis", callerDeadlineEpochMillis);
        value.put("options", new LinkedHashMap<>(options));
        Map<String, Object> authorityValue = new LinkedHashMap<>();
        authorityValue.put("projectId", authority.projectId());
        authorityValue.put("controlProjectRoot", authority.controlProjectRoot());
        authorityValue.put("provider", authority.provider());
        authorityValue.put("connectionInstanceId", authority.connectionInstanceId());
        authorityValue.put("bindingSessionId", authority.bindingSessionId());
        authorityValue.put("bindingFingerprint", authority.bindingFingerprint());
        authorityValue.put("bindingVersion", authority.bindingVersion());
        authorityValue.put("participant", authority.participant());
        authorityValue.put("workIntentId", authority.workIntentId());
        authorityValue.put("laneEpoch", authority.laneEpoch());
        authorityValue.put("canonicalWorktree", authority.canonicalWorktree());
        authorityValue.put("realWorktree", authority.realWorktree());
        authorityValue.put("gitCommonDirectory", authority.gitCommonDirectory());
        authorityValue.put("branch", authority.branch());
        authorityValue.put("baseCommit", authority.baseCommit());
        authorityValue.put("supervisorId", authority.supervisorId());
        authorityValue.put("workerId", authority.workerId());
        value.put("authority", authorityValue);
        return ProviderJson.write(value)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Computes the canonical SHA-256 request digest.
     *
     * @return lowercase hexadecimal digest
     */
    public String digest() {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonicalBytes()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required", impossible);
        }
    }

    /**
     * Signs this request with the caller's local node identity.
     *
     * @param signer local identity
     * @return signed envelope
     * @throws GeneralSecurityException when Ed25519 signing fails
     */
    public SignedEnvelope sign(NodeIdentity signer) throws GeneralSecurityException {
        Objects.requireNonNull(signer, "signer");
        return new SignedEnvelope(this, signer.nodeId(), signer.publicKeyEncoded(), signer.sign(canonicalBytes()));
    }

    /**
     * Supported Codex lifecycle operations.
     */
    public enum Operation {
        /**
         * Establishes an App Server attachment and explicitly starts its first turn.
         */
        START,
        /**
         * Sends continuation input to the exact stored thread.
         */
        NOTIFY,
        /**
         * Steers the exact active turn.
         */
        STEER,
        /**
         * Waits for an event-driven exact-thread/turn transition.
         */
        WAIT,
        /**
         * Interrupts the exact active turn.
         */
        INTERRUPT,
        /**
         * Terminates the verified App Server attachment tree.
         */
        HARD_STOP,
        /**
         * Resumes the exact stored thread, optionally with explicit continuation input.
         */
        RESUME,
        /**
         * Reads authoritative lifecycle state without mutation.
         */
        STATUS
    }

    /**
     * Request classification used by the durable idempotency ledger.
     */
    public enum Classification {
        /**
         * The request only observes or waits for existing state.
         */
        READ_ONLY,
        /**
         * The request may mutate lifecycle or provider state.
         */
        STATE_CHANGING
    }

    /**
     * Exact Synesis authority identities frozen into a START request.
     *
     * @param projectId            project UUID text
     * @param controlProjectRoot   canonical Synesis control-project root
     * @param provider             installed provider ID; SYN-038 requires {@code codex}
     * @param connectionInstanceId exact provider connection identity
     * @param bindingSessionId     exact provider binding session
     * @param bindingFingerprint   provider binding fingerprint
     * @param bindingVersion       provider binding revision
     * @param participant          participant handle
     * @param workIntentId         WorkIntent and lane UUID text
     * @param laneEpoch            WorkIntent version used as the lane epoch
     * @param canonicalWorktree    canonical assigned worktree path
     * @param realWorktree         real-path assigned worktree identity
     * @param gitCommonDirectory   canonical Git common directory
     * @param branch               assigned worktree branch
     * @param baseCommit           binding base commit
     * @param supervisorId         exact supervisor identity
     * @param workerId             exact worker identity
     */
    public record AuthorityContext(
            String projectId,
            String controlProjectRoot,
            String provider,
            String connectionInstanceId,
            String bindingSessionId,
            String bindingFingerprint,
            int bindingVersion,
            String participant,
            String workIntentId,
            long laneEpoch,
            String canonicalWorktree,
            String realWorktree,
            String gitCommonDirectory,
            String branch,
            String baseCommit,
            String supervisorId,
            String workerId
    ) {

        /**
         * Validates the exact authority shape and freezes text values.
         */
        public AuthorityContext {
            requireText(projectId, "projectId");
            requireText(controlProjectRoot, "controlProjectRoot");
            requireText(provider, "provider");
            if (!"codex".equals(provider)) {
                throw new IllegalArgumentException("Codex lifecycle authority requires provider codex");
            }
            requireText(connectionInstanceId, "connectionInstanceId");
            requireText(bindingSessionId, "bindingSessionId");
            requireText(bindingFingerprint, "bindingFingerprint");
            requireText(participant, "participant");
            requireText(workIntentId, "workIntentId");
            requireText(canonicalWorktree, "canonicalWorktree");
            requireText(realWorktree, "realWorktree");
            requireText(gitCommonDirectory, "gitCommonDirectory");
            requireText(branch, "branch");
            requireText(baseCommit, "baseCommit");
            requireText(supervisorId, "supervisorId");
            requireText(workerId, "workerId");
            if (bindingVersion < 1 || laneEpoch < 1) {
                throw new IllegalArgumentException("authority revision must be positive");
            }
        }

        /**
         * Compatibility constructor for direct service fixtures that do not
         * need a control-root override.
         *
         * @param projectId            project UUID text
         * @param provider             installed provider ID
         * @param connectionInstanceId connection identity
         * @param bindingSessionId     binding session
         * @param bindingFingerprint   binding fingerprint
         * @param bindingVersion       binding revision
         * @param participant          participant identity
         * @param workIntentId         WorkIntent/lane ID
         * @param laneEpoch            lane epoch
         * @param canonicalWorktree    canonical worktree
         * @param realWorktree         real worktree
         * @param gitCommonDirectory   Git common directory
         * @param branch               branch identity
         * @param baseCommit           base commit
         * @param supervisorId         supervisor identity
         * @param workerId             worker identity
         */
        public AuthorityContext(String projectId, String provider, String connectionInstanceId,
                String bindingSessionId, String bindingFingerprint, int bindingVersion, String participant,
                String workIntentId, long laneEpoch, String canonicalWorktree, String realWorktree,
                String gitCommonDirectory, String branch, String baseCommit, String supervisorId, String workerId) {
            this(projectId, ".", provider, connectionInstanceId, bindingSessionId, bindingFingerprint,
                    bindingVersion, participant, workIntentId, laneEpoch, canonicalWorktree, realWorktree,
                    gitCommonDirectory, branch, baseCommit, supervisorId, workerId);
        }
    }

    /**
     * Signed transport representation of one immutable request.
     *
     * @param request         immutable semantic request
     * @param signerNodeId    signer node identity
     * @param signerPublicKey encoded signer public key
     * @param signature       Ed25519 signature over canonical request bytes
     */
    public record SignedEnvelope(LifecycleControlRequestEnvelope request, String signerNodeId,
                                 byte[] signerPublicKey, byte[] signature) {

        /**
         * Validates and clones cryptographic material.
         */
        public SignedEnvelope {
            Objects.requireNonNull(request, "request");
            if (signerNodeId == null || signerNodeId.isBlank() || signerPublicKey == null
                    || signerPublicKey.length == 0 || signerPublicKey.length > 16_384
                    || signature == null || signature.length == 0 || signature.length > 16_384) {
                throw new IllegalArgumentException("invalid signed envelope");
            }
            signerPublicKey = signerPublicKey.clone();
            signature = signature.clone();
        }

        /**
         * Decodes a bounded JSON envelope.
         *
         * @param bytes encoded envelope
         * @return decoded signed envelope
         * @throws IOException when input is malformed or exceeds bounds
         */
        public static SignedEnvelope decode(byte[] bytes) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length == 0 || bytes.length > MAX_ENVELOPE_BYTES) {
                throw new IOException("lifecycle envelope exceeds bound");
            }
            try {
                Object parsed = ProviderJson.parse(strictUtf8(bytes));
                if (!(parsed instanceof Map<?, ?> raw)) {
                    throw new IOException("lifecycle envelope must be an object");
                }
                Map<String, Object> value = castMap(raw);
                Map<String, Object> requestValue = castMap(value.get("request"));
                LifecycleControlRequestEnvelope request = fromMap(requestValue);
                String node = string(value, "signerNodeId");
                byte[] key = Base64.getDecoder()
                        .decode(string(value, "signerPublicKey"));
                byte[] signature = Base64.getDecoder()
                        .decode(string(value, "signature"));
                return new SignedEnvelope(request, node, key, signature);
            } catch (IllegalArgumentException | ClassCastException failure) {
                throw new IOException("malformed lifecycle envelope", failure);
            }
        }

        /**
         * Returns a defensive copy of the public key.
         *
         * @return public key bytes
         */
        @Override
        @SuppressWarnings("unused")
        public byte[] signerPublicKey() {
            return signerPublicKey.clone();
        }

        /**
         * Returns a defensive copy of the signature.
         *
         * @return signature bytes
         */
        @Override
        @SuppressWarnings("unused")
        public byte[] signature() {
            return signature.clone();
        }

        /**
         * Verifies node ID, key, signature, and request bounds.
         *
         * @return {@code true} only when the envelope is authentic
         * @throws GeneralSecurityException when key verification fails
         */
        public boolean verify() throws GeneralSecurityException {
            if (!NodeIdentity.deriveNodeId(signerPublicKey)
                    .equals(signerNodeId)) {
                return false;
            }
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(signerPublicKey));
            java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(request.canonicalBytes());
            return verifier.verify(signature);
        }

        /**
         * Encodes the signed envelope as bounded JSON.
         *
         * @return encoded transport bytes
         */
        public byte[] encoded() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("request", ProviderJson.parse(new String(request.canonicalBytes(), StandardCharsets.UTF_8)));
            value.put("signerNodeId", signerNodeId);
            value.put("signerPublicKey",
                    Base64.getEncoder()
                            .encodeToString(signerPublicKey));
            value.put("signature",
                    Base64.getEncoder()
                            .encodeToString(signature));
            byte[] encoded = ProviderJson.write(value)
                    .getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_ENVELOPE_BYTES) {
                throw new IllegalStateException("lifecycle envelope exceeds bound");
            }
            return encoded;
        }
    }
}
