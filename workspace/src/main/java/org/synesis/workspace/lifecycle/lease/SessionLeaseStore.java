package org.synesis.workspace.lifecycle.lease;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;

/**
 * Persists and loads provider session lease records outside the control checkout under the
 * external project administration directory {@code admin/session-leases/}.
 *
 * @since 1.0
 */
public final class SessionLeaseStore {

    /**
     * Creates a lease store.
     */
    public SessionLeaseStore() {
    }

    /**
     * Resolves the directory path for storing session leases.
     *
     * @param controlRoot control project root path
     * @return normalized lease directory path
     */
    public static Path resolveLeasesDirectory(Path controlRoot) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
        return workspaceRoot.resolve("admin")
                .resolve("session-leases");
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static Map<String, Object> toSerializableMap(SessionLeaseRecord r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", r.schemaVersion());
        map.put("projectId", r.projectId());
        map.put("provider", r.provider());
        map.put("connectionInstanceId", r.connectionInstanceId());
        map.put("workerNodeId", r.workerNodeId());
        map.put("sessionId", r.sessionId());
        map.put("synesisVersion", r.synesisVersion());
        map.put("createdAtEpochMillis", r.createdAtEpochMillis());
        map.put("lastHeartbeatEpochMillis", r.lastHeartbeatEpochMillis());
        map.put("leaseState",
                r.leaseState()
                        .name());

        Map<String, Object> pi = new LinkedHashMap<>();
        pi.put("pid",
                r.processIdentity()
                        .pid());
        pi.put("executableIdentity",
                r.processIdentity()
                        .executableIdentity());
        pi.put("commandLine",
                r.processIdentity()
                        .commandLine());
        pi.put("processStartTime",
                r.processIdentity()
                        .processStartTime());
        pi.put("connectionNonce",
                r.processIdentity()
                        .connectionNonce());
        map.put("processIdentity", pi);

        return map;
    }

    @SuppressWarnings("unchecked")
    private static SessionLeaseRecord fromSerializableMap(String rawJson) throws IOException {
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(rawJson);
        int schemaVersion = ((Number) map.get("schemaVersion")).intValue();
        String projectId = (String) map.get("projectId");
        String provider = (String) map.get("provider");
        String connId = (String) map.get("connectionInstanceId");
        String workerNodeId = (String) map.get("workerNodeId");
        String sessionId = (String) map.get("sessionId");
        String version = (String) map.get("synesisVersion");
        long createdAt = ((Number) map.get("createdAtEpochMillis")).longValue();
        long lastHeartbeat = ((Number) map.get("lastHeartbeatEpochMillis")).longValue();
        SessionLeaseState state = SessionLeaseState.valueOf((String) map.get("leaseState"));

        Map<String, Object> piMap = (Map<String, Object>) map.get("processIdentity");
        SessionProcessIdentity pi = new SessionProcessIdentity(
                ((Number) piMap.get("pid")).longValue(),
                (String) piMap.get("executableIdentity"),
                (String) piMap.get("commandLine"),
                ((Number) piMap.get("processStartTime")).longValue(),
                (String) piMap.get("connectionNonce")
        );

        return new SessionLeaseRecord(schemaVersion,
                projectId,
                provider,
                connId,
                workerNodeId,
                sessionId,
                pi,
                version,
                createdAt,
                lastHeartbeat,
                state);
    }

    /**
     * Atomically saves or updates a session lease record.
     *
     * @param controlRoot control project root path
     * @param record      lease record to save
     * @throws IOException if saving fails
     */
    public void save(Path controlRoot, SessionLeaseRecord record) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(record, "record");

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path leasesDir = resolveLeasesDirectory(root);
        Files.createDirectories(leasesDir);

        Path targetFile = leasesDir.resolve(record.connectionInstanceId() + ".json");
        Path tmpFile = leasesDir.resolve(record.connectionInstanceId() + ".tmp");

        String json = ProviderJson.write(toSerializableMap(record));
        Files.writeString(tmpFile,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads a session lease record by connection instance ID.
     *
     * @param controlRoot          control project root path
     * @param connectionInstanceId connection instance identifier
     * @return optional containing record if found
     */
    public Optional<SessionLeaseRecord> load(Path controlRoot, String connectionInstanceId) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path leasesDir = resolveLeasesDirectory(root);
        Path targetFile = leasesDir.resolve(connectionInstanceId + ".json");

        if (!Files.exists(targetFile)) {
            return Optional.empty();
        }

        try {
            String rawJson = Files.readString(targetFile, StandardCharsets.UTF_8);
            return Optional.of(fromSerializableMap(rawJson));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Lists all persisted session lease records for the specified project.
     *
     * @param controlRoot control project root path
     * @return list of session lease records
     */
    public List<SessionLeaseRecord> listAll(Path controlRoot) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath()
                .normalize();
        Path leasesDir = resolveLeasesDirectory(root);
        if (!Files.isDirectory(leasesDir)) {
            return List.of();
        }

        List<SessionLeaseRecord> records = new ArrayList<>();
        try (var stream = Files.list(leasesDir)) {
            for (Path file : stream.filter(p -> p.getFileName()
                            .toString()
                            .endsWith(".json"))
                    .toList()) {
                try {
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    records.add(fromSerializableMap(raw));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return Collections.unmodifiableList(records);
    }
}
