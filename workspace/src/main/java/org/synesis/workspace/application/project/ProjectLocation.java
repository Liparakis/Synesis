package org.synesis.workspace.application.project;

import java.nio.file.Path;

import java.time.Instant;

import java.util.Objects;
import java.util.UUID;


/**
 * Discovered project paths and shareable metadata used by workspace application services.
 *
 * @param root             normalized project root
 * @param synesisDirectory normalized private Synesis state directory
 * @param metadataFile     normalized shareable project metadata file
 * @param profile          normalized local profile directory
 * @param projectId        stable project identifier
 * @param createdAt        project creation time
 * @param validation       optional project-owned validation command
 */
public record ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt, ProjectCommandSpec validation) {

    /**
     * Constructs a project location without a project-owned validation command.
     *
     * @param root             project root
     * @param synesisDirectory Synesis directory
     * @param metadataFile     project metadata file
     * @param profile          local profile directory
     * @param projectId        project identifier
     * @param createdAt        creation timestamp
     */
    public ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt) {
        this(root, synesisDirectory, metadataFile, profile, projectId, createdAt, null);
    }

    /**
     * Validates and normalizes the project paths.
     */
    public ProjectLocation {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        synesisDirectory = Objects.requireNonNull(synesisDirectory, "synesis directory").toAbsolutePath().normalize();
        metadataFile = Objects.requireNonNull(metadataFile, "metadata file").toAbsolutePath().normalize();
        profile = Objects.requireNonNull(profile, "profile").toAbsolutePath().normalize();
        Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(createdAt, "created at");
    }

    /**
     * Returns the portable identity represented by this location.
     *
     * @return immutable project identity
     */
    public ProjectIdentity identity() {
        return new ProjectIdentity(projectId, createdAt);
    }
}
