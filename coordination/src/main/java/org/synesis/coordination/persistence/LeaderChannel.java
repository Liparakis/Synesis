package org.synesis.coordination.persistence;

import java.io.IOException;
import java.util.List;

/**
 * How a member's coordination log talks to the project's leader (ADR-0098). The transport behind it (Link, or an
 * in-process pair in tests) is not visible here, and it is the transport that authenticates the member: the leader
 * is told which node a proposal came from, not asked to believe the proposal.
 */
public interface LeaderChannel {

    /**
     * Sends one encoded, signed entry for the leader to place at the member's head plus one. Sending the same bytes
     * again is safe: the leader answers {@link ProposalOutcome.Accepted} for an entry it already holds.
     *
     * @param encodedEvent the entry exactly as it would be placed
     * @return the leader's answer, or why there is none
     * @throws IOException when the transport fails in a way it cannot describe as an outcome
     */
    ProposalOutcome propose(byte[] encodedEvent) throws IOException;

    /**
     * Fetches the leader's entries after a sequence, in order.
     *
     * @param after      the member's head sequence
     * @param maxEntries most entries to return
     * @param maxBytes   soft bound on the encoded size; at least one entry is returned when one exists
     * @return encoded entries from {@code after + 1}; empty when the member is current
     * @throws IOException with code {@code LEADER_UNREACHABLE} when the leader cannot be reached
     */
    List<byte[]> pull(long after, int maxEntries, int maxBytes) throws IOException;
}
