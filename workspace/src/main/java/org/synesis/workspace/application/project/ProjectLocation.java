package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import java.time.Instant;

import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;


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
 * @param review           review policy the project gives every work group announced in it; never {@code null}
 */
public record ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt, ProjectCommandSpec validation,
                              ReviewPolicy review) {

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
        this(root, synesisDirectory, metadataFile, profile, projectId, createdAt, null, ReviewPolicy.none());
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
        review = review == null ? ReviewPolicy.none() : review;
    }

    /**
     * Returns the portable identity represented by this location.
     *
     * @return immutable project identity
     */
    public ProjectIdentity identity() {
        return new ProjectIdentity(projectId, createdAt);
    }

    /**
     * Returns the directory holding this project's coordination event log.
     *
     * @return coordination directory under the project root
     */
    public Path coordinationDirectory() {
        return root.resolve(".synesis/coordination");
    }

    /**
     * Opens a fresh view of this project's coordination store, so events appended by other
     * processes are visible.
     *
     * @return coordination store for this project
     * @throws IOException              if the event log cannot be read
     * @throws GeneralSecurityException if stored events fail verification
     */
    public PredictionEventStore openCoordinationStore() throws IOException, GeneralSecurityException {
        return new PredictionEventStore(coordinationDirectory(), projectId);
    }

    /**
     * Returns the directory holding the signing identity files of this project's profile.
     *
     * @return link directory under the profile
     */
    public Path linkDirectory() {
        return profile.resolve("link");
    }

    /**
     * Loads the signing identity of this project's profile, creating it on first use.
     *
     * @return the long-term node identity
     * @throws IOException              if identity storage is missing, corrupt, or inconsistent
     * @throws GeneralSecurityException if key material is invalid
     */
    public NodeIdentity loadOrCreateIdentity() throws IOException, GeneralSecurityException {
        return new IdentityBootstrap(linkDirectory()).loadOrCreate().identity();
    }
}