package org.synesis.workspace.application.task;

import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitOutput;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Manages the transaction-owned prepared refs and the final snapshot ref that a completion
 * publishes through. It is separate because every operation here verifies that a ref still
 * resolves to the exact commit the completion transaction recorded, which is the fence that keeps
 * a stale or substituted object from being promoted.
 */
final class SnapshotRefLifecycle {

    private SnapshotRefLifecycle() {
    }

    /** Implements {@link TaskSnapshotService#pinPreparedRef(Path, TaskSnapshotRecord, String)}. */
    static String pinPreparedRef(Path worktreePath, TaskSnapshotRecord snapshot, String completionId) throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(completionId, "completionId");
        String ref = SnapshotRefs.preparedRef(completionId);
        runGitOutput(worktreePath, "update-ref", ref, snapshot.commitSha());
        String resolved = runGitOutput(worktreePath, "rev-parse", ref);
        if (!resolved.equals(snapshot.commitSha())) {
            throw new IOException("PREPARED_OBJECT_MISMATCH");
        }
        return ref;
    }

    /** Implements {@link TaskSnapshotService#removeSnapshotRef(Path, TaskSnapshotRecord)}. */
    static void removeSnapshotRef(Path worktreePath, TaskSnapshotRecord snapshot) throws IOException {
        runGitOutput(worktreePath, "update-ref", "-d", snapshot.provenance().snapshotRef());
    }

    /** Implements {@link TaskSnapshotService#removePreparedRef(Path, String)}. */
    static void removePreparedRef(Path worktreePath, String preparedRef) throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(preparedRef, "preparedRef");
        if (!preparedRef.startsWith(SnapshotRefs.PREPARED_PREFIX)) {
            throw new IOException("INVALID_PREPARED_REF");
        }
        runGitOutput(worktreePath, "update-ref", "-d", preparedRef);
    }

    /** Implements {@link TaskSnapshotService#promotePreparedRef(Path, String, String, String)}. */
    static void promotePreparedRef(Path worktreePath, String preparedRef, String finalRef, String expectedCommit) throws IOException {
        verifyPreparedRef(worktreePath, preparedRef, expectedCommit);
        runGitOutput(worktreePath, "update-ref", finalRef, expectedCommit);
        verifyPreparedRef(worktreePath, finalRef, expectedCommit);
    }

    /** Implements {@link TaskSnapshotService#verifyPreparedRef(Path, String, String)}. */
    static void verifyPreparedRef(Path worktreePath, String preparedRef, String expectedCommit) throws IOException {
        String resolved = runGitOutput(worktreePath, "rev-parse", preparedRef);
        if (!resolved.equals(expectedCommit)) {
            throw new IOException("PREPARED_OBJECT_MISMATCH");
        }
    }
}
