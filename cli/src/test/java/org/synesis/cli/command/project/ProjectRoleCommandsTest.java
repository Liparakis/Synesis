package org.synesis.cli.command.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

import picocli.CommandLine;

/**
 * The commands that give this computer a role in a project that runs on several computers (ADR-0098 step 2): what
 * they write to {@code replication.properties}, what they print, and what they refuse.
 */
class ProjectRoleCommandsTest {

    @TempDir
    Path tmp;
    private Path root;
    private ProjectLocation location;
    private final List<Path> stopped = new ArrayList<>();
    private String peer;

    private record Run(int exit, List<String> stdout, List<String> stderr) {

        @Override
        public String toString() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    @BeforeEach
    void project() throws Exception {
        peer = NodeIdentity.generate().nodeId();
        root = Files.createDirectories(tmp.resolve("project"));
        location = new ProjectApplicationService().init(root).location();
        // Git comes after the project so that no baseline commit is needed; the clone only needs a remote to name.
        GitProcessRunner.run(root, "init", "-q");
        GitProcessRunner.run(root, "remote", "add", "origin", "https://example.invalid/project.git");
    }

    private Path coordination() {
        return location.coordinationDirectory();
    }

    private ReplicationSettings settings() throws Exception {
        return ReplicationSettings.read(coordination()).orElse(null);
    }

    private String ownNode() throws Exception {
        return location.loadOrCreateIdentity().nodeId();
    }

    private Run run(Function<CliRuntime, Callable<Integer>> command, String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        List<String> all = new ArrayList<>(List.of("--project", root.toString()));
        all.addAll(List.of(arguments));
        int exit = new CommandLine(command.apply(runtime)).execute(all.toArray(String[]::new));
        return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private Run share(String... arguments) {
        return run(runtime -> new ProjectShareCommand(runtime, project -> stopped.add(project)), arguments);
    }

    private Run follow(String... arguments) {
        return run(runtime -> new ProjectFollowCommand(runtime, project -> stopped.add(project)), arguments);
    }

    private Run add(String node) {
        return run(ProjectAddMemberCommand::new, node);
    }

    private Run remove(String node) {
        return run(ProjectRemoveMemberCommand::new, node);
    }

    @Test
    void shareMakesThisComputerTheLeaderNamesTheRemoteAndStopsTheRuntimeSoItStartsWithTheRole() throws Exception {
        Run shared = share("--code-remote", "origin");

        assertEquals(ExitCodes.OK, shared.exit(), shared.toString());
        assertEquals(List.of("ROLE=leader", "NODE_ID=" + ownNode(), "CODE_REMOTE=origin", "MEMBERS=0", "RUNTIME_STOPPED=true"), shared.stdout());
        assertEquals(new ReplicationSettings(null, Set.of(), "origin"), settings());
        assertEquals(1, stopped.size());
        assertTrue(Files.isSameFile(root, stopped.getFirst()));
    }

    @Test
    void shareKeepsTheMembersOfALeaderThatSharesAgain() throws Exception {
        share("--code-remote", "origin");
        assertEquals(ExitCodes.OK, add(peer).exit());

        Run again = share("--code-remote", "origin");

        assertEquals(ExitCodes.OK, again.exit(), again.toString());
        assertTrue(again.stdout().contains("MEMBERS=1"), again.toString());
        assertEquals(Set.of(peer), settings().members());
    }

    @Test
    void shareRefusesARemoteTheCloneDoesNotHaveAndWritesNothing() throws Exception {
        Run shared = share("--code-remote", "nowhere");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, shared.exit());
        assertEquals(List.of("ERROR=CODE_REMOTE_UNKNOWN"), shared.stderr());
        assertEquals(null, settings());
        assertEquals(List.of(), stopped);
    }

    @Test
    void shareRefusesAComputerThatFollowsAnother() throws Exception {
        ReplicationSettings.member(peer).write(coordination());

        Run shared = share("--code-remote", "origin");

        assertEquals(List.of("ERROR=ALREADY_A_MEMBER"), shared.stderr(), shared.toString());
        assertEquals(ReplicationSettings.member(peer), settings());
    }

    @Test
    void addAndRemoveMemberChangeTheListWithoutStoppingTheRuntimeAndRefuseWhatIsNotAMember() throws Exception {
        share("--code-remote", "origin");
        stopped.clear();

        Run added = add(peer);
        assertEquals(List.of("ROLE=leader", "MEMBER_NODE_ID=" + peer, "CHANGED=true", "MEMBERS=1"), added.stdout(), added.toString());
        assertEquals(Set.of(peer), settings().members());
        assertEquals("origin", settings().codeRemote());
        assertEquals(List.of("CHANGED=false"), add(peer).stdout().stream().filter(line -> line.startsWith("CHANGED")).toList());

        ReplicationPeers.rememberHosted(coordination(), 40000, peer);
        ReplicationPeers.rememberDialed(coordination(), peer, "synesis://join/SLB3-x");
        Run removed = remove(peer);
        assertEquals(List.of("ROLE=leader", "MEMBER_NODE_ID=" + peer, "CHANGED=true", "MEMBERS=0"), removed.stdout(), removed.toString());
        assertEquals(Set.of(), settings().members());
        assertEquals(ReplicationPeers.none(), ReplicationPeers.read(coordination()), "a removed member must not be dialed or admitted after a restart");
        assertEquals(List.of("ERROR=NOT_A_MEMBER"), remove(peer).stderr());
        assertEquals(List.of(), stopped);
    }

    @Test
    void addMemberRefusesANodeIdThatIsNotOneThisComputerAndAComputerThatIsNotTheLeader() throws Exception {
        assertEquals(List.of("ERROR=NOT_A_LEADER"), add(peer).stderr());
        share("--code-remote", "origin");

        assertEquals(List.of("ERROR=INVALID_NODE_ID"), add("not a node").stderr());
        assertEquals(List.of("ERROR=MEMBER_IS_THIS_COMPUTER"), add(ownNode()).stderr());
        assertEquals(Set.of(), settings().members());

        ReplicationSettings.member(peer).write(coordination());
        assertEquals(List.of("ERROR=ALREADY_A_MEMBER"), add(NodeIdentity.generate().nodeId()).stderr());
    }

    @Test
    void shareAndFollowNeedNoRemoteBecauseCodeMovesOverLink() throws Exception {
        Run shared = share();

        assertEquals(ExitCodes.OK, shared.exit(), shared.toString());
        assertTrue(shared.stdout().contains("CODE_REMOTE="), shared.toString());
        assertEquals(ReplicationSettings.leader(Set.of()), settings());

        Files.delete(coordination().resolve(ReplicationSettings.FILE));
        Run followed = follow("--leader", peer);

        assertEquals(ExitCodes.OK, followed.exit(), followed.toString());
        assertEquals(ReplicationSettings.member(peer), settings());
    }

    @Test
    void followMakesThisComputerAMemberOfTheLeaderWithTheRemoteAndStopsTheRuntime() throws Exception {
        String other = NodeIdentity.generate().nodeId();
        ReplicationPeers.rememberHosted(coordination(), 40000, other);
        ReplicationPeers.rememberDialed(coordination(), peer, "synesis://join/SLB3-x");
        Run followed = follow("--leader", peer, "--code-remote", "origin");

        assertEquals(ExitCodes.OK, followed.exit(), followed.toString());
        assertEquals(List.of("ROLE=member", "NODE_ID=" + ownNode(), "LEADER_NODE_ID=" + peer, "CODE_REMOTE=origin", "RUNTIME_STOPPED=true"), followed.stdout());
        assertEquals(ReplicationSettings.member(peer).withCodeRemote("origin"), settings());
        assertEquals(java.util.Map.of(peer, "synesis://join/SLB3-x"), ReplicationPeers.read(coordination()).dialed());
        assertEquals(java.util.Map.of(), ReplicationPeers.read(coordination()).hosted(), "only the leader may be resumed by a member");
        assertEquals(1, stopped.size());
    }

    @Test
    void followRefusesALogWithEntriesOfItsOwnBecauseTheyWouldNeverBeOnTheLeadersLog() throws Exception {
        Files.createDirectories(coordination().resolve("events"));
        Files.write(coordination().resolve("events/00000000000000000001.sce"), new byte[] {1});

        Run followed = follow("--leader", peer, "--code-remote", "origin");

        assertEquals(List.of("ERROR=LOG_NOT_EMPTY"), followed.stderr(), followed.toString());
        assertEquals(null, settings());
    }

    @Test
    void followRefusesItselfAnotherLeaderAndALeaderWithMembersButAllowsTheSameLeaderAgainOverAGrownLog() throws Exception {
        assertEquals(List.of("ERROR=INVALID_NODE_ID"), follow("--leader", "x", "--code-remote", "origin").stderr());
        assertEquals(List.of("ERROR=LEADER_IS_THIS_COMPUTER"), follow("--leader", ownNode(), "--code-remote", "origin").stderr());
        assertEquals(List.of("ERROR=CODE_REMOTE_UNKNOWN"), follow("--leader", peer, "--code-remote", "nowhere").stderr());

        assertEquals(ExitCodes.OK, follow("--leader", peer, "--code-remote", "origin").exit());
        // A member's log is the leader's copy, so entries are expected once it has pulled.
        Files.createDirectories(coordination().resolve("events"));
        Files.write(coordination().resolve("events/00000000000000000001.sce"), new byte[] {1});
        GitProcessRunner.run(root, "remote", "add", "mirror", "https://example.invalid/mirror.git");
        assertEquals(ExitCodes.OK, follow("--leader", peer, "--code-remote", "mirror").exit());
        assertEquals("mirror", settings().codeRemote());
        assertEquals(List.of("ERROR=FOLLOWING_ANOTHER_LEADER"), follow("--leader", NodeIdentity.generate().nodeId(), "--code-remote", "origin").stderr());

        ReplicationSettings.leader(Set.of(peer)).write(coordination());
        assertEquals(List.of("ERROR=ALREADY_A_LEADER"), follow("--leader", NodeIdentity.generate().nodeId(), "--code-remote", "origin").stderr());
    }

    @Test
    void theCommandsAreRegisteredUnderProjectInTheExecutableCli() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        ReplicationSettings.leader(Set.of()).write(coordination());

        int exit = SynesisCli.execute(new String[] {"project", "add-member", "--project", root.toString(), peer}, runtime);

        assertEquals(ExitCodes.OK, exit, err.toString(StandardCharsets.UTF_8));
        assertEquals(Set.of(peer), settings().members());
    }
}
