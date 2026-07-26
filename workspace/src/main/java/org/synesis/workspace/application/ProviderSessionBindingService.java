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

    private static final int SCHEMA_VERSION = 2;
    private static final String SESSIONS_DIRECTORY = "sessions";
    private static final String PROVIDERS_DIRECTORY = "providers";

    /**
     * Creates a session binding service.
     */
    public ProviderSessionBindingService() {
    }

    private static Binding withWorktree(ProjectApplicationService.ProjectLocation location, Binding binding) {
        String baseCommit = validCommit(binding.baseCommit()) ? binding.baseCommit() : "GIT_HEAD_UNAVAILABLE";
        String worktreeId = "worktree-" + binding.sessionId()
                .substring("session-".length());
        Path path = workspaceRoot().resolve(binding.projectId())
                .resolve("worktrees")
                .resolve(binding.sessionId())
                .toAbsolutePath()
                .normalize();
        String branch = "synesis/" + binding.provider() + "/" + binding.sessionId();
        Path control = location.root()
                .toAbsolutePath()
                .normalize();
        if (path.startsWith(control) || control.startsWith(path)) {
            return copy(binding, null, control.toString(), null, baseCommit, null, "FAILED", "UNVERIFIED",
                    "CONTROL_CHECKOUT_MUTATION_DENIED", "WORKSPACE_UNVERIFIED");
        }
        if ("GIT_HEAD_UNAVAILABLE".equals(baseCommit)) {
            return copy(binding,
                    null,
                    location.root()
                            .toString(),
                    null,
                    baseCommit,
                    null,
                    "UNASSIGNED",
                    "UNVERIFIED",
                    "GIT_HEAD_UNAVAILABLE",
                    "WORKSPACE_UNVERIFIED");
        }
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                try {
                    runGit(location.root(), "worktree", "add", "-b", branch, path.toString(), baseCommit);
                } catch (Exception branchExists) {
                    runGit(location.root(), "worktree", "add", path.toString(), branch);
                }
            }
            writeWorkspaceMarker(path, location, binding, branch);
            Binding allocated = copy(binding,
                    worktreeId,
                    location.root()
                            .toString(),
                    path.toString(),
                    baseCommit,
                    branch,
                    "ALLOCATED",
                    "UNVERIFIED",
                    "WORKTREE_ALLOCATED",
                    "WORKSPACE_UNVERIFIED");
            return verifyBinding(location.root(), allocated);
        } catch (Exception failure) {
            return copy(binding,
                    null,
                    location.root()
                            .toString(),
                    null,
                    baseCommit,
                    null,
                    "FAILED",
                    "UNVERIFIED",
                    "WORKTREE_ALLOCATION_FAILED",
                    "WORKSPACE_UNVERIFIED");
        }
    }

    private static Binding verifyBinding(Path controlRoot, Binding binding) {
        try {
            Path assigned = Path.of(binding.worktreePath());
            String common = canonicalGitCommon(controlRoot);
            boolean verified = registeredWorktree(controlRoot, assigned, binding)
                    && sameGitCommonDirectory(controlRoot, assigned, binding)
                    && binding.branch()
                    .equals(git(assigned, "symbolic-ref", "--short", "HEAD"))
                    && isBaseAncestor(assigned, binding.baseCommit());
            return new Binding(SCHEMA_VERSION,
                    binding.sessionId(),
                    binding.projectId(),
                    binding.nodeId(),
                    binding.provider(),
                    binding.providerInstanceFingerprint(),
                    binding.supervisorId(),
                    binding.workerId(),
                    binding.worktreeId(),
                    binding.worktreePath(),
                    controlRoot.toString(),
                    binding.branch(),
                    binding.baseCommit(),
                    common,
                    "ALLOCATED",
                    verified ? "VERIFIED" : "UNVERIFIED",
                    verified ? "WORKTREE_VERIFIED" : "WORKTREE_NOT_REGISTERED",
                    binding.status(),
                    binding.createdAtEpochMillis(),
                    System.currentTimeMillis(),
                    binding.lastVerifiedProjectSequence(),
                    verified ? "WORKSPACE_UNVERIFIED" : "WORKSPACE_UNVERIFIED",
                    binding.bindingVersion(),
                    binding.completedAt());
        } catch (Exception failure) {
            return copy(binding,
                    binding.worktreeId(),
                    controlRoot.toString(),
                    binding.worktreePath(),
                    binding.baseCommit(),
                    binding.branch(),
                    "ALLOCATED",
                    "UNVERIFIED",
                    "WORKTREE_NOT_REGISTERED",
                    "WORKSPACE_UNVERIFIED");
        }
    }

    private static boolean registeredWorktree(Path root, Path assigned, Binding binding) throws Exception {
        String list = runGit(root, "worktree", "list", "--porcelain");
        String canonical = assigned.toRealPath()
                .toString();
        boolean pathFound = false;
        for (String line : list.lines()
                .toList()) {
            if (line.startsWith("worktree ")) {
                Path wt = Path.of(line.substring("worktree ".length()))
                        .toAbsolutePath()
                        .normalize();
                String wtCanonical = Files.exists(wt) ? wt.toRealPath()
                                                        .toString() : wt.toString();
                pathFound = wtCanonical.equalsIgnoreCase(canonical);
            }
            if (pathFound && line.equals("branch refs/heads/" + binding.branch())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameGitCommonDirectory(Path root, Path assigned, Binding binding) throws Exception {
        Path expected = Path.of(canonicalGitCommon(root));
        Path actual = Path.of(canonicalGitCommon(assigned));
        return expected.equals(actual) && (binding.gitCommonDir() == null || binding.gitCommonDir()
                .equals(actual.toString()));
    }

    private static String canonicalGitCommon(Path root) throws Exception {
        Path common = Path.of(git(root, "rev-parse", "--git-common-dir"));
        return (common.isAbsolute() ? common : root.resolve(common)).toRealPath()
                .toString();
    }

    private static boolean isBaseAncestor(Path assigned, String baseCommit) throws Exception {
        if (!validCommit(baseCommit)) {
            return false;
        }
        Process process = new ProcessBuilder("git",
                "-C",
                assigned.toString(),
                "merge-base",
                "--is-ancestor",
                baseCommit,
                "HEAD")
                .redirectErrorStream(true)
                .start();
        process.getInputStream()
                .readAllBytes();
        return process.waitFor() == 0;
    }

    private static boolean validCommit(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}") && !value.matches("0{40}");
    }

    private static String currentCommit(Path root) {
        try {
            String value = git(root, "rev-parse", "--verify", "HEAD");
            return validCommit(value) ? value : "GIT_HEAD_UNAVAILABLE";
        } catch (Exception ignored) {
            return "GIT_HEAD_UNAVAILABLE";
        }
    }

    private static Path workspaceRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis")
                    .toString();
        }
        return Path.of(base, "Synesis", "workspaces")
                .toAbsolutePath()
                .normalize();
    }

    private static void writeWorkspaceMarker(Path worktree, ProjectApplicationService.ProjectLocation location,
            Binding binding, String branch) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("projectId", binding.projectId());
        marker.put("sessionId", binding.sessionId());
        marker.put("provider", binding.provider());
        marker.put("controlCheckoutPath",
                location.root()
                        .toString());
        marker.put("branch", branch);
        writeText(worktree.resolve(".synesis/local/workspace-binding.json"),
                ProviderJson.write(marker) + System.lineSeparator());
    }

    private static Binding copy(Binding b, String worktreeId, String control, String path, String base, String branch,
            String creation, String verification, String lastSeen, String trust) {
        return new Binding(SCHEMA_VERSION,
                b.sessionId(),
                b.projectId(),
                b.nodeId(),
                b.provider(),
                b.providerInstanceFingerprint(),
                b.supervisorId(),
                b.workerId(),
                worktreeId,
                path,
                control,
                branch,
                base,
                b.gitCommonDir(),
                creation,
                verification,
                lastSeen,
                b.status(),
                b.createdAtEpochMillis(),
                System.currentTimeMillis(),
                b.lastVerifiedProjectSequence(),
                trust,
                b.bindingVersion(),
                b.completedAt());
    }

    private static String git(Path root, String... arguments) throws Exception {
        return runGit(root, arguments).trim();
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

    private static String fallbackEvidence(ProjectApplicationService.ProjectLocation location, String provider)
            throws IOException {
        Path path = location.synesisDirectory()
                .resolve("local")
                .resolve(PROVIDERS_DIRECTORY)
                .resolve(provider + ".bootstrap-key");
        if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8)
                    .trim();
        }
        Files.createDirectories(path.getParent());
        String value = UUID.randomUUID()
                .toString();
        writeText(path, value + System.lineSeparator());
        return value;
    }

    private static String fingerprint(String value) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Binding read(Path path) throws IOException {
        try {
            Map<String, Object> value = (Map<String, Object>) ProviderJson.parse(Files.readString(path));
            int schema = number(value, "schemaVersion").intValue();
            if (schema != 1 && schema != SCHEMA_VERSION) {
                throw new IOException("unsupported session schema");
            }
            return new Binding(schema,
                    text(value, "sessionId"),
                    text(value, "projectId"),
                    text(value, "nodeId"),
                    text(value, "provider"),
                    text(value, "providerInstanceFingerprint"),
                    text(value, "supervisorId"),
                    text(value, "workerId"),
                    nullable(value, "worktreeId"),
                    nullable(value, "worktreePath"),
                    nullable(value, "controlCheckoutPath"),
                    nullable(value, "branch"),
                    text(value, "baseCommit"),
                    nullable(value, "gitCommonDir"),
                    nullable(value, "creationState"),
                    nullable(value, "verificationState"),
                    nullable(value, "lastSeenState"),
                    text(value, "status"),
                    number(value, "createdAtEpochMillis").longValue(),
                    number(value, "lastSeenEpochMillis").longValue(),
                    number(value, "lastVerifiedProjectSequence").longValue(),
                    nullable(value, "providerTrustState") == null ? "WORKSPACE_UNVERIFIED"
                            : nullable(value, "providerTrustState"),
                    number(value, "bindingVersion").intValue(),
                    nullable(value, "completedAt"));
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
        value.put("controlCheckoutPath", binding.controlCheckoutPath());
        value.put("branch", binding.branch());
        value.put("baseCommit", binding.baseCommit());
        value.put("gitCommonDir", binding.gitCommonDir());
        value.put("creationState", binding.creationState());
        value.put("verificationState", binding.verificationState());
        value.put("lastSeenState", binding.lastSeenState());
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
        if (!(result instanceof String text) || text.isBlank()) {
            throw new IOException("missing " + key);
        }
        return text;
    }

    private static String nullable(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (result == null) {
            return null;
        }
        if (!(result instanceof String text) || text.isBlank()) {
            throw new IOException("invalid " + key);
        }
        return text;
    }

    private static Number number(Map<String, Object> value, String key) throws IOException {
        Object result = value.get(key);
        if (!(result instanceof Number number)) {
            throw new IOException("missing " + key);
        }
        return number;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256 || value.contains("/")) {
            throw new IllegalArgumentException(label + " invalid");
        }
    }

    /**
     * Ensures one idempotent binding for a provider instance.
     *
     * @param location         initialized project location
     * @param provider         stable provider identifier
     * @param instanceEvidence provider session/conversation key, or {@code null}
     * @return durable binding result
     * @throws BindingException when project identity or local state is invalid
     */
    public synchronized BindingResult ensure(ProjectApplicationService.ProjectLocation location,
            String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            NodeIdentity identity = new IdentityBootstrap(location.profile()
                    .resolve("link"))
                    .loadOrCreate()
                    .identity();
            String evidence = instanceEvidence == null || instanceEvidence.isBlank()
                    ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String fingerprint = fingerprint(evidence);
            Path local = location.synesisDirectory()
                    .resolve("local");
            Path sessions = local.resolve(SESSIONS_DIRECTORY);
            Path bindingPath = sessions.resolve(provider + "-" + fingerprint + ".json");
            if (Files.exists(bindingPath)) {
                Binding binding = read(bindingPath);
                if (!binding.projectId()
                        .equals(location.projectId()
                                .toString()) || !binding.nodeId()
                        .equals(identity.nodeId())
                        || !binding.provider()
                        .equals(provider) || !binding.providerInstanceFingerprint()
                        .equals(fingerprint)) {
                    throw new BindingException("SESSION_IDENTITY_MISMATCH",
                            "Stored provider binding does not match this project: "
                                    + binding.projectId() + "/" + binding.nodeId() + "/" + binding.provider() + "/"
                                    + binding.providerInstanceFingerprint() + " expected " + location.projectId() + "/"
                                    + identity.nodeId() + "/" + provider + "/" + fingerprint);
                }
                if (!binding.status()
                        .equals("REVOKED") && !binding.status()
                        .equals("COMPLETED")
                        && !binding.status()
                        .equals("ABANDONED")) {
                    if (binding.worktreePath() != null && !workspaceGenerationFresh(location, binding)) {
                        if (!isWorktreeClean(binding)) {
                            throw new BindingException("WORKSPACE_STALE_DIRTY",
                                    "Stored provider workspace contains uncommitted work");
                        }
                        binding = newBinding(location, identity, provider, fingerprint);
                    }
                    Binding refreshed = withWorktree(location, binding).touch();
                    write(bindingPath, refreshed);
                    return new BindingResult(refreshed, instanceEvidence == null || instanceEvidence.isBlank());
                }
            }
            Binding binding = newBinding(location, identity, provider, fingerprint);
            binding = withWorktree(location, binding);
            write(bindingPath, binding);
            return new BindingResult(binding, instanceEvidence == null || instanceEvidence.isBlank());
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_FAILED", "Could not establish provider session", failure);
        }
    }

    private static Binding newBinding(ProjectApplicationService.ProjectLocation location,
            NodeIdentity identity, String provider, String fingerprint) {
        long now = System.currentTimeMillis();
        return new Binding(SCHEMA_VERSION,
                "session-" + UUID.randomUUID(),
                location.projectId().toString(),
                identity.nodeId(),
                provider,
                fingerprint,
                "supervisor-" + UUID.randomUUID(),
                "worker-" + UUID.randomUUID(),
                null,
                null,
                location.root().toString(),
                null,
                currentCommit(location.root()),
                null,
                "UNASSIGNED",
                "UNVERIFIED",
                "BOOTSTRAPPED",
                "BOUND",
                now,
                now,
                0L,
                "WORKSPACE_UNVERIFIED",
                1,
                null);
    }

    private static boolean workspaceGenerationFresh(ProjectApplicationService.ProjectLocation location,
            Binding binding) {
        if (binding == null || binding.worktreePath() == null || !validCommit(binding.baseCommit())) {
            return false;
        }
        try {
            Path worker = Path.of(binding.worktreePath());
            return Files.isDirectory(worker)
                    && binding.baseCommit().equals(currentCommit(worker))
                    && binding.baseCommit().equals(currentCommit(location.root()));
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean isWorktreeClean(Binding binding) {
        if (binding == null || binding.worktreePath() == null) {
            return false;
        }
        try {
            String status = git(Path.of(binding.worktreePath()), "status", "--porcelain", "--untracked-files=all");
            return status.lines().map(String::trim)
                    .filter(line -> !line.isBlank())
                    .noneMatch(line -> !(line.endsWith(".synesis") || line.contains(".synesis/")));
        } catch (Exception failure) {
            return false;
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
            Path directory = location.synesisDirectory()
                    .resolve("local")
                    .resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                return java.util.List.of();
            }
            try (var paths = Files.list(directory)) {
                java.util.List<Binding> result = new java.util.ArrayList<>();
                for (Path path : paths.filter(item -> item.getFileName()
                                .toString()
                                .startsWith(provider + "-"))
                        .toList()) {
                    result.add(read(path));
                }
                return result.stream()
                        .sorted(java.util.Comparator.comparing(Binding::createdAtEpochMillis))
                        .toList();
            }
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not read provider sessions", failure);
        }
    }

    /**
     * Resolves the durable binding for one exact provider connection instance.
     *
     * <p>Operations must never fall back to the newest provider binding: a single
     * project can legitimately contain several concurrent workers. The same evidence
     * normalization and fingerprinting used by {@link #ensure(ProjectApplicationService.ProjectLocation,
     * String, String)} is therefore applied here.
     *
     * @param location initialized project location
     * @param provider stable provider identifier
     * @param instanceEvidence provider connection identity
     * @return the matching binding, or empty when this connection has not been ensured
     * @throws BindingException when the binding is malformed or identity-mismatched
     */
    public synchronized java.util.Optional<Binding> find(ProjectApplicationService.ProjectLocation location,
            String provider, String instanceEvidence) throws BindingException {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        try {
            String evidence = instanceEvidence == null || instanceEvidence.isBlank()
                    ? fallbackEvidence(location, provider) : instanceEvidence.trim();
            String instanceFingerprint = fingerprint(evidence);
            Path bindingPath = location.synesisDirectory()
                    .resolve("local")
                    .resolve(SESSIONS_DIRECTORY)
                    .resolve(provider + "-" + instanceFingerprint + ".json");
            if (!Files.isRegularFile(bindingPath)) {
                return java.util.Optional.empty();
            }
            Binding binding = read(bindingPath);
            NodeIdentity identity = new IdentityBootstrap(location.profile()
                    .resolve("link"))
                    .loadOrCreate()
                    .identity();
            if (!location.projectId().toString().equals(binding.projectId())
                    || !identity.nodeId().equals(binding.nodeId())
                    || !provider.equals(binding.provider())
                    || !instanceFingerprint.equals(binding.providerInstanceFingerprint())) {
                throw new BindingException("SESSION_IDENTITY_MISMATCH",
                        "Stored provider binding does not match this project or connection");
            }
            return java.util.Optional.of(binding);
        } catch (BindingException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED",
                    "Could not resolve provider connection binding", failure);
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
            Path directory = location.synesisDirectory()
                    .resolve("local")
                    .resolve(SESSIONS_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(item -> item.getFileName()
                                .toString()
                                .startsWith(provider + "-"))
                        .toList()) {
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
     * @param binding  binding to classify
     * @return {@code true} when the binding fingerprint matches the fallback nonce
     * @throws BindingException when the fallback record cannot be read
     */
    public synchronized boolean isFallbackEvidence(ProjectApplicationService.ProjectLocation location,
            String provider, Binding binding) throws BindingException {
        try {
            Path key = location.synesisDirectory()
                    .resolve("local")
                    .resolve(PROVIDERS_DIRECTORY)
                    .resolve(provider + ".bootstrap-key");
            return Files.isRegularFile(key) && binding.providerInstanceFingerprint()
                    .equals(fingerprint(Files.readString(key, StandardCharsets.UTF_8)
                            .trim()));
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not classify provider evidence", failure);
        }
    }

    /**
     * Verifies that a provider event is executing in its assigned worktree.
     *
     * @param location project location
     * @param binding  session binding
     * @param cwd      provider event working directory
     * @return workspace verification result
     */
    public synchronized WorkspaceCheck verifyWorkspace(ProjectApplicationService.ProjectLocation location,
            Binding binding, Path cwd) {
        if (binding == null || binding.worktreePath() == null || binding.worktreePath()
                .isBlank()) {
            return new WorkspaceCheck(false, binding == null ? "WORKSPACE_UNVERIFIED" : binding.lastSeenState());
        }
        try {
            Path root = location.root()
                    .toAbsolutePath()
                    .normalize();
            Path assigned = Path.of(binding.worktreePath())
                    .toAbsolutePath()
                    .normalize();
            Path actual = Objects.requireNonNull(cwd, "cwd")
                    .toAbsolutePath()
                    .normalize();
            if (actual.equals(root)) {
                return new WorkspaceCheck(false, "CONTROL_CHECKOUT_MUTATION_DENIED");
            }
            if (assigned.equals(root) || assigned.startsWith(root)
                    || !root.equals(Path.of(binding.controlCheckoutPath()))) {
                return new WorkspaceCheck(false, "CONTROL_CHECKOUT_MUTATION_DENIED");
            }
            if (!Files.isDirectory(assigned) || !actual.equals(assigned)) {
                return new WorkspaceCheck(false, "WORKSPACE_TRANSITION_REQUIRED");
            }
            if (!registeredWorktree(root, assigned, binding)) {
                return new WorkspaceCheck(false, "WORKTREE_NOT_REGISTERED");
            }
            if (!sameGitCommonDirectory(root, assigned, binding)) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            if (!binding.branch()
                    .equals(git(assigned, "symbolic-ref", "--short", "HEAD"))) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            if (!isBaseAncestor(assigned, binding.baseCommit())) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            if (!binding.baseCommit().equals(git(assigned, "rev-parse", "HEAD"))) {
                return new WorkspaceCheck(false, "WORKSPACE_GENERATION_MISMATCH");
            }
            if (!binding.baseCommit().equals(git(root, "rev-parse", "HEAD"))) {
                return new WorkspaceCheck(false, "CONTROL_BASE_ADVANCED");
            }
            return new WorkspaceCheck(true, "WORKSPACE_VERIFIED");
        } catch (Exception failure) {
            return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
        }
    }

    /**
     * Performs strict, idempotent workspace verification inspecting the real filesystem
     * and Git repository state. On success, transitions providerTrustState to VERIFIED and records evidence.
     *
     * @param location  initialized project location
     * @param provider  provider identifier
     * @param sessionId session identifier, or {@code null} to resolve latest bound session
     * @param cwd       provider's declared active working directory
     * @return structured workspace verification result
     */
    public synchronized WorkspaceVerificationResult verifyWorkspaceTrust(ProjectApplicationService.ProjectLocation location,
            String provider, String sessionId, Path cwd) {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        if (cwd == null) {
            return new WorkspaceVerificationResult(false, "WORKSPACE_UNVERIFIED", null, null);
        }
        try {
            var bindings = list(location, provider);
            if (bindings.isEmpty()) {
                return new WorkspaceVerificationResult(false, "SESSION_UNBOUND", null, null);
            }
            Binding binding = null;
            if (sessionId != null && !sessionId.isBlank()) {
                binding = bindings.stream()
                        .filter(b -> sessionId.equals(b.sessionId()))
                        .findFirst()
                        .orElse(null);
            } else {
                binding = bindings.getLast();
            }
            if (binding == null || !"BOUND".equals(binding.status())) {
                return new WorkspaceVerificationResult(false, "SESSION_UNBOUND", null, null);
            }

            if (binding.worktreePath() == null || binding.worktreePath()
                    .isBlank()) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_UNASSIGNED", null, binding);
            }

            Path root = location.root()
                    .toAbsolutePath()
                    .normalize();
            Path assigned = Path.of(binding.worktreePath())
                    .toAbsolutePath()
                    .normalize();
            Path actual = cwd.toAbsolutePath()
                    .normalize();

            // 1. Check assigned worktree differs from control checkout
            if (assigned.equals(root) || assigned.startsWith(root) || actual.equals(root)) {
                return new WorkspaceVerificationResult(false, "CONTROL_CHECKOUT_MUTATION_DENIED", null, binding);
            }

            // 2. Check worktree path exists on filesystem
            if (!Files.isDirectory(assigned)) {
                return new WorkspaceVerificationResult(false, "WORKTREE_DIRECTORY_MISSING", null, binding);
            }

            // 3. Check canonical path equality
            Path canonicalAssigned = assigned.toRealPath();
            Path canonicalActual = actual.toRealPath();
            if (!canonicalAssigned.equals(canonicalActual)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_TRANSITION_REQUIRED", null, binding);
            }

            // 4. No other active session owns it
            for (Binding other : bindings) {
                if (!other.sessionId()
                        .equals(binding.sessionId()) && !"REVOKED".equals(other.status())
                        && !"COMPLETED".equals(other.status()) && other.worktreePath() != null) {
                    if (Path.of(other.worktreePath())
                            .toAbsolutePath()
                            .normalize()
                            .equals(assigned)) {
                        return new WorkspaceVerificationResult(false, "DUPLICATE_ACTIVE_WORKTREE", null, binding);
                    }
                }
            }

            // 5. Git recognizes it as a registered worktree
            if (!registeredWorktree(root, assigned, binding)) {
                return new WorkspaceVerificationResult(false, "WORKTREE_NOT_REGISTERED", null, binding);
            }

            // 6. Git common directory belongs to control repository
            if (!sameGitCommonDirectory(root, assigned, binding)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // 7. Branch matches recorded session branch
            String currentBranch = git(assigned, "symbolic-ref", "--short", "HEAD");
            if (!binding.branch()
                    .equals(currentBranch)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // 8. HEAD is valid commit derived from base commit
            String headCommit = git(assigned, "rev-parse", "HEAD");
            if (!validCommit(headCommit) || !isBaseAncestor(assigned, binding.baseCommit())) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // All checks passed! Transition to VERIFIED and persist evidence
            long now = System.currentTimeMillis();
            String evidenceRaw = location.projectId()
                    .toString() + "|" + canonicalAssigned.toString() + "|"
                    + binding.branch() + "|" + headCommit + "|" + now;
            String evidenceDigest = fingerprint(evidenceRaw);

            Binding verifiedBinding = new Binding(
                    binding.schemaVersion(),
                    binding.sessionId(),
                    binding.projectId(),
                    binding.nodeId(),
                    binding.provider(),
                    binding.providerInstanceFingerprint(),
                    binding.supervisorId(),
                    binding.workerId(),
                    binding.worktreeId(),
                    binding.worktreePath(),
                    binding.controlCheckoutPath(),
                    binding.branch(),
                    binding.baseCommit(),
                    binding.gitCommonDir(),
                    binding.creationState(),
                    "VERIFIED",
                    "WORKSPACE_VERIFIED",
                    binding.status(),
                    binding.createdAtEpochMillis(),
                    now,
                    binding.lastVerifiedProjectSequence(),
                    "VERIFIED",
                    binding.bindingVersion(),
                    binding.completedAt()
            );

            // Persist updated binding
            Path sessionDir = location.synesisDirectory()
                    .resolve("local")
                    .resolve(SESSIONS_DIRECTORY);
            Path bindingPath = sessionDir.resolve(provider + "-" + binding.providerInstanceFingerprint() + ".json");
            write(bindingPath, verifiedBinding);

            // Persist verification record
            Map<String, Object> verRecord = new LinkedHashMap<>();
            verRecord.put("schemaVersion", 1);
            verRecord.put("verificationVersion", 1);
            verRecord.put("sessionId", binding.sessionId());
            verRecord.put("projectId",
                    location.projectId()
                            .toString());
            verRecord.put("provider", provider);
            verRecord.put("verificationTimestamp", now);
            verRecord.put("repositoryIdentity",
                    location.projectId()
                            .toString());
            verRecord.put("canonicalWorktreePath", canonicalAssigned.toString());
            verRecord.put("branch", binding.branch());
            verRecord.put("headCommit", headCommit);
            verRecord.put("evidenceDigest", evidenceDigest);
            verRecord.put("workspaceTrust", "VERIFIED");

            Path evidenceFile = sessionDir.resolve("verification-" + binding.sessionId() + ".json");
            writeText(evidenceFile, ProviderJson.write(verRecord) + System.lineSeparator());

            return new WorkspaceVerificationResult(true, "WORKSPACE_VERIFIED", evidenceDigest, verifiedBinding);
        } catch (Exception failure) {
            return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, null);
        }
    }

    /**
     * Result of workspace trust verification.
     *
     * @param verified       whether workspace verification succeeded
     * @param code           stable status code
     * @param evidenceDigest evidence SHA-256 digest on success, or {@code null}
     * @param binding        updated session binding, or {@code null}
     */
    public record WorkspaceVerificationResult(
            boolean verified,
            String code,
            String evidenceDigest,
            Binding binding
    ) {

        /**
         * Validates result shape.
         */
        public WorkspaceVerificationResult {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * Immutable durable provider session binding.
     *
     * @param schemaVersion               record schema version
     * @param sessionId                   session identity
     * @param projectId                   project identity
     * @param nodeId                      project node identity
     * @param provider                    provider identifier
     * @param providerInstanceFingerprint hashed provider-instance evidence
     * @param supervisorId                supervisor actor identity
     * @param workerId                    worker actor identity
     * @param worktreeId                  assigned worktree identity, when allocated
     * @param worktreePath                assigned worktree path, when allocated
     * @param controlCheckoutPath         canonical control checkout path
     * @param branch                      assigned branch, when allocated
     * @param baseCommit                  Git base commit
     * @param gitCommonDir                Git common directory identity
     * @param creationState               worktree creation state
     * @param verificationState           worktree verification state
     * @param lastSeenState               last-seen binding state
     * @param status                      lifecycle status
     * @param createdAtEpochMillis        creation timestamp
     * @param lastSeenEpochMillis         last-seen timestamp
     * @param lastVerifiedProjectSequence last verified project sequence
     * @param providerTrustState          provider trust state
     * @param bindingVersion              binding version
     * @param completedAt                 terminal timestamp, when complete
     */
    public record Binding(int schemaVersion, String sessionId, String projectId, String nodeId, String provider,
                          String providerInstanceFingerprint, String supervisorId, String workerId, String worktreeId,
                          String worktreePath, String controlCheckoutPath, String branch, String baseCommit,
                          String gitCommonDir,
                          String creationState, String verificationState, String lastSeenState, String status,
                          long createdAtEpochMillis, long lastSeenEpochMillis, long lastVerifiedProjectSequence,
                          String providerTrustState,
                          int bindingVersion, String completedAt) {

        /**
         * Returns a refreshed binding with an updated last-seen timestamp.
         *
         * @return refreshed binding
         */
        public Binding touch() {
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    lastSeenState,
                    status,
                    createdAtEpochMillis,
                    System.currentTimeMillis(),
                    lastVerifiedProjectSequence,
                    providerTrustState,
                    bindingVersion,
                    completedAt);
        }

        /**
         * Returns a terminal revoked binding.
         *
         * @return revoked binding
         */
        public Binding revoke() {
            return new Binding(schemaVersion,
                    sessionId,
                    projectId,
                    nodeId,
                    provider,
                    providerInstanceFingerprint,
                    supervisorId,
                    workerId,
                    worktreeId,
                    worktreePath,
                    controlCheckoutPath,
                    branch,
                    baseCommit,
                    gitCommonDir,
                    creationState,
                    verificationState,
                    "REVOKED",
                    "REVOKED",
                    createdAtEpochMillis,
                    System.currentTimeMillis(),
                    lastVerifiedProjectSequence,
                    "REVOKED",
                    bindingVersion,
                    Long.toString(System.currentTimeMillis()));
        }
    }

    /**
     * Result of automatic provider bootstrap.
     *
     * @param binding          durable binding
     * @param fallbackEvidence whether the provider key was unavailable
     */
    public record BindingResult(Binding binding, boolean fallbackEvidence) {

        /**
         * Validates the binding result.
         */
        public BindingResult {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * Workspace verification result.
     *
     * @param verified whether cwd is the assigned worktree
     * @param code     stable result code
     */
    public record WorkspaceCheck(boolean verified, String code) {

    }

    /**
     * Stable automatic binding failure.
     */
    public static final class BindingException extends Exception {

        private static final long serialVersionUID = 1L;
        /**
         * Stable machine-readable failure code.
         */
        private final String code;

        /**
         * Creates a binding failure.
         *
         * @param code    stable code
         * @param message safe message
         */
        public BindingException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Creates a binding failure with an internal cause.
         *
         * @param code    stable code
         * @param message safe message
         * @param cause   cause
         */
        public BindingException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /**
         * Returns the stable failure code.
         *
         * @return code
         */
        public String code() {
            return code;
        }
    }
}
