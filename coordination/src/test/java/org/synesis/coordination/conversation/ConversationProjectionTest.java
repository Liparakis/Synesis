package org.synesis.coordination.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.conversation.ConversationProjection;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies what the event log accepts as a conversation message or a delivery mark, who is handed a message,
 * and what a replay reads back (ADR-0093).
 */
final class ConversationProjectionTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private final NodeIdentity identity;

    ConversationProjectionTest() throws Exception {
        identity = NodeIdentity.generate();
    }

    private long say(PredictionEventStore store, UUID id, UUID sender, List<UUID> members, String text) throws Exception {
        return store.append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGE_SENT, identity.nodeId(), ConversationMessage.encodeSent(id, sender, members, text), identity)
                .sequence();
    }

    private long say(PredictionEventStore store, UUID sender, List<UUID> members, String text) throws Exception {
        return say(store, UUID.randomUUID(), sender, members, text);
    }

    private long mark(PredictionEventStore store, UUID lane, long through) throws Exception {
        return store.append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGES_DELIVERED, identity.nodeId(), ConversationMessage.encodeDelivered(lane, through), identity)
                .sequence();
    }

    @Test
    void aMessageReachesEveryMemberAndNoOneElse(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());

        say(store, A, List.of(A, B), "does listing create the store file?");

        ConversationProjection projection = store.conversationProjection();
        assertEquals(1, projection.unread(B).size());
        assertEquals("does listing create the store file?", projection.unread(B).getFirst().text());
        assertTrue(projection.unread(A).isEmpty(), "the sender is not sent its own message");
        assertTrue(projection.unread(C).isEmpty());
        assertEquals(1, projection.of(A).size(), "the sender still reads what it said");
        assertEquals(1, projection.of(B).size());
        assertTrue(projection.of(C).isEmpty(), "a lane outside the conversation reads nothing of it");
    }

    @Test
    void theSameMembersInAnyOrderAreOneConversationAndADifferentSetIsAnother(@TempDir Path temp) throws Exception {
        assertEquals(ConversationMessage.conversationOf(List.of(A, B, C)), ConversationMessage.conversationOf(List.of(C, A, B)));
        assertNotEquals(ConversationMessage.conversationOf(List.of(A, B)), ConversationMessage.conversationOf(List.of(A, B, C)));

        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        say(store, A, List.of(A, B), "one");
        say(store, B, List.of(B, A), "two");
        say(store, A, List.of(A, B, C), "three");

        List<ConversationMessage> messages = store.conversationProjection().messages();
        assertEquals(messages.get(0).conversationId(), messages.get(1).conversationId());
        assertNotEquals(messages.get(0).conversationId(), messages.get(2).conversationId());
        assertEquals(List.of(A, B).stream().sorted().toList(), store.conversationProjection().members(messages.get(0).conversationId()));
    }

    @Test
    void aDeliveryMarkHidesWhatItCoversAndNothingNewerAndNeverMovesBack(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        long first = say(store, A, List.of(A, B), "first");
        long second = say(store, A, List.of(A, B), "second");
        long third = say(store, A, List.of(A, B), "third");

        mark(store, B, second);
        assertEquals(List.of("third"), store.conversationProjection().unread(B).stream().map(ConversationMessage::text).toList());

        mark(store, B, first);
        assertEquals(List.of("third"), store.conversationProjection().unread(B).stream().map(ConversationMessage::text).toList(), "an older mark changes nothing");

        mark(store, B, third);
        assertTrue(store.conversationProjection().unread(B).isEmpty());
    }

    @Test
    void aDeliveryMarkAtOrBeyondItsOwnLogPositionIsRefused(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        long sent = say(store, A, List.of(A, B), "first");

        assertThrows(IOException.class, () -> mark(store, B, sent + 1), "its own position");
        assertThrows(IOException.class, () -> mark(store, B, sent + 100), "a position not yet in the log");
        assertThrows(IOException.class, () -> mark(store, B, -1));
        assertEquals(1, store.conversationProjection().unread(B).size());
    }

    @Test
    void aDuplicateMessageIdIsRefusedAndAMessageWithoutItsSenderAmongTheMembersCannotBeEncoded(@TempDir Path temp) throws Exception {
        PredictionEventStore store = new PredictionEventStore(temp, UUID.randomUUID());
        UUID id = UUID.randomUUID();
        say(store, id, A, List.of(A, B), "once");

        assertThrows(IOException.class, () -> say(store, id, B, List.of(A, B), "again"));
        assertEquals(1, store.conversationProjection().messages().size());

        assertThrows(CoordinationArgumentException.class, () -> ConversationMessage.encodeSent(UUID.randomUUID(), C, List.of(A, B), "not a member"));
        assertThrows(CoordinationArgumentException.class, () -> ConversationMessage.encodeSent(UUID.randomUUID(), A, List.of(A), "alone"));
        assertThrows(CoordinationArgumentException.class, () -> ConversationMessage.encodeSent(UUID.randomUUID(), A, List.of(A, B), "  "));
    }

    @Test
    void replayingTheLogGivesTheSameMessagesOrderAndMarks(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        PredictionEventStore store = new PredictionEventStore(temp, project);
        say(store, A, List.of(A, B), "one");
        long two = say(store, B, List.of(A, B, C), "two");
        say(store, A, List.of(A, B), "three");
        mark(store, A, two);

        ConversationProjection replayed = new PredictionEventStore(temp, project).conversationProjection();

        assertEquals(store.conversationProjection().messages(), replayed.messages());
        assertEquals(store.conversationProjection().unread(A), replayed.unread(A));
        assertEquals(store.conversationProjection().unread(B), replayed.unread(B));
        assertEquals(store.conversationProjection().unread(C), replayed.unread(C));
        assertEquals(List.of("one", "three"), replayed.unread(B).stream().map(ConversationMessage::text).toList());
    }
}
