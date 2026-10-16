package org.synesis.coordination.persistence;

/**
 * The leader's answer to one proposed entry, or what the transport knows when it has no answer.
 */
public sealed interface ProposalOutcome {

    /**
     * The leader holds the entry at this sequence, placed by this proposal or an earlier identical one.
     *
     * @param sequence sequence of the entry on the leader's log
     */
    record Accepted(long sequence) implements ProposalOutcome {

    }

    /**
     * The leader's log is not at the entry's previous sequence and digest. Nothing was placed.
     *
     * @param leaderHead the leader's head sequence
     */
    record HeadMoved(long leaderHead) implements ProposalOutcome {

    }

    /**
     * The leader's rules refuse the entry. Nothing was placed.
     *
     * @param code the refusal code, as a local refusal would carry it
     */
    record Refused(String code) implements ProposalOutcome {

    }

    /**
     * The transport could not reach the leader. Nothing was sent, so nothing was placed.
     */
    record Unreachable() implements ProposalOutcome {

    }

    /**
     * The proposal was sent and no answer came. The leader may or may not hold the entry.
     */
    record NoAnswer() implements ProposalOutcome {

    }
}
