package org.synesis.coordination.application;

import java.io.IOException;
import java.security.GeneralSecurityException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

/**
 * Runs one read, decide, append sequence under the project append lock.
 *
 * <p>The long-lived store a service was built with is never read for a decision: its projections can be stale after
 * another process appended. Every sequence therefore decides on a store opened after the lock is held.</p>
 */
final class ProjectAppends {

    private final PredictionEventStore store;
    private final boolean readOnly;

    /**
     * Binds the helper to one project log.
     *
     * @param store event store naming the project root and project identity
     */
    ProjectAppends(PredictionEventStore store) {
        this(store, false);
    }

    /**
     * Binds the helper to one project log, for reading only when {@code readOnly}: every view is replayed without
     * creating, moving or removing anything, and no sequence that decides and appends runs.
     *
     * @param store    event store naming the project root and project identity
     * @param readOnly whether the helper may only read
     */
    ProjectAppends(PredictionEventStore store, boolean readOnly) {
        this.store = CoordinationNullException.requireNonNull(store, "store");
        this.readOnly = readOnly;
    }

    /**
     * Takes the project append lock, opens a fresh store, and runs the action with it.
     *
     * @param action decision and append steps that must see no concurrent append
     * @param <T>    result type
     * @return the action's result
     * @throws IOException              when the lock is unavailable or the action fails durably
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    <T> T call(Action<T> action) throws IOException, GeneralSecurityException {
        if (readOnly) {
            throw new CoordinationException("COORDINATION_READ_ONLY");
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            return action.apply(freshUnder(lock));
        }
    }

    /**
     * Same as {@link #call} for an action with no result.
     *
     * @param action decision and append steps that must see no concurrent append
     * @throws IOException              when the lock is unavailable or the action fails durably
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    void run(Step action) throws IOException, GeneralSecurityException {
        call(current -> {
            action.apply(current);
            return null;
        });
    }

    /**
     * Opens a fresh store for a caller that already holds the project append lock.
     *
     * @param lock lock held by the caller
     * @return store replayed after the lock was taken
     * @throws IOException              when the lock is no longer held or replay fails
     * @throws GeneralSecurityException when signature verification fails
     */
    PredictionEventStore freshUnder(ProjectAppendLock lock) throws IOException, GeneralSecurityException {
        if (!lock.isHeld()) {
            throw new CoordinationException("event append lock unavailable");
        }
        return fresh();
    }

    /**
     * Replays a view of the project's durable coordination state without taking the lock, for reads only.
     *
     * @return fresh store
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signature verification fails
     */
    PredictionEventStore fresh() throws IOException, GeneralSecurityException {
        return readOnly ? PredictionEventStore.openReadOnly(store.rootDirectory(), store.projectId())
                : new PredictionEventStore(store.rootDirectory(), store.projectId());
    }

    /**
     * An action with a result, run under the lock.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    interface Action<T> {

        /**
         * Runs under the lock.
         *
         * @param current store opened after the lock was taken
         * @return result
         * @throws IOException              when payload validation or durable I/O fails
         * @throws GeneralSecurityException when signing or signature verification fails
         */
        T apply(PredictionEventStore current) throws IOException, GeneralSecurityException;
    }

    /**
     * An action without a result, run under the lock.
     */
    @FunctionalInterface
    interface Step {

        /**
         * Runs under the lock.
         *
         * @param current store opened after the lock was taken
         * @throws IOException              when payload validation or durable I/O fails
         * @throws GeneralSecurityException when signing or signature verification fails
         */
        void apply(PredictionEventStore current) throws IOException, GeneralSecurityException;
    }
}
