package org.synesis.coordination.domain.task;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable provenance attached to a published lane snapshot. */
public record SnapshotProvenance(UUID workGroupId, UUID laneId, String participant,
        String bindingIdentity, long claimEpoch, List<String> contractRevisions,
        List<String> handoffLineage, String snapshotRef, String integrityEvidence) {
    /** Validates bounded provenance fields. */
    public SnapshotProvenance {
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(bindingIdentity, "bindingIdentity");
        if (claimEpoch < 1) throw new IllegalArgumentException("claim epoch must be positive");
        contractRevisions = List.copyOf(Objects.requireNonNull(contractRevisions, "contractRevisions"));
        handoffLineage = List.copyOf(Objects.requireNonNull(handoffLineage, "handoffLineage"));
        Objects.requireNonNull(snapshotRef, "snapshotRef");
        Objects.requireNonNull(integrityEvidence, "integrityEvidence");
    }
}
