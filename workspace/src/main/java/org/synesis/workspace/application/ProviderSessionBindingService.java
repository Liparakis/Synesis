package org.synesis.workspace.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.provider.ProviderJson;

/**
 * Creates and resumes project-scoped provider session bindings.
 *
 * <p>The binding is local, durable, and deliberately contains no private key,
 * provider credential, or conversation content. Provider-instance evidence is
 * hashed before persistence. An absent provider session identifier uses one
 * project/provider fallback nonce and is reported as a degraded fallback so it
 * is not mistaken for independent chat identity.
 *
 * @since 1.0
 */
public final class ProviderSessionBindingService {
    private static final int SCHEMA_VERSION = 1;
    private static final String SESSIONS_DIRECTORY = "sessions";
    private static final String PROVIDERS_DIRECTORY = "providers";

    /** Creates a session binding service. */
    public ProviderSessionBindingService() {
    }

    /**
     * Ensures one idempotent binding for a provider instance.
     *
     * @param location initialized project location
     * @param provider stable provider identifier
     * @param instanceEvidence provider session/conversation key, or {@code null}
     * @return durable binding result
     * @throws BindingException when project identity or local state is invalid
     */
    public synchronized BindingResult ensure(ProjectApplicationService.ProjectLocation location,
            String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link"))
                    .loadOrCreate().identity();
            String evidence = instanceEvidence == null || instanceEvidence.isBlank()
                    ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String fingerprint = fingerprint(evidence);
            Path local = location.synesisDirectory().resolve("local");
            Path sessions = local.resolve(SESSIONS_DIRECTORY);
            Path bindingPath = sessions.resolve(provider + "-" + fingerprint + ".json");
            if (Files.exists(bindingPath)) {
                Binding binding = read(bindingPath);
                if (!binding.projectId().equals(location.projectId().toString()) || !binding.nodeId().equals(identity.nodeId())
                        || !binding.provider().equals(provider) || !binding.providerInstanceFingerprint().equals(fingerprint)) {
                    throw new BindingException("SESSION_IDENTITY_MISMATCH", "Stored provider binding does not match this project: "
                            + binding.projectId() + "/" + binding.nodeId() + "/" + binding.provider() + "/"
                            + binding.providerInstanceFingerprint() + " expected " + location.projectId() + "/"
                            + identity.nodeId() + "/" + provider + "/" + fingerprint);
                }
                if (!binding.status().equals("REVOKED") && !binding.status().equals("COMPLETED")
                        && !binding.status().equals("ABANDONED")) {
                    Binding refreshed = withWorktree(location, binding).touch();
                    write(bindingPath, refreshed);
                    return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                }
            }
            String sessionId = "session-" + UUID.randomUUID();
            String supervisorId = "supervisor-" + UUID.randomUUID();
            String workerId = "worker-" + UUID.randomUUID();
            long now = System.currentTimeMillis();
            Binding binding = new Binding(SCHEMA_VERSION, sessionId, location.projectId().toString(), identity.nodeId(),
                    provider, fingerprint, supervisorId, workerId, null, null, null, currentCommit(location.root()),
                    "BOUND", now, now, 0L, "WORKSPACE_UNVERIFIED", 1, null);
            binding = withWorktree(location, binding);
            write(bindingPath, binding);
            return new BindingResult(binding, instanceEvidence == null || instanceEvidence.isBlank());
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_FAILED", "Could not establish provider session", failure);
        }
    }

    /**
     * Loads all valid bindings for one provider.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @return bindings, possibly empty
     * @throws BindingException when a binding file is malformed
     */
    public synchronized java.util.List<Binding> list(ProjectApplicationService.ProjectLocation location,
            String provider) throws BindingException {
        try {
            Path directory = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) return java.util.List.of();
            try (var paths = Files.list(directory)) {
                java.util.List<Binding> result = new java.util.ArrayList<>();
                for (Path path : paths.filter(item -> item.getFileName().toString().startsWith(provider + "-")).toList()) {
                    result.add(read(path));
                }
                return result.stream().sorted(java.util.Comparator.comparing(Binding::createdAtEpochMillis)).toList();
            }
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not read provider sessions", failure);
        }
    }

    /**
     * Revokes all sessions for a provider while preserving their audit records.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @throws BindingException when a binding cannot be read or persisted
     */
    public synchronized void revoke(ProjectApplicationService.ProjectLocation location, String provider)
            throws BindingException {
        try {
            Path directory = location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) return;
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(item -> item.getFileName().toString().startsWith(provider + "-")).toList()) {
                    Binding binding = read(path);
                    write(path, binding.revoke());
                }
            }
        } catch (Exception failure) {
            throw new BindingException("SESSION_REVOKE_FAILED", "Could not revoke provider sessions", failure);
        }
    }

    /**
     * Determines whether a binding was created from the provider fallback nonce.
     *
     * @param location initialized project location
     * @param provider provider identifier
     * @param binding binding to classify
     * @return {@code true} when the binding fingerprint matches the fallback nonce
     * @throws BindingException when the fallback record cannot be read
     */
    public synchronized boolean isFallbackEvidence(ProjectApplicationService.ProjectLocation location,
            String provider, Binding binding) throws BindingException {
        try {
            Path key = location.synesisDirectory().resolve("local").resolve(PROVIDERS_DIRECTORY)
                    .resolve(provider + ".bootstrap-key");
            return Files.isRegularFile(key) && binding.providerInstanceFingerprint()
                    .equals(fingerprint(Files.readString(key, StandardCharsets.UTF_8).trim()));
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not classify provider evidence", failure);
        }
    }

    /** Verifies that a provider event is executing in its assigned worktree.
     * @param location project location
     * @param binding session binding
     * @param cwd provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyWorkspace(ProjectApplicationService.ProjectLocation location,
            Binding binding, Path cwd) {
        if (binding == null || binding.worktreePath() == null || binding.worktreePath().isBlank()) {
            return new WorkspaceCheck(false, "WORKSPACE_TRANSITION_REQUIRED");
        }
        try {
            Path root = location.root().toAbsolutePath().normalize();
            Path assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            Path actual = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            if (assigned.equals(root)) return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            if (!Files.isDirectory(assigned) || !actual.equals(assigned)) {
                return new WorkspaceCheck(false, "WORKSPACE_TRANSITION_REQUIRED");
            }
            String top = git(assigned, "rev-parse", "--show-toplevel");
            if (!Files.isSameFile(assigned, Path.of(top).toAbsolutePath().normalize())) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            return new WorkspaceCheck(true, "WORKSPACE_VERIFIED");
        } catch (Exception failure) {
            return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
        }
    }

    private static Binding withWorktree(ProjectApplicationService.ProjectLocation location, Binding binding) {
        if (binding.worktreePath() != null && Files.isDirectory(Path.of(binding.worktreePath()))) return binding;
        if ("UNKNOWN".equals(binding.baseCommit())) {
            return binding.providerTrustState().equals("WORKSPACE_UNVERIFIED") ? binding
                    : new Binding(binding.schemaVersion(), binding.sessionId(), binding.projectId(), binding.nodeId(),
                    binding.provider(), binding.providerInstanceFingerprint(), binding.supervisorId(), binding.workerId(),
                    null, null, null, binding.baseCommit(), binding.status(), binding.createdAtEpochMillis(),
                    binding.lastSeenEpochMillis(), binding.lastVerifiedProjectSequence(), "WORKSPACE_UNVERIFIED",
                    binding.bindingVersion(), binding.completedAt());
        }
        String worktreeId = "worktree-" + binding.sessionId().substring("session-".length());
        Path path = location.synesisDirectory().resolve("local/worktrees").resolve(worktreeId).toAbsolutePath().normalize();
        if (path.equals(location.root()) || !path.startsWith(location.synesisDirectory().toAbsolutePath().normalize())) return binding;
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                runGit(location.root(), "worktree", "add", "-b", "synesis/" + binding.sessionId(),
                        path.toString(), binding.baseCommit());
            }
            return new Binding(binding.schemaVersion(), binding.sessionId(), binding.projectId(), binding.nodeId(),
                    binding.provider(), binding.providerInstanceFingerprint(), binding.supervisorId(), binding.workerId(),
                    worktreeId, path.toString(), "synesis/" + binding.sessionId(), binding.baseCommit(), binding.status(),
                    binding.createdAtEpochMillis(), binding.lastSeenEpochMillis(), binding.lastVerifiedProjectSequence(),
                    "WORKSPACE_UNVERIFIED", binding.bindingVersion(), binding.completedAt());
        } catch (Exception ignored) {
            return binding;
        }
    }

    private static String currentCommit(Path root) { try { return git(root, "rev-parse", "HEAD"); } catch (Exception ignored) { return "UNKNOWN"; } }
    private static String git(Path root, String... arguments) throws Exception { return runGit(root, arguments).trim(); }
    private static String runGit(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3]; command[0] = "git"; command[1] = "-C"; command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("git failed: " + output);
        return output;
    }

    private static String fallbackEvidence(ProjectApplicationService.ProjectLocation location, String provider) throws IOException {
        Path path = location.synesisDirectory().resolve("local").resolve(PROVIDERS_DIRECTORY)
                .resolve(provider + ".bootstrap-key");
        if (Files.exists(path)) return Files.readString(path, StandardCharsets.UTF_8).trim();
        Files.createDirectories(path.getParent());
        String value = UUID.randomUUID().toString();
        writeText(path, value + System.lineSeparator());
        return value;
    }

    private static String fingerprint(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Binding read(Path path) throws IOException {
        try {
            Map<String, Object> value = (Map<String, Object>) ProviderJson.parse(Files.readString(path));
            int schema = number(value, "schemaVersion").intValue();
            if (schema != SCHEMA_VERSION) throw new IOException("unsupported session schema");
            return new Binding(schema, text(value, "sessionId"), text(value, "projectId"), text(value, "nodeId"),
                    text(value, "provider"), text(value, "providerInstanceFingerprint"), text(value, "supervisorId"),
                    text(value, "workerId"), nullable(value, "worktreeId"), nullable(value, "worktreePath"),
                    nullable(value, "branch"), text(value, "baseCommit"), text(value, "status"), number(value, "createdAtEpochMillis").longValue(),
                    number(value, "lastSeenEpochMillis").longValue(), number(value, "lastVerifiedProjectSequence").longValue(),
                    text(value, "providerTrustState"), number(value, "bindingVersion").intValue(), nullable(value, "completedAt"));
        } catch (RuntimeException failure) {
            throw new IOException("malformed provider session binding", failure);
        }
    }

    private static void write(Path path, Binding binding) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", binding.schemaVersion());
        value.put("sessionId", binding.sessionId());
        value.put("projectId", binding.projectId());
        value.put("nodeId", binding.nodeId());
        value.put("provider", binding.provider());
        value.put("providerInstanceFingerprint", binding.providerInstanceFingerprint());
        value.put("supervisorId", binding.supervisorId());
        value.put("workerId", binding.workerId());
        value.put("worktreeId", binding.worktreeId());
        value.put("worktreePath", binding.worktreePath());
        value.put("branch", binding.branch());
        value.put("baseCommit", binding.baseCommit());
        value.put("status", binding.status());
        value.put("createdAtEpochMillis", binding.createdAtEpochMillis());
        value.put("lastSeenEpochMillis", binding.lastSeenEpochMillis());
        value.put("lastVerifiedProjectSequence", binding.lastVerifiedProjectSequence());
        value.put("providerTrustState", binding.providerTrustState());
        value.put("bindingVersion", binding.bindingVersion());
        value.put("completedAt", binding.completedAt());
        writeText(path, ProviderJson.write(value) + System.lineSeparator());
    }

    private static void writeText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
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

    private static String text(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (!(result instanceof String text) || text.isBlank()) throw new IOException("missing " + key);
        return text;
    }

    private static String nullable(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (result == null) return null;
        if (!(result instanceof String text) || text.isBlank()) throw new IOException("invalid " + key);
        return text;
    }

    private static Number number(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (!(result instanceof Number number)) throw new IOException("missing " + key);
        return number;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256 || value.contains("/")) {
            throw new IllegalArgumentException(label + " invalid");
        }
    }

    /** Immutable durable provider session binding.
     * @param schemaVersion record schema version
     * @param sessionId session identity
     * @param projectId project identity
     * @param nodeId project node identity
     * @param provider provider identifier
     * @param providerInstanceFingerprint hashed provider-instance evidence
     * @param supervisorId supervisor actor identity
     * @param workerId worker actor identity
     * @param worktreeId assigned worktree identity, when allocated
     * @param worktreePath assigned worktree path, when allocated
     * @param branch assigned branch, when allocated
     * @param baseCommit Git base commit
     * @param status lifecycle status
     * @param createdAtEpochMillis creation timestamp
     * @param lastSeenEpochMillis last-seen timestamp
     * @param lastVerifiedProjectSequence last verified project sequence
     * @param providerTrustState provider trust state
     * @param bindingVersion binding version
     * @param completedAt terminal timestamp, when complete
     */
    public record Binding(int schemaVersion, String sessionId, String projectId, String nodeId, String provider,
            String providerInstanceFingerprint, String supervisorId, String workerId, String worktreeId,
            String worktreePath, String branch, String baseCommit, String status, long createdAtEpochMillis,
            long lastSeenEpochMillis, long lastVerifiedProjectSequence, String providerTrustState,
            int bindingVersion, String completedAt) {
        /** Returns a refreshed binding with an updated last-seen timestamp.
         * @return refreshed binding
         */
        public Binding touch() {
            return new Binding(schemaVersion, sessionId, projectId, nodeId, provider, providerInstanceFingerprint,
                    supervisorId, workerId, worktreeId, worktreePath, branch, baseCommit, status, createdAtEpochMillis,
                    System.currentTimeMillis(), lastVerifiedProjectSequence, providerTrustState, bindingVersion,
                    completedAt);
        }

        /** Returns a terminal revoked binding.
         * @return revoked binding
         */
        public Binding revoke() {
            return new Binding(schemaVersion, sessionId, projectId, nodeId, provider, providerInstanceFingerprint,
                    supervisorId, workerId, worktreeId, worktreePath, branch, baseCommit, "REVOKED", createdAtEpochMillis,
                    System.currentTimeMillis(), lastVerifiedProjectSequence, "REVOKED", bindingVersion,
                    Long.toString(System.currentTimeMillis()));
        }
    }

    /** Result of automatic provider bootstrap.
     * @param binding durable binding
     * @param fallbackEvidence whether the provider key was unavailable
     */
    public record BindingResult(Binding binding, boolean fallbackEvidence) {
        /** Validates the binding result. */
        public BindingResult {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /** Workspace verification result.
     * @param verified whether cwd is the assigned worktree
     * @param code stable result code
     */
    public record WorkspaceCheck(boolean verified, String code) { }

    /** Stable automatic binding failure. */
    public static final class BindingException extends Exception {
        private static final long serialVersionUID = 1L;
        /** Stable machine-readable failure code. */
        private final String code;

        /** Creates a binding failure.
         * @param code stable code
         * @param message safe message
         */
        public BindingException(String code, String message) { super(message); this.code = code; }
        /** Creates a binding failure with an internal cause.
         * @param code stable code
         * @param message safe message
         * @param cause cause
         */
        public BindingException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        /** Returns the stable failure code.
         * @return code
         */
        public String code() { return code; }
    }
}
