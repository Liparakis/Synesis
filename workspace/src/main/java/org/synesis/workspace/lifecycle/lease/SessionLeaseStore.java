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
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Persists and loads provider session lease records outside the control checkout under the external
 * project administration directory {@code admin/session-leases/}.
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
        return ProjectAdministrativeLayout.sessionLeases(controlRoot);
    }

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
        map.put("leaseState", r.leaseState().name());

        Map<String, Object> pi = new LinkedHashMap<>();
        pi.put("pid", r.processIdentity().pid());
        pi.put("executableIdentity", r.processIdentity().executableIdentity());
        pi.put("commandLine", r.processIdentity().commandLine());
        pi.put("processStartTime", r.processIdentity().processStartTime());
        pi.put("connectionNonce", r.processIdentity().connectionNonce());
        map.put("processIdentity", pi);

        return map;
    }

    @SuppressWarnings("unchecked")
    private static SessionLeaseRecord fromSerializableMap(String rawJson) {
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
        SessionProcessIdentity pi = new SessionProcessIdentity(((Number) piMap.get("pid")).longValue(),
                (String) piMap.get("executableIdentity"),
                (String) piMap.get("commandLine"),
                ((Number) piMap.get("processStartTime")).longValue(),
                (String) piMap.get("connectionNonce"));

        return new SessionLeaseRecord(schemaVersion, projectId, provider, connId, workerNodeId, sessionId, pi, version, createdAt, lastHeartbeat, state);
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

        Path root = controlRoot.toAbsolutePath().normalize();
        Path leasesDir = resolveLeasesDirectory(root);
        Files.createDirectories(leasesDir);

        Path targetFile = leasesDir.resolve(record.connectionInstanceId() + ".json");
        Path tmpFile = leasesDir.resolve(record.connectionInstanceId() + ".tmp");

        String json = ProviderJson.write(toSerializableMap(record));
        Files.writeString(tmpFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

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

        Path root = controlRoot.toAbsolutePath().normalize();
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
        Path root = controlRoot.toAbsolutePath().normalize();
        Path leasesDir = resolveLeasesDirectory(root);
        if (!Files.isDirectory(leasesDir)) {
            return List.of();
        }

        List<SessionLeaseRecord> records = new ArrayList<>();
        try (var stream = Files.list(leasesDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
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

    /**
     * The process reserved to keep a connection's lease before any process opened it, as written by
     * {@link SessionLeaseService#reserveKeeper}. Local, never authority: the process that opens the
     * lease checks it.
     *
     * @param pid              process ID of the keeper
     * @param processStartTime its start time in epoch milliseconds
     */
    record KeeperReservation(long pid, long processStartTime) {
    }

    void saveKeeper(Path controlRoot, String connectionInstanceId, long pid, long processStartTime) throws IOException {
        Path file = keeperFile(controlRoot, connectionInstanceId);
        Files.createDirectories(file.getParent());
        Path tmpFile = file.resolveSibling(connectionInstanceId + ".keeper.tmp");
        Files.writeString(tmpFile, ProviderJson.write(Map.of("pid", pid, "processStartTime", processStartTime)), StandardCharsets.UTF_8);
        Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A reservation that is missing or unreadable is none. */
    Optional<KeeperReservation> loadKeeper(Path controlRoot, String connectionInstanceId) {
        try {
            Map<?, ?> map = (Map<?, ?>) ProviderJson.parse(Files.readString(keeperFile(controlRoot, connectionInstanceId), StandardCharsets.UTF_8));
            return Optional.of(new KeeperReservation(((Number) map.get("pid")).longValue(), ((Number) map.get("processStartTime")).longValue()));
        } catch (Exception missingOrUnreadable) {
            return Optional.empty();
        }
    }

    void deleteKeeper(Path controlRoot, String connectionInstanceId) throws IOException {
        Files.deleteIfExists(keeperFile(controlRoot, connectionInstanceId));
    }

    /**
     * Lists every lease, or nothing when the list may be incomplete: the leases folder is missing, cannot be listed,
     * or holds a lease file that cannot be read. {@link #listAll} skips such files; a caller that concludes something
     * from a session's absence needs to know it did not miss one.
     *
     * @param controlRoot control project root path
     * @return every session lease record, or empty when any may be missing
     */
    public Optional<List<SessionLeaseRecord>> listAllComplete(Path controlRoot) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path leasesDir = resolveLeasesDirectory(controlRoot.toAbsolutePath().normalize());
        if (!Files.isDirectory(leasesDir)) {
            return Optional.empty();
        }
        List<SessionLeaseRecord> records = new ArrayList<>();
        try (var stream = Files.list(leasesDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                records.add(fromSerializableMap(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (Exception unreadable) {
            return Optional.empty();
        }
        return Optional.of(Collections.unmodifiableList(records));
    }

    /** Beside the lease, under a suffix {@link #listAll} does not read. */
    private static Path keeperFile(Path controlRoot, String connectionInstanceId) {
        return resolveLeasesDirectory(controlRoot.toAbsolutePath().normalize()).resolve(connectionInstanceId + ".keeper");
    }
}
