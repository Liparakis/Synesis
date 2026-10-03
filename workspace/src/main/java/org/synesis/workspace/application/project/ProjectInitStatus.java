package org.synesis.workspace.application.project;

/**
 * Outcome of initializing or locating local project state.
 */
public enum ProjectInitStatus {
    /**
     * Project state was created.
     */
    SUCCESS,
    /**
     * Valid existing project state was found.
     */
    ALREADY_INITIALIZED
}
