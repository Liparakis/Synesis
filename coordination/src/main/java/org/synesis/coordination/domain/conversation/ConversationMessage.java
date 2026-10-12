package org.synesis.coordination.domain.conversation;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * One message of a conversation between lanes (ADR-0093).
 *
 * <p>A message is text. No projection of lane state reads it, and nothing a lane may do follows
 * from it. A conversation is its set of members, so a different set is a different conversation
 * and there is nothing to join or to leave. Members are authority lineages and not sessions: an
 * agent that continues a lane has that lane's conversations.</p>
 *
 * @param id                message identifier
 * @param senderLineageId   lineage of the lane that sent it
 * @param members           lineages of the member lanes, the sender among them, in canonical order
 * @param text              what was said
 * @param sequence          position of the message in the project log
 * @param sentAtEpochMillis when it was recorded
 */
public record ConversationMessage(UUID id, UUID senderLineageId, List<UUID> members, String text, long sequence, long sentAtEpochMillis) {

    /** Most lanes one conversation can hold. A structural bound of the payload, not a policy. */
    public static final int MAX_MEMBERS = 16;

    private static final int MAGIC_SENT = 0x53434d31;
    private static final int MAGIC_DELIVERED = 0x53434d32;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, false, "conversation message bound", "truncated conversation message").checkingWrites(MAX_TEXT_BYTES,
            "conversation message bound");

    /**
     * Validates the message and puts its members in canonical order.
     */
    public ConversationMessage {
        CoordinationNullException.requireNonNull(id, "id");
        CoordinationNullException.requireNonNull(senderLineageId, "senderLineageId");
        CoordinationNullException.requireNonNull(text, "text");
        members = List.copyOf(new TreeSet<>(CoordinationNullException.requireNonNull(members, "members")));
        if (members.size() < 2 || members.size() > MAX_MEMBERS || !members.contains(senderLineageId)) {
            throw new CoordinationArgumentException("a conversation has two to " + MAX_MEMBERS + " member lanes and its sender is one of them");
        }
        if (text.isBlank()) {
            throw new CoordinationArgumentException("conversation message text is required");
        }
    }

    /**
     * Names the conversation of a set of member lanes. The same set always names the same
     * conversation, so sending to a set of members opens it or continues it without a lookup.
     *
     * @param members lineages of the member lanes, in any order
     * @return the conversation identifier
     */
    public static UUID conversationOf(Collection<UUID> members) {
        String canonical = new TreeSet<>(members).stream().map(UUID::toString).collect(Collectors.joining(","));
        return UUID.nameUUIDFromBytes(("synesis-conversation:" + canonical).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Groups messages into their conversations, in the order each conversation began.
     *
     * @param messages messages, oldest first
     * @return each conversation's messages, oldest first, by conversation identifier
     */
    public static Map<UUID, List<ConversationMessage>> byConversation(Collection<ConversationMessage> messages) {
        Map<UUID, List<ConversationMessage>> grouped = new LinkedHashMap<>();
        for (ConversationMessage message : messages) {
            grouped.computeIfAbsent(message.conversationId(), id -> new ArrayList<>()).add(message);
        }
        return grouped;
    }

    /**
     * Returns the conversation this message belongs to.
     *
     * @return the identifier its members name
     */
    public UUID conversationId() {
        return conversationOf(members);
    }

    /**
     * Tells whether a lane is sent this message: it is a member and did not write it.
     *
     * @param lineageId lineage of the lane
     * @return true when the message is addressed to that lane
     */
    public boolean addressedTo(UUID lineageId) {
        return members.contains(lineageId) && !senderLineageId.equals(lineageId);
    }

    /**
     * Encodes the sending of a message.
     *
     * @param id              message identifier
     * @param senderLineageId lineage of the sending lane
     * @param members         lineages of the member lanes, the sender among them
     * @param text            what is said
     * @return canonical event payload
     */
    public static byte[] encodeSent(UUID id, UUID senderLineageId, Collection<UUID> members, String text) {
        // Built once here so that a payload nobody could replay is refused before it is signed.
        ConversationMessage message = new ConversationMessage(id, senderLineageId, List.copyOf(members), text, 0, 0);
        return WireFormat.encode(MAGIC_SENT, out -> {
            writeUuid(out, message.id());
            writeUuid(out, message.senderLineageId());
            out.writeInt(message.members().size());
            for (UUID member : message.members()) {
                writeUuid(out, member);
            }
            TEXT.writeText(out, message.text());
        });
    }

    static ConversationMessage decodeSent(PredictionEvent event) throws IOException {
        try {
            DataInputStream in = open(event.payload(), MAGIC_SENT);
            UUID id = readUuid(in);
            UUID sender = readUuid(in);
            int count = in.readInt();
            if (count < 2 || count > MAX_MEMBERS) {
                throw new CoordinationException("conversation member count");
            }
            List<UUID> members = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                members.add(readUuid(in));
            }
            ConversationMessage message = new ConversationMessage(id, sender, members, TEXT.readText(in), event.sequence(), event.createdAtEpochMillis());
            requireEnd(in);
            return message;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed conversation message", failure);
        }
    }

    /**
     * Encodes that a lane has been handed every message addressed to it up to a log position.
     *
     * @param lineageId lineage of the lane
     * @param through   log position of the newest message handed over
     * @return canonical event payload
     */
    public static byte[] encodeDelivered(UUID lineageId, long through) {
        return WireFormat.encode(MAGIC_DELIVERED, out -> {
            writeUuid(out, lineageId);
            out.writeLong(through);
        });
    }

    static Delivered decodeDelivered(byte[] encoded) throws IOException {
        try {
            DataInputStream in = open(encoded, MAGIC_DELIVERED);
            Delivered delivered = new Delivered(readUuid(in), in.readLong());
            requireEnd(in);
            return delivered;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed conversation delivery", failure);
        }
    }

    private static DataInputStream open(byte[] encoded, int magic) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != magic) {
            throw new CoordinationException("conversation event format");
        }
        return in;
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.available() != 0) {
            throw new CoordinationException("trailing conversation event bytes");
        }
    }

    /**
     * How far a lane has been handed its messages.
     *
     * @param lineageId lineage of the lane
     * @param through   log position of the newest message handed over
     */
    record Delivered(UUID lineageId, long through) {
    }
}
