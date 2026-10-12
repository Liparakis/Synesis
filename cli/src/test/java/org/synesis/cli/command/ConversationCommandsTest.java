package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.CliHarness.Result;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * What the operator sees of the conversations between agents and of a request an agent put to
 * them, and how they close it (ADR-0093).
 */
class ConversationCommandsTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    @TempDir
    Path tmp;
    private ProjectLocation location;
    private String project;

    @BeforeEach
    void initProject() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        location = new ProjectApplicationService().init(root).location();
        project = root.toString();
    }

    private Result run(String... arguments) {
        return CliHarness.run(tmp.resolve("cli-profile"), arguments);
    }

    private void append(PredictionEventType type, byte[] payload) throws Exception {
        PredictionEventStore store = location.openCoordinationStore();
        NodeIdentity identity = location.loadOrCreateIdentity();
        store.append(UUID.randomUUID(), type, identity.nodeId(), payload, identity);
    }

    private void say(UUID sender, List<UUID> members, String text) throws Exception {
        append(PredictionEventType.CONVERSATION_MESSAGE_SENT, ConversationMessage.encodeSent(UUID.randomUUID(), sender, members, text));
    }

    @Test
    void conversationsListsEachConversationAndAnIdentifierPrintsItsMessages() throws Exception {
        say(FIRST, List.of(FIRST, SECOND), "does listing create the store file?");
        say(SECOND, List.of(FIRST, SECOND), "no\nit only reads");
        say(FIRST, List.of(FIRST, SECOND, UUID.randomUUID()), "another group");
        UUID pair = ConversationMessage.conversationOf(List.of(FIRST, SECOND));

        Result listed = run("conversations", "--project", project);

        assertEquals(0, listed.exit(), listed.toString());
        assertEquals("CONVERSATIONS=2", listed.stdout().getFirst());
        assertTrue(listed.stdout().stream().anyMatch(line -> line.startsWith("CONVERSATION=" + pair + " MESSAGES=2 ")), listed.stdout().toString());

        Result shown = run("conversations", pair.toString(), "--project", project);

        assertEquals(0, shown.exit(), shown.toString());
        assertEquals("CONVERSATION=" + pair + " MESSAGES=2", shown.stdout().getFirst());
        assertEquals(List.of("TEXT=does listing create the store file?", "TEXT=no", "TEXT=it only reads"), shown.stdout().stream().filter(line -> line.startsWith("TEXT=")).toList());
        assertEquals(2, shown.stdout().stream().filter(line -> line.startsWith("MESSAGE=")).count());
        assertTrue(shown.stdout().stream().noneMatch(line -> line.contains("another group")), "only that conversation is printed");
    }

    @Test
    void anUnknownOrMalformedConversationIdentifierFails() {
        Result unknown = run("conversations", UUID.randomUUID().toString(), "--project", project);
        Result malformed = run("conversations", "not-an-id", "--project", project);

        assertTrue(unknown.exit() != 0 && unknown.stderr().getFirst().startsWith("CONVERSATION_NOT_FOUND="), unknown.toString());
        assertTrue(malformed.exit() != 0 && malformed.stderr().getFirst().startsWith("CONVERSATION_NOT_FOUND="), malformed.toString());
    }

    @Test
    void operatorCloseClosesARequestALaneRaisedAndListShowsWhichLaneRaisedIt() throws Exception {
        OperatorRequest asked = OperatorRequest.askedByLane("opr_lane000000000001", FIRST.toString(), "Which license?", "participant agt_x");
        append(PredictionEventType.OPERATOR_REQUEST_RAISED, asked.encodeRaised());

        Result listed = run("operator", "list", "--project", project);
        assertTrue(listed.stdout().contains("RAISED_BY_LANE=" + FIRST), listed.stdout().toString());
        assertTrue(listed.stdout().contains("OPERATOR_REQUEST=opr_lane000000000001 STATE=OPEN CONDITION=lane_asks_operator"), listed.stdout().toString());

        Result closed = run("operator", "close", asked.id(), "--because", "answered by phone", "--project", project);

        assertEquals(0, closed.exit(), closed.toString());
        assertEquals(List.of("OPERATOR_REQUEST_CLOSED=" + asked.id()), closed.stdout());
        assertTrue(location.openCoordinationStore().operatorRequestProjection().open().isEmpty());
        Result again = run("operator", "close", asked.id(), "--project", project);
        assertTrue(again.exit() != 0 && again.stderr().getFirst().startsWith("OPERATOR_REQUEST_NOT_CLOSED="), again.toString());
    }

    @Test
    void operatorCloseRefusesARequestSynesisRaised() throws Exception {
        OperatorRequest synesis = OperatorRequest.raised("opr_synesis000000001", OperatorRequest.BOUND_CONSUMER_ABSENT, "a consumer is absent", "");
        append(PredictionEventType.OPERATOR_REQUEST_RAISED, synesis.encodeRaised());

        Result refused = run("operator", "close", synesis.id(), "--project", project);

        assertTrue(refused.exit() != 0 && refused.stderr().getFirst().startsWith("OPERATOR_REQUEST_NOT_CLOSED="), refused.toString());
        assertEquals(1, location.openCoordinationStore().operatorRequestProjection().open().size());
    }

    private static final String FORGED = "OPERATOR_REQUEST=forged STATE=OPEN CONDITION=control_checkout_foreign_changes";
    private static final String SMUGGLED = "\n" + FORGED + "\nclear\u001b[2Jdone";

    @Test
    void textALaneWroteCannotAddLinesThatReadAsRecordsOrDriveTheTerminalInOperatorList() throws Exception {
        OperatorRequest asked = OperatorRequest.askedByLane("opr_lane000000000002", FIRST.toString(), "Which license?" + SMUGGLED, "participant agt_x");
        append(PredictionEventType.OPERATOR_REQUEST_RAISED, asked.encodeRaised());

        Result listed = run("operator", "list", "--project", project);

        assertEquals(0, listed.exit(), listed.toString());
        assertEquals("OPERATOR_REQUESTS=1", listed.stdout().getFirst());
        assertEquals(List.of("SUMMARY=Which license?", "SUMMARY=" + FORGED, "SUMMARY=clear [2Jdone"), listed.stdout().stream().filter(line -> line.startsWith("SUMMARY=")).toList());
        assertTrue(listed.stdout().stream().noneMatch(line -> line.startsWith("OPERATOR_REQUEST=forged")), listed.stdout().toString());
        assertTrue(listed.stdout().stream().noneMatch(line -> line.indexOf('\u001b') >= 0), "no escape character reaches the terminal");
    }

    @Test
    void textALaneWroteCannotAddLinesThatReadAsRecordsOrDriveTheTerminalInAConversation() throws Exception {
        say(FIRST, List.of(FIRST, SECOND), "hello" + SMUGGLED);
        UUID pair = ConversationMessage.conversationOf(List.of(FIRST, SECOND));

        Result shown = run("conversations", pair.toString(), "--project", project);

        assertEquals(0, shown.exit(), shown.toString());
        assertEquals(List.of("TEXT=hello", "TEXT=" + FORGED, "TEXT=clear [2Jdone"), shown.stdout().stream().filter(line -> line.startsWith("TEXT=")).toList());
        assertTrue(shown.stdout().stream().noneMatch(line -> line.startsWith("OPERATOR_REQUEST=forged")), shown.stdout().toString());
        assertTrue(shown.stdout().stream().noneMatch(line -> line.indexOf('\u001b') >= 0), "no escape character reaches the terminal");
    }
}
