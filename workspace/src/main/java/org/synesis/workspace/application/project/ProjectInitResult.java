package org.synesis.workspace.application.project;

import java.util.List;
import java.util.Objects;

import org.synesis.link.identity.NodeIdentity;

/**
 * Result of initializing a project or materializing a local replica.
 *
 * @param status              initialization outcome
 * @param location            discovered project paths and portable metadata
 * @param identity            local node identity
 * @param createdIdentity     whether this operation created the identity
 * @param gitHeadStatus       result of establishing the Git base commit
 * @param retiredInstructions project files earlier versions wrote for the agents that this operation
 *                            changed or removed (the {@code AGENTS.md} section, a provider rule file),
 *                            relative to the project root with {@code /} separators; empty on a project
 *                            without them
 */
public record ProjectInitResult(ProjectInitStatus status, ProjectLocation location, NodeIdentity identity, boolean createdIdentity, String gitHeadStatus, List<String> retiredInstructions) {

    /**
     * Validates the initialization result.
     */
    public ProjectInitResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(gitHeadStatus, "Git HEAD status");
        retiredInstructions = List.copyOf(Objects.requireNonNull(retiredInstructions, "retired instructions"));
    }

    /**
     * Creates a result for an operation that retired no earlier instruction file.
     *
     * @param status          initialization outcome
     * @param location        discovered project paths and portable metadata
     * @param identity        local node identity
     * @param createdIdentity whether this operation created the identity
     * @param gitHeadStatus   result of establishing the Git base commit
     */
    public ProjectInitResult(ProjectInitStatus status, ProjectLocation location, NodeIdentity identity, boolean createdIdentity, String gitHeadStatus) {
        this(status, location, identity, createdIdentity, gitHeadStatus, List.of());
    }
}
