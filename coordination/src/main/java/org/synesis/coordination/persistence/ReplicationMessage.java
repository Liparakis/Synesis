package org.synesis.coordination.persistence;

import java.util.List;
import java.util.UUID;
import org.synesis.link.transport.control.ApplicationStreamCodec;

/**
 * What a member and its leader say to each other (ADR-0098 step 2), and what a process says to its own computer's
 * coordination server over loopback. {@link ReplicationCodec} is the wire form.
 *
 * <p>A requester never states who it is: the leader takes the sender from the authenticated Link session.</p>
 */
public sealed interface ReplicationMessage {

    /**
     * Largest encoded message. It is the Link application payload bound, so any message fits one stream.
     */
    int MAX_BYTES = ApplicationStreamCodec.MAX_PAYLOAD_BYTES;

    /**
     * Most entries one answer to a pull carries.
     */
    int MAX_ENTRIES = 1024;

    /**
     * Largest encoded log entry in a proposal or an answer: a 64 KiB payload and its header, with room.
     */
    int MAX_ENTRY_BYTES = 80 * 1024;

    /**
     * Longest refusal code in UTF-8 bytes.
     */
    int MAX_CODE_BYTES = 64;

    /**
     * A member proposes one signed entry.
     *
     * @param project the project the entry belongs to
     * @param event   the entry exactly as it would be placed
     */
    record Propose(UUID project, byte[] event) implements ReplicationMessage {

    }

    /**
     * A member asks for the entries after its head.
     *
     * @param project    the project
     * @param after      the member's head sequence
     * @param maxEntries most entries wanted
     * @param maxBytes   soft bound on the encoded size of the entries
     */
    record Pull(UUID project, long after, int maxEntries, int maxBytes) implements ReplicationMessage {

    }

    /**
     * The leader tells a member that its head moved.
     *
     * @param project the project
     * @param head    the leader's head sequence
     */
    record Notice(UUID project, long head) implements ReplicationMessage {

    }

    /**
     * The answer to a {@link Propose}, or what a relay knows when it has no answer.
     *
     * @param outcome the outcome
     */
    record Outcome(ProposalOutcome outcome) implements ReplicationMessage {

    }

    /**
     * The answer to a {@link Pull}.
     *
     * @param entries encoded entries in sequence order
     */
    record Entries(List<byte[]> entries) implements ReplicationMessage {

    }

    /**
     * The answer to a {@link Notice}.
     */
    record Noticed() implements ReplicationMessage {

    }
}
