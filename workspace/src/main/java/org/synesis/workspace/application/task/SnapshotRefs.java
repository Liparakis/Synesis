package org.synesis.workspace.application.task;

/**
 * Owns the Git ref names under which immutable snapshots and their transaction-owned prepared
 * pins are stored. These names are persisted in snapshot provenance and must not change.
 *
 * @since 1.0
 */
public final class SnapshotRefs {

    /** Namespace prefix of final immutable snapshot refs. */
    static final String SNAPSHOT_PREFIX = "refs/synesis/snapshots/";

    /** Namespace prefix of refs that pin a snapshot commit during a completion transaction. */
    static final String PREPARED_PREFIX = "refs/synesis/prepared/";

    /** Namespace prefix of the refs that keep trashed work (ADR-0098 step 7). */
    static final String TRASH_PREFIX = "refs/synesis/trash/";

    private static final java.util.regex.Pattern TRASH_REF = java.util.regex.Pattern.compile(
            "refs/synesis/trash/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(0|[1-9][0-9]{0,18})");

    private SnapshotRefs() {
    }

    /**
     * Returns the ref that keeps one lane's trashed work at one epoch. Only {@code WorkTrash} deletes such a ref,
     * when a person empties the trash (the "nothing taken back" fitness function).
     *
     * @param laneId the lane
     * @param epoch  the lane's epoch
     * @return the trash ref
     */
    public static String trashRef(java.util.UUID laneId, long epoch) {
        return TRASH_PREFIX + laneId + "/" + epoch;
    }

    /**
     * Returns whether a name is exactly a trash ref, as {@link #trashRef} makes them.
     *
     * @param ref ref name
     * @return whether it is one
     */
    public static boolean isTrashRef(String ref) {
        return TRASH_REF.matcher(ref).matches();
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
