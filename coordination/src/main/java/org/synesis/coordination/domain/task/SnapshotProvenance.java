package org.synesis.coordination.domain.task;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Immutable provenance attached to a published lane snapshot.
 *
 * @param workGroupId            group ID
 * @param laneId                 lane ID
 * @param authorityLineageId     durable authority lineage for this lane and its authorized successors
 * @param participant            participant
 * @param bindingIdentity        binding identity
 * @param claimEpoch             claim epoch
 * @param contractRevisions      exact contract revisions
 * @param handoffLineage         handoff lineage
 * @param claimSelectors         encoded exact-path/subtree claims
 * @param snapshotRef            immutable Git ref
 * @param integrityEvidence      integrity digest
 * @param artifactManifestDigest explicit provider/admin artifact manifest digest
 */
public record SnapshotProvenance(UUID workGroupId, UUID laneId, UUID authorityLineageId, String participant,
                                 String bindingIdentity, long claimEpoch, List<String> contractRevisions,
                                 List<String> handoffLineage, List<String> claimSelectors, String snapshotRef,
                                 String integrityEvidence, String artifactManifestDigest) {

    /**
     * Validates bounded provenance fields.
     */
    public SnapshotProvenance {
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(laneId, "laneId");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(participant, "participant");
        CoordinationNullException.requireNonNull(bindingIdentity, "bindingIdentity");
        if (claimEpoch < 1) {
            throw new CoordinationArgumentException("claim epoch must be positive");
        }
        contractRevisions = List.copyOf(CoordinationNullException.requireNonNull(contractRevisions,
                "contractRevisions"));
        handoffLineage = List.copyOf(CoordinationNullException.requireNonNull(handoffLineage, "handoffLineage"));
        claimSelectors = List.copyOf(CoordinationNullException.requireNonNull(claimSelectors, "claimSelectors"));
        CoordinationNullException.requireNonNull(snapshotRef, "snapshotRef");
        CoordinationNullException.requireNonNull(integrityEvidence, "integrityEvidence");
        CoordinationNullException.requireNonNull(artifactManifestDigest, "artifactManifestDigest");
    }

    /**
     * Constructs provenance without an explicit artifact manifest digest.
     *
     * @param workGroupId       group ID
     * @param laneId            lane ID
     * @param participant       participant
     * @param bindingIdentity   binding identity
     * @param claimEpoch        claim epoch
     * @param contractRevisions contract revisions
     * @param handoffLineage    handoff lineage
     * @param claimSelectors    claim selectors
     * @param snapshotRef       snapshot ref
     * @param integrityEvidence integrity evidence
     */
    public SnapshotProvenance(UUID workGroupId,
            UUID laneId,
            String participant,
            String bindingIdentity,
            long claimEpoch,
            List<String> contractRevisions,
            List<String> handoffLineage,
            List<String> claimSelectors,
            String snapshotRef,
            String integrityEvidence) {
        this(workGroupId,
                laneId,
                defaultAuthorityLineage(laneId),
                participant,
                bindingIdentity,
                claimEpoch,
                contractRevisions,
                handoffLineage,
                claimSelectors,
                snapshotRef,
                integrityEvidence,
                "UNRECORDED");
    }

    /**
     * Derives the deterministic lineage used by legacy intents.
     */
    private static UUID defaultAuthorityLineage(UUID laneId) {
        return UUID.nameUUIDFromBytes(("synesis-authority-lineage:" + laneId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the typed work-group identity that owns this snapshot.
     *
     * @return the typed work-group identity that owns this snapshot.
     */
    public WorkGroupId workGroupIdentity() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the typed lane identity that produced this snapshot.
     *
     * @return the typed lane identity that produced this snapshot.
     */
    public WorkIntentId laneIdentity() {
        return new WorkIntentId(laneId);
    }

    /**
     * Returns the typed authority lineage retained by this snapshot.
     *
     * @return the typed authority lineage retained by this snapshot.
     */
    public AuthorityLineageId lineageIdentity() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Returns the typed participant incarnation that published this snapshot.
     *
     * @return the typed participant incarnation that published this snapshot.
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Returns the provider session whose binding produced this snapshot.
     *
     * @return the provider session whose binding produced this snapshot.
     */
    public ProviderSessionId bindingSessionIdentity() {
        return new ProviderSessionId(bindingIdentity);
    }

    /**
     * Returns the typed claim epoch represented by this snapshot.
     *
     * @return the typed claim epoch represented by this snapshot.
     */
    public ClaimEpoch claimEpochIdentity() {
        return new ClaimEpoch(claimEpoch);
    }

}
