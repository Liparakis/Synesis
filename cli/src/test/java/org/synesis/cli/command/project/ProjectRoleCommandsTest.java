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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.SynesisCli;
import org.synesis.cli.command.coordination.CoordinationStatusCommand;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.membership.RecoveryKey;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

import picocli.CommandLine;

/**
 * The commands that give this computer a role in a project that runs on several computers (ADR-0098 steps 2 and 6): what
 * they write to {@code replication.properties} and to the membership entries of the log, what they print, and what they
 * refuse. Since step 6 who belongs is in the log; the settings file only says {@code role=shared} and the code remote.
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

    private MembershipProjection membership() throws Exception {
        return location.openCoordinationStore().membershipProjection();
    }

    private List<PredictionEventType> entries() throws Exception {
        return location.openCoordinationStore().events().stream().map(PredictionEvent::type).toList();
    }

    private static final Pattern WORDS = Pattern.compile("RECOVERY_WORDS=(.*)");

    /** The 24 words a run printed, or null when it printed none. */
    private static String words(Run run) {
        return run.stdout().stream().map(WORDS::matcher).filter(java.util.regex.Matcher::matches).map(matcher -> matcher.group(1)).findFirst().orElse(null);
    }

    private Run status() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        int exit = new CommandLine(new CoordinationStatusCommand(runtime)).execute("--project", root.toString());
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
    void shareMakesThisComputerTheOwnerWithARecoveryKeyShownOnceAsTwentyFourWordsAndStopsTheRuntime() throws Exception {
        Run shared = share("--code-remote", "origin");

        assertEquals(ExitCodes.OK, shared.exit(), shared.toString());
        assertEquals(List.of("ROLE=shared", "NODE_ID=" + ownNode(), "OWNER_NODE_ID=" + ownNode(), "CODE_REMOTE=origin", "MEMBERS=1"),
                shared.stdout().subList(0, 5), shared.toString());
        String printed = words(shared);
        assertEquals(24, printed.split(" ").length, shared.toString());
        assertTrue(shared.stdout().stream().anyMatch(line -> line.startsWith("RECOVERY_WORDS_NOTE=")), shared.toString());
        assertEquals("RUNTIME_STOPPED=true", shared.stdout().getLast());
        assertEquals(ReplicationSettings.sharedProject("origin"), settings());
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED), entries());
        MembershipProjection membership = membership();
        assertTrue(membership.shared());
        assertEquals(ownNode(), membership.owner().orElseThrow(), "the owner is this computer's node");
        assertEquals(Set.of(ownNode()), membership.members());
        assertTrue(java.util.Arrays.equals(RecoveryKey.fromWords(printed).publicKey(), membership.recoveryPublicKey()), "the entry names the key those words give");
        assertEquals(1, stopped.size());
        assertTrue(Files.isSameFile(root, stopped.getFirst()));
    }

    @Test
    void shareAgainOnTheOwnersComputerShowsNoWordsAndSaysTheKeyIsAlreadySet() throws Exception {
        Run first = share("--code-remote", "origin");
        byte[] key = membership().recoveryPublicKey();

        Run again = share("--code-remote", "origin");

        assertEquals(ExitCodes.OK, again.exit(), again.toString());
        assertEquals(null, words(again), "the words are shown once and stored nowhere: " + again);
        assertTrue(again.stdout().contains("RECOVERY_KEY=ALREADY_SET"), again.toString());
        assertTrue(first.stdout().stream().noneMatch(line -> line.equals("RECOVERY_KEY=ALREADY_SET")));
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED), entries(), "nothing was written the second time");
        assertTrue(java.util.Arrays.equals(key, membership().recoveryPublicKey()));
    }

    @Test
    void shareWithNewRecoveryKeyReplacesTheKeyByAnOwnerChangedEntryAndShowsTheNewWords() throws Exception {
        Run first = share();
        String firstWords = words(first);

        Run replaced = share("--new-recovery-key");

        assertEquals(ExitCodes.OK, replaced.exit(), replaced.toString());
        String newWords = words(replaced);
        assertEquals(24, newWords.split(" ").length, replaced.toString());
        assertTrue(!newWords.equals(firstWords));
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.OWNER_CHANGED), entries());
        MembershipProjection membership = membership();
        assertEquals(ownNode(), membership.owner().orElseThrow(), "the owner stays this computer");
        assertTrue(java.util.Arrays.equals(RecoveryKey.fromWords(newWords).publicKey(), membership.recoveryPublicKey()));
        assertTrue(!java.util.Arrays.equals(RecoveryKey.fromWords(firstWords).publicKey(), membership.recoveryPublicKey()), "the old words no longer recover the project");
        assertEquals(1, membership.revision(), "one owner statement so far");
    }

    @Test
    void shareOnAStepFiveLeaderFileMigratesItsMembersIntoTheLogAndNamesARecoveryKeyBecauseTheMigrationHadNone() throws Exception {
        new ReplicationSettings(null, Set.of(peer), "origin").write(coordination());

        Run shared = share();

        assertEquals(ExitCodes.OK, shared.exit(), shared.toString());
        assertEquals("ROLE=shared", shared.stdout().getFirst());
        assertTrue(shared.stdout().contains("MEMBERS=2"), shared.toString());
        assertTrue(shared.stdout().contains("CODE_REMOTE=origin"), "the code remote of the old file is kept: " + shared);
        assertEquals(ReplicationSettings.sharedProject("origin"), settings(), "the step-5 file is rewritten as role=shared");
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED, PredictionEventType.JOIN_ANSWERED, PredictionEventType.OWNER_CHANGED),
                entries(), "the migration shares the project with no key, accepts the member, and the share names a key");
        assertEquals(Set.of(ownNode(), peer), membership().members());
        assertTrue(words(shared) != null && membership().recoveryPublicKey().length > 0, "a migrated project had no recovery key, so the words are shown: " + shared);
    }

    @Test
    void shareRefusesARemoteTheCloneDoesNotHaveAndWritesNothing() throws Exception {
        Run shared = share("--code-remote", "nowhere");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, shared.exit());
        assertEquals(List.of("ERROR=CODE_REMOTE_UNKNOWN"), shared.stderr());
        assertEquals(null, settings());
        assertEquals(List.of(), entries());
        assertEquals(List.of(), stopped);
    }

    @Test
    void shareRefusesAComputerThatFollowsAnother() throws Exception {
        ReplicationSettings.member(peer).write(coordination());

        Run shared = share("--code-remote", "origin");

        assertEquals(List.of("ERROR=ALREADY_A_MEMBER"), shared.stderr(), shared.toString());
        assertEquals(ReplicationSettings.member(peer), settings());
        assertEquals(List.of(), entries());
    }

    @Test
    void shareRefusesNotTheOwnerOnASharedProjectAnotherComputerOwnsAndWritesNothing() throws Exception {
        NodeIdentity other = NodeIdentity.generate();
        new MembershipService(location.openCoordinationStore(), other).share(java.util.Optional.empty());
        ReplicationSettings.sharedProject(null).write(coordination());

        Run shared = share();

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, shared.exit(), shared.toString());
        assertEquals(List.of("ERROR=NOT_THE_OWNER"), shared.stderr(), shared.toString());
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED), entries(), "no entry of this computer was added");
        assertEquals(List.of(), stopped);
    }

    @Test
    void addMemberLetsAComputerInByJoinRequestedAndJoinAnsweredWithoutStoppingTheRuntimeAndRemoveMemberWritesMemberRemoved() throws Exception {
        share("--code-remote", "origin");
        stopped.clear();

        Run added = add(peer);
        assertEquals(List.of("ROLE=shared", "MEMBER_NODE_ID=" + peer, "CHANGED=true", "MEMBERS=2"), added.stdout(), added.toString());
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED, PredictionEventType.JOIN_ANSWERED), entries());
        assertEquals(Set.of(ownNode(), peer), membership().members());
        assertEquals(List.of(), membership().openRequests(), "asked and answered at once");
        assertEquals(Boolean.TRUE, membership().requests().getFirst().accepted().orElseThrow());
        assertEquals(ReplicationSettings.sharedProject("origin"), settings(), "the settings file does not list members any more");
        assertEquals(List.of("CHANGED=false"), add(peer).stdout().stream().filter(line -> line.startsWith("CHANGED")).toList());
        assertEquals(3, entries().size(), "adding a member again writes nothing");

        ReplicationPeers.rememberHosted(coordination(), 40000, peer);
        ReplicationPeers.rememberDialed(coordination(), peer, "synesis://join/SLB3-x");
        Run removed = remove(peer);
        assertEquals(List.of("ROLE=shared", "MEMBER_NODE_ID=" + peer, "CHANGED=true", "MEMBERS=1"), removed.stdout(), removed.toString());
        assertEquals(PredictionEventType.MEMBER_REMOVED, entries().getLast());
        assertEquals(Set.of(ownNode()), membership().members());
        assertTrue(membership().removedHead(peer).isPresent(), "the removal names the head of the member's log");
        assertEquals(ReplicationPeers.none(), ReplicationPeers.read(coordination()), "a removed member must not be dialed or admitted after a restart");
        assertEquals(List.of("ERROR=NOT_A_MEMBER"), remove(peer).stderr());
        assertEquals(List.of(), stopped);
    }

    @Test
    void addMemberOnAStepFiveLeaderFileMigratesFirstAndRefusesWhatIsNotAMemberOrNotACommand() throws Exception {
        assertEquals(List.of("ERROR=NOT_A_LEADER"), add(peer).stderr());
        share("--code-remote", "origin");

        assertEquals(List.of("ERROR=INVALID_NODE_ID"), add("not a node").stderr());
        assertEquals(List.of("ERROR=MEMBER_IS_THIS_COMPUTER"), add(ownNode()).stderr());
        assertEquals(Set.of(ownNode()), membership().members());

        ReplicationSettings.member(peer).write(coordination());
        assertEquals(List.of("ERROR=ALREADY_A_MEMBER"), add(NodeIdentity.generate().nodeId()).stderr());
    }

    @Test
    void shareAndFollowNeedNoRemoteBecauseCodeMovesOverLink() throws Exception {
        Run followed = follow("--leader", peer);

        assertEquals(ExitCodes.OK, followed.exit(), followed.toString());
        assertEquals(ReplicationSettings.member(peer), settings());

        Files.delete(coordination().resolve(ReplicationSettings.FILE));
        Run shared = share();

        assertEquals(ExitCodes.OK, shared.exit(), shared.toString());
        assertTrue(shared.stdout().contains("CODE_REMOTE="), shared.toString());
        assertEquals(ReplicationSettings.sharedProject(null), settings());
    }

    @Test
    void followRefusesAComputerThatSharedItsProjectBecauseTheShareIsAnEntryOfItsOwnLog() throws Exception {
        share();

        Files.delete(coordination().resolve(ReplicationSettings.FILE));
        Run followed = follow("--leader", peer);

        assertEquals(List.of("ERROR=LOG_NOT_EMPTY"), followed.stderr(), "the step-5 rule that an own entry would never be on the leader's log still holds: " + followed);
    }

    @Test
    void coordinationStatusOfASharedProjectShowsOwnerRecoveryKeyMembersAndOpenJoinRequests() throws Exception {
        share();
        add(peer);
        String stranger = NodeIdentity.generate().nodeId();
        new MembershipService(location.openCoordinationStore(), location.loadOrCreateIdentity()).requestJoin(stranger);

        Run status = status();

        assertEquals(ExitCodes.OK, status.exit(), status.toString());
        List<String> lines = status.stdout();
        assertTrue(lines.contains("REPLICATION_ROLE=shared"), status.toString());
        assertTrue(lines.contains("REPLICATION_OWNER=" + ownNode()), status.toString());
        assertTrue(lines.contains("REPLICATION_RECOVERY_KEY=SET"), status.toString());
        // No coordination server runs in this test, so no connection is recorded for anyone.
        assertEquals(Set.of(ownNode(), peer), lines.stream().filter(line -> line.startsWith("REPLICATION_MEMBER="))
                .map(line -> line.substring("REPLICATION_MEMBER=".length()).split(" ")[0]).collect(Collectors.toSet()), status.toString());
        // This computer is named as itself; another member shows its connection, here none with no server running.
        assertTrue(lines.contains("REPLICATION_MEMBER=" + ownNode() + " STATE=THIS_COMPUTER"), status.toString());
        assertTrue(lines.contains("REPLICATION_MEMBER=" + peer + " STATE=NO_COORDINATION_SERVER"), status.toString());
        assertTrue(lines.contains("JOIN_REQUEST=" + stranger + " inviter=" + ownNode()), status.toString());
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("REPLICATION_LEADER=") || line.startsWith("REPLICATION_STATE=")), "no step-5 lines for a shared project");
    }

    @Test
    void coordinationStatusSaysNoneWhenTheSharedProjectHasNoRecoveryKey() throws Exception {
        new MembershipService(location.openCoordinationStore(), location.loadOrCreateIdentity()).share(java.util.Optional.empty());
        ReplicationSettings.sharedProject(null).write(coordination());

        Run status = status();

        assertEquals(ExitCodes.OK, status.exit(), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_RECOVERY_KEY=NONE"), status.toString());
        assertTrue(status.stdout().contains("REPLICATION_OWNER=" + ownNode()), status.toString());
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
        assertEquals(Set.of(ownNode(), peer), membership().members(), "a step-5 leader file migrated, and the member was let in by entries");
        assertEquals(ReplicationSettings.sharedProject(null), settings());
    }
}
