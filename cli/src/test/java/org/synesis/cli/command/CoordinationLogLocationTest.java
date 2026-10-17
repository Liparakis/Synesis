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
import org.synesis.coordination.persistence.ReplicationSettings;

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
    void aMemberComputerReportsItsRoleAndAnAgentProcessOpeningItsLogCannotAppendLocally() throws Exception {
        ReplicationSettings.member("sl1-leader").write(location.coordinationDirectory());

        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);

        assertEquals(0, status.exit(), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_ROLE=member"), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_LEADER=sl1-leader"), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_STATE=NO_COORDINATION_SERVER"), status.toString());
        // The path every agent's process takes: the project location opens the log, the role comes from the file.
        java.io.IOException refusal = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, this::agentAppends);
        assertEquals("LEADER_UNREACHABLE", refusal.getMessage());
        assertEquals(0, PredictionEventStore.recordedEvents(location.coordinationDirectory()));
    }

    @Test
    void aMemberComputersServerRelaysOnLoopbackWhileItRunsAndLeavesNothingBehind() throws Exception {
        Path coordination = location.coordinationDirectory();
        ReplicationSettings.member("sl1-leader").write(coordination);
        java.util.concurrent.CompletableFuture<Result> served = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> CliHarness.run(tmp.resolve("cli-profile"), "coordination", "serve", "--project", project, "--port", "0",
                        "--duration-seconds", "6"));
        Path endpointFile = coordination.resolve("replication.endpoint");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(endpointFile) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(Files.exists(endpointFile), "the member server must record where it listens");

        // An agent's process with no Link session behind the server is told, at once, that the leader is unreachable.
        java.io.IOException refusal = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, this::agentAppends);
        assertEquals("LEADER_UNREACHABLE", refusal.getMessage());
        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        assertTrue(status.stdout().contains("REPLICATION_STATE=DISCONNECTED"), status.toString());

        assertEquals(0, served.get(30, java.util.concurrent.TimeUnit.SECONDS).exit());
        assertTrue(Files.notExists(endpointFile));
        assertTrue(Files.notExists(coordination.resolve("replication.status")));
        assertEquals(0, PredictionEventStore.recordedEvents(coordination));
    }

    @Test
    void aLeaderComputerReportsEachMembersConnectionWhileItsServerRunsAndLeavesNothingBehind() throws Exception {
        Path coordination = location.coordinationDirectory();
        ReplicationSettings.leader(java.util.Set.of("sl1-member-b", "sl1-member-a")).write(coordination);
        Result stopped = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        assertEquals(0, stopped.exit(), stopped.toString());
        assertTrue(stopped.stdout().contains("REPLICATION_ROLE=leader"), stopped.toString());
        assertEquals(List.of("REPLICATION_MEMBER=sl1-member-a STATE=NO_COORDINATION_SERVER",
                "REPLICATION_MEMBER=sl1-member-b STATE=NO_COORDINATION_SERVER"), memberLines(stopped), stopped.toString());

        java.util.concurrent.CompletableFuture<Result> served = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> CliHarness.run(tmp.resolve("cli-profile"), "coordination", "serve", "--project", project, "--port", "0",
                        "--duration-seconds", "6"));
        Path membersFile = coordination.resolve("replication.members");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(membersFile) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(Files.exists(membersFile), "the leader server must record its members' connections");

        Result running = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        List<String> members = memberLines(running);
        assertEquals(2, members.size(), running.toString());
        assertTrue(members.get(0).matches("REPLICATION_MEMBER=sl1-member-a STATE=DISCONNECTED since=\\S+Z"), running.toString());
        assertTrue(members.get(1).matches("REPLICATION_MEMBER=sl1-member-b STATE=DISCONNECTED since=\\S+Z"), running.toString());

        assertEquals(0, served.get(30, java.util.concurrent.TimeUnit.SECONDS).exit());
        assertTrue(Files.notExists(membersFile));
    }

    private static List<String> memberLines(Result status) {
        return status.stdout().stream().filter(line -> line.startsWith("REPLICATION_MEMBER=")).toList();
    }

    @Test
    void aReplicationFileThatCannotBeReadFailsStatusAndRefusesAgentAppends() throws Exception {
        Files.writeString(Files.createDirectories(location.coordinationDirectory()).resolve(ReplicationSettings.FILE), "role=boss\n");

        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);

        assertEquals(10, status.exit(), status.toString());
        assertTrue(status.stderr().contains("COORDINATION_ERROR=REPLICATION_ROLE_INVALID"), status.toString());
        java.io.IOException refusal = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, this::agentAppends);
        assertEquals("REPLICATION_ROLE_INVALID", refusal.getMessage());
    }

    @Test
    void anExplicitDataDirectoryStillWins() {
        Path elsewhere = tmp.resolve("elsewhere");

        assertEquals(elsewhere.toAbsolutePath().normalize(), CoordinationCliSupport.data(location, elsewhere));
    }
}
