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
 * @param validationAlsoInLane whether the validation command also runs in a lane alone before its finish is
 *                         published; it always runs on the combined tree before integration. A lane whose tests need
 *                         a sibling's code cannot pass the command alone, so a project opts in
 * @param policySource     where the policy is read from when it is the signed log (ADR-0104), or {@code null} when the
 *                         three fields above are the policy, which is the project file's before a signed baseline. The
 *                         accessors of policy ask the source, which resolves when first asked and fails closed:
 *                         {@link PolicyUnavailableException} when the log cannot be read, {@link PolicyNotReceivedException}
 *                         on a member that has not applied the owner's baseline. Nothing that never asks for policy reads
 *                         the log, and no gate can run on a default because every gate asks through these accessors
 */
public record ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt, ProjectCommandSpec validation,
                              ReviewPolicy review, boolean validationAlsoInLane, PolicySource policySource) {

    /**
     * The gating policy as one value.
     *
     * @param validation           the validation command, or {@code null} for none
     * @param review               the review policy
     * @param validationAlsoInLane whether the command also runs in each lane alone
     * @param received             whether this computer has the policy; {@code false} only on a member that has not applied the owner's baseline
     * @param unsandboxedAllowed   whether the owner signed that agents' commands may run on a computer with no sandbox; only a signed log says {@code true}
     */
    public record Policy(ProjectCommandSpec validation, ReviewPolicy review, boolean validationAlsoInLane, boolean received, boolean unsandboxedAllowed) {
    }

    /**
     * Where a location's policy comes from when it is not the file's.
     */
    @FunctionalInterface
    public interface PolicySource {

        /**
         * Returns the policy now.
         *
         * @return the policy in force
         * @throws PolicyUnavailableException when the signed log cannot be read or verified
         */
        Policy current();
    }

    /**
     * Constructs a project location whose policy is known: read from the project file before a baseline, or from the
     * signed log after it.
     *
     * @param root             project root
     * @param synesisDirectory Synesis directory
     * @param metadataFile     project metadata file
     * @param profile          local profile directory
     * @param projectId        project identifier
     * @param createdAt        creation timestamp
     * @param validation       optional project-owned validation command
     * @param review           review policy of the project
     * @param validationAlsoInLane whether the command also runs in each lane alone
     */
    public ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt, ProjectCommandSpec validation,
                           ReviewPolicy review, boolean validationAlsoInLane) {
        this(root, synesisDirectory, metadataFile, profile, projectId, createdAt, validation, review, validationAlsoInLane, null);
    }

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
     * Constructs a project location whose validation command runs only before integration.
     *
     * @param root             project root
     * @param synesisDirectory Synesis directory
     * @param metadataFile     project metadata file
     * @param profile          local profile directory
     * @param projectId        project identifier
     * @param createdAt        creation timestamp
     * @param validation       optional project-owned validation command
     * @param review           review policy of the project
     */
    public ProjectLocation(Path root, Path synesisDirectory, Path metadataFile, Path profile, UUID projectId, Instant createdAt, ProjectCommandSpec validation, ReviewPolicy review) {
        this(root, synesisDirectory, metadataFile, profile, projectId, createdAt, validation, review, false);
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
     * Returns the validation command the project's policy declares.
     *
     * @return the command, or {@code null} when the project declares none
     * @throws PolicyNotReceivedException when this computer has not received the owner's signed policy
     */
    @Override
    public ProjectCommandSpec validation() {
        return received().validation();
    }

    /**
     * Returns the review policy of the project.
     *
     * @return the review policy
     * @throws PolicyNotReceivedException when this computer has not received the owner's signed policy
     */
    @Override
    public ReviewPolicy review() {
        return received().review();
    }

    /**
     * Returns whether the validation command also runs in each lane alone.
     *
     * @return whether it does
     * @throws PolicyNotReceivedException when this computer has not received the owner's signed policy
     */
    @Override
    public boolean validationAlsoInLane() {
        return received().validationAlsoInLane();
    }

    /**
     * Returns whether the owner signed that agents' commands may run on a computer with no sandbox. Only the signed log
     * can say {@code true}; the project file never does.
     *
     * @return whether it is allowed
     * @throws PolicyNotReceivedException when this computer has not received the owner's signed policy
     */
    public boolean unsandboxedAllowed() {
        return received().unsandboxedAllowed();
    }

    /**
     * Returns the policy, refusing where this computer does not have it.
     */
    private Policy received() {
        Policy policy = policy();
        if (!policy.received()) {
            throw new PolicyNotReceivedException();
        }
        return policy;
    }

    /**
     * Returns the policy and whether this computer has it, without refusing.
     *
     * @return the policy in force
     * @throws PolicyUnavailableException when the signed log cannot be read or verified
     */
    public Policy policy() {
        return policySource == null ? new Policy(validation, review, validationAlsoInLane, true, false) : policySource.current();
    }

    /**
     * Returns whether this computer has the project's signed policy: {@code false} only on a member of a shared project
     * that has not applied the owner's baseline, where every accessor of policy refuses.
     *
     * @return whether the policy is here
     * @throws PolicyUnavailableException when the signed log cannot be read or verified
     */
    public boolean policyReceived() {
        return policy().received();
    }

    /**
     * Returns this location with its policy read from a source: the signed log.
     *
     * @param source where the policy comes from
     * @return the same project with that policy
     */
    public ProjectLocation withPolicySource(PolicySource source) {
        return new ProjectLocation(root, synesisDirectory, metadataFile, profile, projectId, createdAt, validation, review, validationAlsoInLane, source);
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
     * Returns the file that holds this computer's operator key, encrypted under a passphrase (ADR-0104). It sits in the
     * profile beside the node key and is read only by commands a person runs.
     *
     * @return operator key file under the profile
     */
    public Path operatorKeyFile() {
        return profile.resolve("operator/operator.key");
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