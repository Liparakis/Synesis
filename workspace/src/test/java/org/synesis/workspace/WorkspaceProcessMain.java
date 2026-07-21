package org.synesis.workspace;

import org.synesis.workspace.application.WorkspaceOperations;

/** Test-only process entry point for the library-backed workspace runner. */
public final class WorkspaceProcessMain {
    private WorkspaceProcessMain() {
    }

    /** Runs one isolated workspace invocation. @param arguments runner arguments */
    public static void main(String[] arguments) {
        System.exit(WorkspaceOperations.run(arguments));
    }
}
