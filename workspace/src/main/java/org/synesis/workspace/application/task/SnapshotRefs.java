package org.synesis.workspace.application.task;

/**
 * Owns the Git ref names under which immutable snapshots and their transaction-owned prepared
 * pins are stored. These names are persisted in snapshot provenance and must not change.
 *
 * @since 1.0
 */
public final class SnapshotRefs {

    /** Namespace prefix of final immutable snapshot refs. */
    public static final String SNAPSHOT_PREFIX = "refs/synesis/snapshots/";

    /** Namespace prefix of refs that pin a snapshot commit during a completion transaction. */
    public static final String PREPARED_PREFIX = "refs/synesis/prepared/";

    private SnapshotRefs() {
    }

    /**
     * Returns the final immutable ref of a snapshot.
     *
     * @param snapshotId snapshot identifier
     * @return the snapshot ref
     */
    public static String snapshotRef(String snapshotId) {
        return SNAPSHOT_PREFIX + snapshotId;
    }

    /**
     * Returns whether a ref lies in the snapshot namespace.
     *
     * @param ref ref name
     * @return {@code true} when the ref starts with {@link #SNAPSHOT_PREFIX}
     */
    public static boolean isSnapshotRef(String ref) {
        return ref.startsWith(SNAPSHOT_PREFIX);
    }

    /**
     * Returns the prepared ref of a completion transaction.
     *
     * @param completionId durable completion transaction ID
     * @return the prepared ref
     */
    public static String preparedRef(String completionId) {
        return PREPARED_PREFIX + completionId;
    }
}
