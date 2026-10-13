package org.synesis.workspace.application.project;

import java.util.Objects;

/**
 * What setting or clearing a project's validation command did.
 *
 * @param location the project as its metadata now reads
 * @param changed  whether the metadata file was rewritten; false when the project already declared what was asked
 * @param commit   abbreviated identity of the commit that holds the rewritten file, or empty when none was made
 */
public record ProjectValidationChange(ProjectLocation location, boolean changed, String commit) {

    /**
     * Validates the change.
     */
    public ProjectValidationChange {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(commit, "commit");
    }
}
