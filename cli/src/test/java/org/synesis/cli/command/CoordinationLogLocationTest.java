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
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Agents and the coordination server run as separate processes on one computer. Started the default
 * way, the server must read and append the log the agents write, or the dashboard shows a log no
 * agent writes.
 */
class CoordinationLogLocationTest {

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

    private void agentAppends() throws Exception {
        UUID sender = UUID.randomUUID();
        NodeIdentity identity = location.loadOrCreateIdentity();
        location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGE_SENT, identity.nodeId(),
                ConversationMessage.encodeSent(UUID.randomUUID(), sender, List.of(sender, UUID.randomUUID()), "hello"), identity);
    }

    @Test
    void theServerStartedByDefaultServesTheEventsAgentsAppended() throws Exception {
        agentAppends();

        CoordinationService server = new CoordinationService(
                new PredictionEventStore(CoordinationCliSupport.data(location, null), location.projectId()), NodeIdentity.generate());

        assertEquals(1, server.headSequence());
    }

    @Test
    void coordinationStatusReportsTheEventsAgentsAppended() throws Exception {
        agentAppends();

        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);

        assertEquals(0, status.exit(), status.toString());
        assertTrue(status.stdout().contains("PROJECT_SEQUENCE=1"), status.toString());
    }

    @Test
    void anExplicitDataDirectoryStillWins() {
        Path elsewhere = tmp.resolve("elsewhere");

        assertEquals(elsewhere.toAbsolutePath().normalize(), CoordinationCliSupport.data(location, elsewhere));
    }
}
