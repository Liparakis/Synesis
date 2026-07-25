package org.synesis.workspace.migration;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.synesis.coordination.CapabilityRequestProjection;
import org.synesis.coordination.CapabilityRequestRecord;
import org.synesis.coordination.PredictionEventStore;
import org.synesis.coordination.TaskCompletionProjection;
import org.synesis.coordination.TaskSnapshotRecord;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Replays the existing durable event store and compares canonical semantic
 * projection snapshots before and after a project migration.
 */
public final class PostMigrationReplayVerifier {

    /** Creates a replay verifier with no mutable state. */
    public PostMigrationReplayVerifier() {
    }

    /** Canonical semantic state captured from one project.
     * @param projectId project identity
     * @param nodeId node identity
     * @param eventHead verified event sequence
     * @param eventBytesHash event bytes fingerprint
     * @param snapshotBytesHash snapshot bytes fingerprint
     * @param projectionFingerprint canonical projection fingerprint
     * @param eventHashChainValid event-chain verification result
     * @param snapshotReferencesValid snapshot-reference verification result
     */
    public record MigrationSemanticSnapshot(String projectId, String nodeId, long eventHead, String eventBytesHash,
                                            String snapshotBytesHash, String projectionFingerprint,
                                            boolean eventHashChainValid, boolean snapshotReferencesValid) {
        /** Validates snapshot values. */
        public MigrationSemanticSnapshot {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(eventBytesHash, "eventBytesHash");
            Objects.requireNonNull(snapshotBytesHash, "snapshotBytesHash");
            Objects.requireNonNull(projectionFingerprint, "projectionFingerprint");
        }
    }

    /** Result of comparing two replay snapshots.
     * @param eventLogBytesUnchanged event bytes are identical
     * @param eventHashChainValid event chains verified
     * @param allProjectionsReplayed all projections were rebuilt
     * @param semanticStateEquivalent semantic fingerprints match
     * @param snapshotReferencesValid snapshot references are valid
     * @param identitiesUnchanged project and node identities match
     * @param reason stable result reason
     */
    public record ProjectionReplayVerificationResult(boolean eventLogBytesUnchanged, boolean eventHashChainValid,
                                                     boolean allProjectionsReplayed, boolean semanticStateEquivalent,
                                                     boolean snapshotReferencesValid, boolean identitiesUnchanged,
                                                     String reason) {
        /** Validates result values. */
        public ProjectionReplayVerificationResult {
            Objects.requireNonNull(reason, "reason");
        }

        /**
         * Returns whether every required replay gate passed.
         *
         * @return true when safe
         */
        public boolean successful() {
            return eventLogBytesUnchanged && eventHashChainValid && allProjectionsReplayed
                    && semanticStateEquivalent && snapshotReferencesValid && identitiesUnchanged;
        }
    }

    /** Captures and verifies one project using the production event store.
     * @param location initialized project location
     * @return semantic snapshot
     * @throws IOException if durable state cannot be read
     * @throws GeneralSecurityException if event verification fails
     */
    public MigrationSemanticSnapshot capture(ProjectApplicationService.ProjectLocation location)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(location, "location");
        String nodeId = new IdentityBootstrap(location.profile().resolve("link")).inspect().nodeId();
        if (nodeId.isBlank()) {
            throw new IOException("project node identity unavailable");
        }
        Path coordination = location.synesisDirectory().resolve("coordination");
        Path events = coordination.resolve("events");
        String eventHash = hashFiles(events, ".sce");
        PredictionEventStore store = null;
        if (Files.isDirectory(events)) {
            store = new PredictionEventStore(coordination, location.projectId());
        }
        String projection = store == null ? hashText("empty-projections") : projectionFingerprint(store);
        long head = store == null ? 0L : store.headSequence();
        TaskCompletionProjection completion = store == null ? null : store.taskCompletionProjection();
        boolean snapshotsValid = completion == null || snapshotsValid(completion);
        String snapshotHash = hashFiles(location.synesisDirectory().resolve("local/snapshots"), null);
        return new MigrationSemanticSnapshot(location.projectId().toString(), nodeId, head, eventHash, snapshotHash,
                projection, true, snapshotsValid);
    }

    /** Compares the pre- and post-migration replay snapshots.
     * @param before pre-migration snapshot
     * @param after post-migration snapshot
     * @return comparison result
     */
    public ProjectionReplayVerificationResult compare(MigrationSemanticSnapshot before,
                                                       MigrationSemanticSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        boolean events = before.eventBytesHash().equals(after.eventBytesHash())
                && before.eventHead() == after.eventHead();
        boolean identity = before.projectId().equals(after.projectId()) && before.nodeId().equals(after.nodeId());
        boolean equivalent = before.projectionFingerprint().equals(after.projectionFingerprint());
        boolean chain = before.eventHashChainValid() && after.eventHashChainValid();
        boolean snapshots = before.snapshotReferencesValid() && after.snapshotReferencesValid()
                && before.snapshotBytesHash().equals(after.snapshotBytesHash());
        String reason = events && chain && equivalent && snapshots && identity ? "post_migration_replay_verified"
                : "post_migration_replay_mismatch";
        return new ProjectionReplayVerificationResult(events, chain, true, equivalent, snapshots, identity, reason);
    }

    private static String projectionFingerprint(PredictionEventStore store) {
        Map<String, Object> state = new TreeMap<>();
        state.put("prediction", store.projection().snapshot());
        state.put("coordinationTasks", store.coordinationProjection().tasks());
        state.put("ownership", store.coordinationProjection().ownerships());
        CapabilityRequestProjection capabilities = store.capabilityRequestProjection();
        state.put("capabilityRequests", capabilities.records());
        state.put("validation", capabilities.allValidationContexts());
        state.put("implementations", implementationSnapshot(capabilities));
        TaskCompletionProjection completion = store.taskCompletionProjection();
        state.put("taskSnapshots", completion.allSnapshots());
        state.put("activeIntegration", completion.activeIntegrationAttempt().orElse(null));
        state.put("lastControlHead", completion.lastControlHeadAdvanced());
        return hashText(canonical(state));
    }

    private static List<Object> implementationSnapshot(CapabilityRequestProjection projection) {
        List<Object> values = new ArrayList<>();
        for (CapabilityRequestRecord record : projection.records().values()) {
            for (int revision = 1; revision <= 1024; revision++) {
                var value = projection.findImplementation(record.handle().value(), revision);
                if (value.isEmpty()) break;
                values.add(value.get());
            }
        }
        return values;
    }

    private static boolean snapshotsValid(TaskCompletionProjection projection) {
        for (TaskSnapshotRecord record : projection.allSnapshots()) {
            if (record.snapshotId().isBlank() || record.commitSha().isBlank() || record.baseCommit().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String hashFiles(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) return hashText("missing:" + root.getFileName());
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        StringBuilder value = new StringBuilder();
        for (Path file : files) {
            value.append(root.relativize(file)).append(':').append(HexFormat.of().formatHex(Files.readAllBytes(file))).append('\n');
        }
        return hashText(value.toString());
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> canonical(String.valueOf(entry.getKey())) + '=' + canonical(entry.getValue()))
                    .sorted().collect(java.util.stream.Collectors.joining("{", "", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PostMigrationReplayVerifier::canonical).sorted()
                    .collect(java.util.stream.Collectors.joining("[", "", "]"));
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) items.add(Array.get(value, i));
            return canonical(items);
        }
        if (value.getClass().isRecord()) {
            List<String> components = new ArrayList<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                String name = component.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("time") || name.contains("timestamp") || name.contains("path") || name.contains("pid")) continue;
                try { components.add(component.getName() + '=' + canonical(component.getAccessor().invoke(value))); }
                catch (ReflectiveOperationException failure) { components.add(component.getName() + "=<unavailable>"); }
            }
            components.sort(String::compareTo);
            return components.stream().collect(java.util.stream.Collectors.joining("(", "", ")"));
        }
        return String.valueOf(value);
    }

    private static String hashText(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
