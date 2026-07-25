package org.synesis.workspace.repair;

/**
 * Closed enumeration of narrowly safe administrative repair actions.
 *
 * @since 1.0
 */
public enum RepairAction {
    /**
     * Creates missing administrative subdirectories under %LOCALAPPDATA%\Synesis\workspaces\&lt;project-id&gt;\admin.
     */
    CREATE_MISSING_ADMIN_DIRECTORY,

    /**
     * Safely removes verified stale cleanup-execution.lock file.
     */
    REMOVE_VERIFIED_STALE_CLEANUP_LOCK,

    /**
     * Safely removes verified stale reconciliation-execution.lock file.
     */
    REMOVE_VERIFIED_STALE_RECONCILIATION_LOCK,

    /**
     * Safely removes verified stale repair-execution.lock file.
     */
    REMOVE_VERIFIED_STALE_REPAIR_LOCK,

    /**
     * Atomically archives corrupted administrative plan file to backup directory.
     */
    ARCHIVE_CORRUPT_ADMIN_PLAN,

    /**
     * Atomically archives corrupted administrative journal file to backup directory.
     */
    ARCHIVE_CORRUPT_ADMIN_JOURNAL,

    /**
     * Finalizes incomplete administrative journal entry with terminal status.
     */
    FINALIZE_INCOMPLETE_ADMIN_JOURNAL_ENTRY,

    /**
     * Safely removes expired administrative temporary file (.tmp).
     */
    REMOVE_EXPIRED_ADMIN_TEMP_FILE,

    /**
     * Rebuilds derived administrative index from intact immutable plans or journals.
     */
    REBUILD_DERIVED_ADMIN_INDEX
}
