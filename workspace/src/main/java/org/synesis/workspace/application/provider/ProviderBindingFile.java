package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.BindingException;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.synesis.workspace.application.provider.ProviderSessionBindingService.SCHEMA_VERSION;

/**
 * Maps a {@link Binding} to and from its durable JSON file and derives the file identity from
 * provider-instance evidence. The map key insertion order in {@link #write(Path, Binding)} is the
 * on-disk format, and the atomic replace in {@link #writeText(Path, String)} is what lets a
 * concurrent reader see either the old or the new binding but never a partial one. It is separate
 * from {@link ProviderSessionBindingService} so the lifecycle code decides when a binding changes
 * while this class alone decides how it is spelled on disk.
 */
final class ProviderBindingFile {

    private static final String PROVIDERS_DIRECTORY = "providers";
    private static final String SESSIONS_DIRECTORY = "sessions";

    private ProviderBindingFile() {
    }

    static String fallbackEvidence(ProjectLocation location, String provider) throws IOException {
        Path path = location.synesisDirectory().resolve("local").resolve(PROVIDERS_DIRECTORY).resolve(provider + ".bootstrap-key");
        if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        }
        Files.createDirectories(path.getParent());
        String value = UUID.randomUUID().toString();
        writeText(path, value + System.lineSeparator());
        return value;
    }

    static Path sessionsDirectory(ProjectLocation location) {
        return location.synesisDirectory().resolve("local").resolve(SESSIONS_DIRECTORY);
    }

    static Path bindingPath(ProjectLocation location, String provider, String fingerprint) {
        return sessionsDirectory(location).resolve(provider + "-" + fingerprint + ".json");
    }

    /**
     * Whether a file of the sessions folder is one provider's binding. The temporary file of an atomic replace is named
     * after its binding with a random suffix, and is partial or gone while a concurrent write is in flight, so a listing
     * that read it failed with "Could not read provider sessions" (s5-run27).
     */
    static boolean isBindingOf(Path file, String provider) {
        String name = file.getFileName().toString();
        return name.startsWith(provider + "-") && name.endsWith(".json");
    }

    static String fingerprint(String value) {
        return Sha256.hex(value);
    }

    @SuppressWarnings("unchecked")
    static Binding read(Path path) throws IOException {
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
                    nullable(value, "providerTrustState") == null ? "WORKSPACE_UNVERIFIED" : nullable(value, "providerTrustState"),
                    number(value, "bindingVersion").intValue(),
                    nullable(value, "completedAt"),
                    nullable(value, "lastSeenDetail"));
        } catch (RuntimeException failure) {
            throw new IOException("malformed provider session binding", failure);
        }
    }

    static void write(Path path, Binding binding) throws IOException {
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
        // Written only when set, so a binding without a failure is spelled as before and older readers ignore the key.
        if (binding.lastSeenDetail() != null) {
            value.put("lastSeenDetail", binding.lastSeenDetail());
        }
        writeText(path, ProviderJson.write(value) + System.lineSeparator());
    }

    static void writeText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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

    static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256 || value.contains("/")) {
            throw new IllegalArgumentException(label + " invalid");
        }
    }

    static boolean isFallbackEvidence(ProjectLocation location, String provider, Binding binding) throws BindingException {
        try {
            Path key = location.synesisDirectory().resolve("local").resolve(PROVIDERS_DIRECTORY).resolve(provider + ".bootstrap-key");
            return Files.isRegularFile(key) && binding.providerInstanceFingerprint().equals(fingerprint(Files.readString(key, StandardCharsets.UTF_8).trim()));
        } catch (Exception failure) {
            throw new BindingException("SESSION_BINDING_READ_FAILED", "Could not classify provider evidence", failure);
        }
    }
}
