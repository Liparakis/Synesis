package org.synesis.workspace.application.project;

import java.util.Objects;

import org.synesis.link.identity.NodeIdentity;

/**
 * Result of initializing a project or materializing a local replica.
 *
 * @param status          initialization outcome
 * @param location        discovered project paths and portable metadata
 * @param identity        local node identity
 * @param createdIdentity whether this operation created the identity
 * @param gitHeadStatus   result of establishing the Git base commit
 */
public record ProjectInitResult(ProjectInitStatus status, ProjectLocation location, NodeIdentity identity, boolean createdIdentity, String gitHeadStatus) {

    /**
     * Validates the initialization result.
     */
    public ProjectInitResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(gitHeadStatus, "Git HEAD status");
    }
}
