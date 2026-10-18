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
    void aMemberComputerReportsItsRoleAndAnAgentProcessOpeningItsLogAppendsToItsOwnLog() throws Exception {
        ReplicationSettings.member("sl1-leader").write(location.coordinationDirectory());

        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);

        assertEquals(0, status.exit(), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_ROLE=member"), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_LEADER=sl1-leader"), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_STATE=NO_COORDINATION_SERVER"), status.toString());
        // The path every agent's process takes: the project location opens the log, the role comes from the file. A member appends to its own log, connected or not (ADR-0098, step 4).
        agentAppends();
        assertEquals(1, PredictionEventStore.recordedEvents(location.coordinationDirectory()));
    }

    @Test
    void aMemberComputersServerRunsOnLoopbackAndAnAgentAppendsToItsOwnLogWithNoLeaderReachable() throws Exception {
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

        // An agent's process with no Link session behind the server still appends: its own log needs no leader.
        agentAppends();
        assertEquals(0, served.get(30, java.util.concurrent.TimeUnit.SECONDS).exit());
        assertTrue(Files.notExists(endpointFile));
        assertTrue(Files.notExists(coordination.resolve("replication.status")));
        assertEquals(1, PredictionEventStore.recordedEvents(coordination), "the entry is the member's own and stays");
        assertEquals("role=member\nleader=sl1-leader\n", Files.readString(coordination.resolve(ReplicationSettings.FILE)).replace("\r\n", "\n"),
                "a step-5 member that never dialed its leader keeps its file: the migration waits for a link");
    }

    /**
     * Step 6 replaced {@code MemberReplication}, which recorded {@code replication.status}, by the shared side, which
     * records {@code replication.members}. {@code coordination status} still reads the old file for a {@code role=member}
     * file, so a running server of a legacy member (set up by {@code project follow}) is reported as not running.
     */
    @Test
    void aLegacyMemberFileStatusShowsItsConnectionWhileTheServerRuns() throws Exception {
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
        Result status = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        assertTrue(status.stdout().contains("REPLICATION_STATE=DISCONNECTED"), status.toString());
        assertEquals(0, served.get(30, java.util.concurrent.TimeUnit.SECONDS).exit());
    }

    @Test
    void aStepFiveLeaderComputerIsMigratedWhenItsServerStartsAndReportsEachMembersConnectionWhileItRunsAndLeavesNothingBehind() throws Exception {
        Path coordination = location.coordinationDirectory();
        String own = location.loadOrCreateIdentity().nodeId();
        List<String> ids = java.util.stream.Stream.of(NodeIdentity.generate().nodeId(), NodeIdentity.generate().nodeId()).sorted().toList();
        String first = ids.get(0);
        String second = ids.get(1);
        ReplicationSettings.leader(java.util.Set.of(second, first)).write(coordination);
        Result stopped = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        assertEquals(0, stopped.exit(), stopped.toString());
        assertTrue(stopped.stdout().contains("REPLICATION_ROLE=leader"), stopped.toString());
        assertEquals(List.of("REPLICATION_MEMBER=" + first + " STATE=NO_COORDINATION_SERVER",
                "REPLICATION_MEMBER=" + second + " STATE=NO_COORDINATION_SERVER"), memberLines(stopped), stopped.toString());

        java.util.concurrent.CompletableFuture<Result> served = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> CliHarness.run(tmp.resolve("cli-profile"), "coordination", "serve", "--project", project, "--port", "0",
                        "--duration-seconds", "6"));
        Path membersFile = coordination.resolve("replication.members");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(membersFile) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(Files.exists(membersFile), "the server must record its members' connections");

        // The server moved the step-5 file into the log (ADR-0098 step 6): the members are read from there now, the owner among them.
        Result running = CliHarness.run(tmp.resolve("cli-profile"), "coordination", "status", "--project", project);
        assertTrue(running.stdout().contains("REPLICATION_ROLE=shared"), running.toString());
        assertTrue(running.stdout().contains("REPLICATION_OWNER=" + own), running.toString());
        List<String> members = memberLines(running);
        assertEquals(3, members.size(), running.toString());
        List<String> others = members.stream().filter(line -> !line.startsWith("REPLICATION_MEMBER=" + own)).sorted().toList();
        assertTrue(others.get(0).matches("REPLICATION_MEMBER=" + first + " STATE=DISCONNECTED since=\\S+Z"), running.toString());
        assertTrue(others.get(1).matches("REPLICATION_MEMBER=" + second + " STATE=DISCONNECTED since=\\S+Z"), running.toString());

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
