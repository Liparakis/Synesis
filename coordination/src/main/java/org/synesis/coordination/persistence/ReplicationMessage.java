package org.synesis.coordination.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.link.transport.control.ApplicationStreamCodec;

/**
 * What two computers of a project say to each other about their logs (ADR-0098 step 4), and what a process says to
 * its own computer's coordination server over loopback. {@link ReplicationCodec} is the wire form.
 *
 * <p>A requester never states who it is: the other side takes the sender from the authenticated Link session.</p>
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
     * Longest node ID naming a log, in UTF-8 bytes.
     */
    int MAX_NODE_BYTES = 128;

    /**
     * Most logs one {@link HeadList} names: a handful of computers, with room.
     */
    int MAX_LOGS = 256;

    /**
     * A member proposes one signed entry. Step 2 only: from compatibility 8 every computer appends to its own log and
     * nothing is proposed, so it is answered {@code REPLICATION_REQUEST_UNSUPPORTED}.
     *
     * @param project the project the entry belongs to
     * @param event   the entry exactly as it would be placed
     */
    record Propose(UUID project, byte[] event) implements ReplicationMessage {

    }

    /**
     * Asks for the entries of one log after a sequence.
     *
     * @param project    the project
     * @param log        the node whose log is wanted: the answering computer's own or a copy it holds
     * @param after      the requester's head of that log
     * @param maxEntries most entries wanted
     * @param maxBytes   soft bound on the encoded size of the entries
     */
    record Pull(UUID project, String log, long after, int maxEntries, int maxBytes) implements ReplicationMessage {

    }

    /**
     * Tells a peer that a log this computer holds grew.
     *
     * @param project the project
     * @param head    how many entries this computer holds in all; advisory
     */
    record Notice(UUID project, long head) implements ReplicationMessage {

    }

    /**
     * Asks which logs the other computer holds, and how far.
     *
     * @param project the project
     */
    record Heads(UUID project) implements ReplicationMessage {

    }

    /**
     * A claim this computer just appended, sent to a connected computer before the claiming agent is answered
     * (ADR-0098 step 4, "Claims are locks wherever they can be checked"). An agent's process sends the same message
     * to its own computer's server over loopback, which asks every connected computer.
     *
     * @param project the project
     * @param entry   the signed {@code WORK_INTENT_ANNOUNCED} entry exactly as placed
     */
    record ClaimSeen(UUID project, byte[] entry) implements ReplicationMessage {

    }

    /**
     * The answer to {@link ClaimSeen}.
     *
     * @param code empty when nothing objects; {@code OVERLAPPING_CLAIM} when the claim must give way
     */
    record ClaimAnswer(String code) implements ReplicationMessage {

    }

    /**
     * The answer to {@link Heads}.
     *
     * @param heads each log's last sequence, by the node whose log it is
     */
    record HeadList(Map<String, Long> heads) implements ReplicationMessage {

        /**
         * Copies the heads.
         *
         * @param heads each log's last sequence, by node
         */
        public HeadList {
            heads = Map.copyOf(heads);
        }
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
