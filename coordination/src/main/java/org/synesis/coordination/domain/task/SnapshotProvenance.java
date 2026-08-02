package org.synesis.coordination.domain.task;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable provenance attached to a published lane snapshot.
 *
 * @param workGroupId group ID
 * @param laneId lane ID
 * @param authorityLineageId durable authority lineage for this lane and its authorized successors
 * @param participant participant
 * @param bindingIdentity binding identity
 * @param claimEpoch claim epoch
 * @param contractRevisions exact contract revisions
 * @param handoffLineage handoff lineage
 * @param claimSelectors encoded exact-path/subtree claims
 * @param snapshotRef immutable Git ref
 * @param integrityEvidence integrity digest
 * @param artifactManifestDigest explicit provider/admin artifact manifest digest
 */
public record SnapshotProvenance(UUID workGroupId, UUID laneId, UUID authorityLineageId, String participant,
        String bindingIdentity, long claimEpoch, List<String> contractRevisions,
        List<String> handoffLineage, List<String> claimSelectors,
        String snapshotRef, String integrityEvidence, String artifactManifestDigest) {
    /** Validates bounded provenance fields. */
    public SnapshotProvenance {
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(authorityLineageId, "authorityLineageId");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(bindingIdentity, "bindingIdentity");
        if (claimEpoch < 1) throw new IllegalArgumentException("claim epoch must be positive");
        contractRevisions = List.copyOf(Objects.requireNonNull(contractRevisions, "contractRevisions"));
        handoffLineage = List.copyOf(Objects.requireNonNull(handoffLineage, "handoffLineage"));
        claimSelectors = List.copyOf(Objects.requireNonNull(claimSelectors, "claimSelectors"));
        Objects.requireNonNull(snapshotRef, "snapshotRef");
        Objects.requireNonNull(integrityEvidence, "integrityEvidence");
        Objects.requireNonNull(artifactManifestDigest, "artifactManifestDigest");
    }

    /** Constructs provenance without an explicit artifact manifest digest.
     * @param workGroupId group ID
     * @param laneId lane ID
     * @param participant participant
     * @param bindingIdentity binding identity
     * @param claimEpoch claim epoch
     * @param contractRevisions contract revisions
     * @param handoffLineage handoff lineage
     * @param claimSelectors claim selectors
     * @param snapshotRef snapshot ref
     * @param integrityEvidence integrity evidence
     */
    public SnapshotProvenance(UUID workGroupId, UUID laneId, String participant,
            String bindingIdentity, long claimEpoch, List<String> contractRevisions,
            List<String> handoffLineage, List<String> claimSelectors,
            String snapshotRef, String integrityEvidence) {
        this(workGroupId, laneId, defaultAuthorityLineage(laneId), participant, bindingIdentity, claimEpoch, contractRevisions,
                handoffLineage, claimSelectors, snapshotRef, integrityEvidence, "UNRECORDED");
    }

    private static UUID defaultAuthorityLineage(UUID laneId) {
        return UUID.nameUUIDFromBytes(("synesis-authority-lineage:" + laneId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
