package org.synesis.coordination.domain.conversation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic replay projection of what lanes said to each other (ADR-0093).
 *
 * <p>It is read to show messages and for nothing else. No projection of lane state, no gate and
 * no next action reads it, which is what keeps a message from binding anyone. Who may send, and
 * how much, is decided by the service that appends: those are rules about open lanes and about
 * bounds that are meant to change, and neither can be a condition of replaying an old log.</p>
 */
public final class ConversationProjection {

    private final List<ConversationMessage> messages = new ArrayList<>();
    private final Map<UUID, Long> delivered = new HashMap<>();

    /**
     * Creates an empty replay projection.
     */
    public ConversationProjection() {
    }

    private ConversationProjection(ConversationProjection source) {
        messages.addAll(source.messages);
        delivered.putAll(source.delivered);
    }

    /**
     * Applies a conversation event.
     *
     * @param event signed event
     * @throws IOException if the event is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case CONVERSATION_MESSAGE_SENT -> send(ConversationMessage.decodeSent(event));
            case CONVERSATION_MESSAGES_DELIVERED -> deliver(ConversationMessage.decodeDelivered(event.payload()), event.sequence());
            default -> {
            }
        }
    }

    /**
     * Validates a conversation event without mutating this projection.
     *
     * @param event signed event
     * @throws IOException if the event is malformed
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        new ConversationProjection(this).apply(event);
    }

    /**
     * Returns every message of every conversation, oldest first. This is the operator's view.
     *
     * @return immutable message snapshot
     */
    public synchronized List<ConversationMessage> messages() {
        return List.copyOf(messages);
    }

    /**
     * Returns the messages of the conversations a lane is a member of, oldest first.
     *
     * @param lineageId lineage of the lane
     * @return what was said in its conversations, its own messages included
     */
    public synchronized List<ConversationMessage> of(UUID lineageId) {
        return messages.stream().filter(message -> message.members().contains(lineageId)).toList();
    }

    /**
     * Returns the messages addressed to a lane that it has not been handed, oldest first.
     *
     * @param lineageId lineage of the lane
     * @return its unread messages
     */
    public synchronized List<ConversationMessage> unread(UUID lineageId) {
        long through = delivered.getOrDefault(lineageId, 0L);
        return messages.stream().filter(message -> message.sequence() > through && message.addressedTo(lineageId)).toList();
    }

    /**
     * Counts the messages a lane has sent.
     *
     * @param lineageId lineage of the lane
     * @return how many messages carry it as their sender
     */
    public synchronized long sentBy(UUID lineageId) {
        return messages.stream().filter(message -> message.senderLineageId().equals(lineageId)).count();
    }

    /**
     * Returns the members of a conversation.
     *
     * @param conversationId conversation identifier
     * @return its member lineages, or an empty list when nothing was ever said in it
     */
    public synchronized List<UUID> members(UUID conversationId) {
        return messages.stream().filter(message -> message.conversationId().equals(conversationId)).findFirst().map(ConversationMessage::members).orElse(List.of());
    }

    private void send(ConversationMessage sent) throws IOException {
        if (messages.stream().anyMatch(message -> message.id().equals(sent.id()))) {
            throw new CoordinationException("CONVERSATION_MESSAGE_EXISTS");
        }
        messages.add(sent);
    }

    /** A mark can only cover what is already in the log, and never moves back. */
    private void deliver(ConversationMessage.Delivered mark, long sequence) throws IOException {
        if (mark.through() < 0 || mark.through() >= sequence) {
            throw new CoordinationException("CONVERSATION_DELIVERY_AHEAD_OF_LOG");
        }
        delivered.merge(mark.lineageId(), mark.through(), Math::max);
    }
}
