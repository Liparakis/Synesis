package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService.BaselineFailure;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService.Journal;
import org.synesis.workspace.lifecycle.ManagedBaselineTransactionService.Phase;
import org.synesis.workspace.lifecycle.process.SemanticIndexFingerprint;

/**
 * Owns the persisted form of the baseline transaction journal: one {@code <transactionId>.json}
 * file per transaction under the baseline root. File names, JSON keys, and {@link Phase} names are
 * persisted state and must stay readable by earlier builds.
 */
final class BaselineJournalStore {

    private BaselineJournalStore() {
    }

    static void write(AdministrativeStateLocator.Resolution resolution, Journal journal) throws IOException {
        Files.createDirectories(resolution.baselineRoot());
        Path target = resolution.baselineRoot().resolve(journal.transactionId() + ".json");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, ProviderJson.write(toMap(journal)) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    static Journal read(AdministrativeStateLocator.Resolution resolution, String id) throws IOException, BaselineFailure {
        Path path = resolution.baselineRoot().resolve(id + ".json");
        if (!Files.isRegularFile(path)) {
            throw new BaselineFailure("BASELINE_JOURNAL_NOT_FOUND", id);
        }
        Object parsed = ProviderJson.parse(Files.readString(path));
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new BaselineFailure("BASELINE_JOURNAL_INVALID", id);
        }
        return fromMap((Map<String, Object>) map);
    }

    /**
     * Lists the transaction identities that have a journal file, in directory order.
     */
    static List<String> transactionIds(AdministrativeStateLocator.Resolution resolution) throws IOException {
        if (!Files.isDirectory(resolution.baselineRoot())) {
            return List.of();
        }
        try (var journals = Files.list(resolution.baselineRoot())) {
            return journals.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json")).map(name -> name.replaceFirst("\\.json$", "")).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Journal fromMap(Map<String, Object> map) throws BaselineFailure {
        try {
            String id = String.valueOf(map.get("transactionId"));
            Map<String, String> states = stringMap(map.get("managedPathStates"));
            Map<String, String> expected = stringMap(map.get("expectedManagedDigests"));
            return new Journal(id,
                    String.valueOf(map.get("repositoryIdentity")),
                    String.valueOf(map.get("originalHead")),
                    String.valueOf(map.get("refName")),
                    Phase.valueOf(String.valueOf(map.get("phase"))),
                    SemanticIndexFingerprint.Fingerprint.fromMap((Map<String, Object>) map.get("originalIndex")),
                    String.valueOf(map.get("originalRealIndexDigest")),
                    String.valueOf(map.get("expectedRealIndexDigest")),
                    String.valueOf(map.get("realIndexSyncStatus")),
                    String.valueOf(map.get("realIndexSyncFailure")),
                    String.valueOf(map.get("commit")),
                    states,
                    expected);
        } catch (Exception failure) {
            throw new BaselineFailure("BASELINE_JOURNAL_INVALID", failure.getMessage());
        }
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> toMap(Journal journal) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("transactionId", journal.transactionId());
        values.put("repositoryIdentity", journal.repositoryIdentity());
        values.put("originalHead", journal.originalHead());
        values.put("refName", journal.refName());
        values.put("phase", journal.phase().name());
        values.put("originalRealIndexDigest", journal.originalRealIndexDigest());
        values.put("expectedRealIndexDigest", journal.expectedRealIndexDigest());
        values.put("realIndexSyncStatus", journal.realIndexSyncStatus());
        values.put("realIndexSyncFailure", journal.realIndexSyncFailure());
        values.put("commit", journal.commit());
        values.put("originalIndex", journal.originalIndex().toMap());
        values.put("managedPathStates", journal.managedPathStates());
        values.put("expectedManagedDigests", journal.expectedManagedDigests());
        values.put("createdAt", Instant.now().toString());
        return Map.copyOf(values);
    }
}
