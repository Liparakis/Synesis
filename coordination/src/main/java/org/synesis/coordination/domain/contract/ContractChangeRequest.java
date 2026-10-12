package org.synesis.coordination.domain.contract;

import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * What a consumer needs from a file that its contract does not say, or does not say yet (ADR-0092).
 *
 * <p>It is addressed to the lane that claims the file, so it can be filed before any contract
 * exists and is then how a consumer asks for one. The owner answers each by its identifier. A
 * request no revision answers stays open, which is how the owner comes to see every consumer's
 * need side by side.</p>
 *
 * @param id                 request identifier
 * @param ownerLineageId     authority lineage of the lane that claims the file
 * @param path               the file the consumer builds on
 * @param filerLineageId     authority lineage of the consumer's lane
 * @param need               what the consumer needs, in its own words
 * @param baseRevision       contract revision the consumer had read, or zero when none existed
 * @param state              whether the owner has answered
 * @param answeredByRevision revision that covers the need, or zero
 * @param reason             why it was declined or withdrawn, or empty
 */
public record ContractChangeRequest(UUID id, UUID ownerLineageId, ResourceSelector path, UUID filerLineageId, String need, long baseRevision, State state, long answeredByRevision, String reason) {

    /** Reason of a request declined because its owner's lane ended before answering. */
    public static final String OWNER_LANE_ENDED = "OWNER_LANE_ENDED";
    /** Reason of a request withdrawn because the lane that filed it ended. */
    public static final String FILER_LANE_ENDED = "FILER_LANE_ENDED";

    public static final int MAX_NEED_CHARACTERS = 4000;

    /**
     * Validates the request.
     */
    public ContractChangeRequest {
        CoordinationNullException.requireNonNull(id, "change request ID");
        CoordinationNullException.requireNonNull(ownerLineageId, "owner lineage");
        CoordinationNullException.requireNonNull(path, "path");
        CoordinationNullException.requireNonNull(filerLineageId, "filer lineage");
        CoordinationNullException.requireNonNull(state, "state");
        CoordinationNullException.requireNonNull(reason, "reason");
        if (need == null || need.isBlank() || need.length() > MAX_NEED_CHARACTERS) {
            throw new CoordinationArgumentException("a change request states a need of 1 to " + MAX_NEED_CHARACTERS + " characters");
        }
        if (baseRevision < 0 || answeredByRevision < 0) {
            throw new CoordinationArgumentException("contract revisions are not negative");
        }
    }

    /**
     * Creates an open request.
     *
     * @param id             request identifier
     * @param ownerLineageId lineage of the lane that claims the file
     * @param path           the file the consumer builds on
     * @param filerLineageId lineage of the consumer's lane
     * @param need           what the consumer needs
     * @param baseRevision   contract revision the consumer had read, or zero
     * @return the open request
     */
    public static ContractChangeRequest open(UUID id, UUID ownerLineageId, ResourceSelector path, UUID filerLineageId, String need, long baseRevision) {
        return new ContractChangeRequest(id, ownerLineageId, path, filerLineageId, need, baseRevision, State.OPEN, 0, "");
    }

    ContractChangeRequest answered(State answer, long revision, String because) {
        return new ContractChangeRequest(id, ownerLineageId, path, filerLineageId, need, baseRevision, answer, revision, because == null ? "" : because);
    }

    /** How a change request stands. */
    public enum State {
        /** The owner has not answered. */
        OPEN,
        /** A revision covers the need. */
        ACCEPTED,
        /** The owner declined, with a reason, or its lane ended first. */
        DECLINED,
        /** The lane that filed it ended. */
        WITHDRAWN
    }
}
