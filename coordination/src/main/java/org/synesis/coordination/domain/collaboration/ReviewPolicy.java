package org.synesis.coordination.domain.collaboration;

import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Structured review requirement declared by a logical work group.
 *
 * @param mandatory            whether review is mandatory
 * @param independenceRequired whether reviewer identity must differ from the implementation owner
 */
public record ReviewPolicy(boolean mandatory, boolean independenceRequired) {

    private static final ReviewPolicy NONE = new ReviewPolicy(false, false);
    private static final ReviewPolicy MANDATORY_INDEPENDENT = new ReviewPolicy(true, true);

    /**
     * Validates the policy combinations currently supported by the review authority model.
     */
    public ReviewPolicy {
        if (mandatory != independenceRequired) {
            throw new CoordinationArgumentException("mandatory review currently requires an independent reviewer");
        }
    }

    /**
     * Returns the policy used by historical and ordinary groups.
     *
     * @return no-review policy
     */
    public static ReviewPolicy none() {
        return NONE;
    }

    /**
     * Returns the mandatory independent review policy.
     *
     * @return mandatory independent-review policy
     */
    public static ReviewPolicy mandatoryIndependent() {
        return MANDATORY_INDEPENDENT;
    }
}
