package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Lane;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.test.CodeLinkPair;
import org.synesis.workspace.test.TestGit;

/**
 * The leader's side of code exchange (ADR-0098 step 3): the commits a member's snapshot names are in the member's clone
 * and not in the leader's, and the leader's pass fetches them over Link from the member, recomputes what they change
 * and advances the control branch. A Git remote, when the settings name one, is a mirror: fetched from after Link and
 * given the control branch. A project with no replication settings never moves code.
 */
class CodeExchangeLeaderTest {

    private static final String MEMBER_NODE = "member-node";

    private CodeLinkPair link;

    @AfterEach
    void unlink() throws IOException {
        if (link != null) {
            link.close();
        }
    }

    /** A leader's project, a clone standing for the member's computer, and, with a mirror, a bare remote both use. */
    private static final class Computers {

        final Project leader;
        final Path remote;
        final Path member;
        final String branch;

        Computers(Path temp, boolean replicated, boolean mirror) throws Exception {
            Files.createDirectories(temp.resolve("leader"));
            leader = new Project(temp.resolve("leader"));
            remote = temp.resolve("code.git");
            TestGit.output(temp, "init", "--bare", remote.toString());
            leader.git("remote", "add", "origin", remote.toString());
            branch = leader.git("rev-parse", "--abbrev-ref", "HEAD");
            leader.git("push", "origin", "HEAD:refs/heads/" + branch);
            TestGit.run(remote, "symbolic-ref", "HEAD", "refs/heads/" + branch);
            member = temp.resolve("member");
            TestGit.run(temp, "clone", remote.toString(), member.toString());
            TestGit.run(member, "config", "user.name", "Member");
            TestGit.run(member, "config", "user.email", "member@example.invalid");
            Files.createDirectories(memberCoordination());
            if (replicated) {
                ReplicationSettings leaderSettings = ReplicationSettings.leader(Set.of());
                (mirror ? leaderSettings.withCodeRemote("origin") : leaderSettings).write(coordination());
            }
        }

        Path coordination() {
            return leader.root.resolve(".synesis/coordination");
        }

        Path memberCoordination() {
            return member.resolve(".synesis/coordination");
        }

        /** Joins the leader's and the member's coordination servers as their Link session would. */
        CodeLinkPair link() throws IOException {
            return new CodeLinkPair(leader.location.projectId(), leader.root, coordination(), leader.identity.nodeId(), member, memberCoordination(), MEMBER_NODE);
        }

        String remoteRefs() throws IOException {
            return TestGit.output(remote, "for-each-ref", "--format=%(refname) %(objectname)");
        }

        /**
         * What the member's computer does for a lane: commits {@code files} in its own clone and keeps the prepared and
         * snapshot refs there, and the entries naming them reach the leader's log. With {@code pushed} it also gives the
         * refs to the mirror. The leader's clone never holds the commit. {@code declared} is the member's claim of what
         * the commit changes.
         */
        String publishFromMember(Lane lane, String snapshotId, Map<String, String> files, List<String> declared, boolean pushed) throws Exception {
            String base = TestGit.output(member, "rev-parse", "HEAD");
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.createDirectories(member.resolve(file.getKey()).getParent());
                Files.writeString(member.resolve(file.getKey()), file.getValue());
                TestGit.run(member, "add", file.getKey());
            }
            TestGit.run(member, "commit", "-m", snapshotId);
            String commit = TestGit.output(member, "rev-parse", "HEAD");
            TestGit.run(member, "reset", "--hard", base);
            String preparedRef = "refs/synesis/prepared/cmp_" + snapshotId;
            String snapshotRef = "refs/synesis/snapshots/" + snapshotId;
            TestGit.run(member, "update-ref", preparedRef, commit);
            TestGit.run(member, "update-ref", snapshotRef, commit);
            if (pushed) {
                TestGit.run(member, "push", "origin", preparedRef + ":" + preparedRef, snapshotRef + ":" + snapshotRef);
            }
            String tree = TestGit.output(member, "rev-parse", commit + "^{tree}");
            SnapshotProvenance provenance = new SnapshotProvenance(leader.group, lane.id(), WorkIntent.defaultAuthorityLineage(lane.id()), lane.participant(), "binding", 1,
                    List.of(), List.of(), declared.stream().map(path -> "PATH_EXACT:" + path).toList(), snapshotRef, tree, "manifest");
            TaskSnapshotPayload snapshot = new TaskSnapshotPayload(lane.task(), snapshotId, leader.identity.nodeId(), "sup", "worker", "session", base, commit, declared, List.of(),
                    "snapshot", provenance, false);
            CompletionPreparedPayload prepared = new CompletionPreparedPayload(lane.task(), "cmp_" + snapshotId, lane.id(), 1, base, preparedRef, tree, declared);
            PredictionEventStore store = leader.store();
            store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, leader.identity.nodeId(), prepared.encode(), leader.identity);
            store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, leader.identity.nodeId(), snapshot.encode(), leader.identity);
            return commit;
        }
    }

    @Test
    void theLeaderFetchesTheMembersCommitOverLinkAndIntegratesIt(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, true, false);
        link = computers.link();
        Project leader = computers.leader;
        Lane lane = leader.announce("agt_member", "src/a.txt");
        String commit = computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "from the member\n"), List.of("src/a.txt"), false);
        assertFalse(hasCommit(leader.root, commit), "the leader's clone does not hold the member's commit before the pass");

        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.COMPLETED, pass.status(), pass.toString());
        assertEquals(TaskCompletionState.INTEGRATED, leader.state("snap_1"));
        assertTrue(leader.headHas("src/a.txt"));
        assertEquals(commit, leader.git("rev-parse", "refs/synesis/snapshots/snap_1"), "the snapshot ref the log names was set");
        assertFalse(computers.remoteRefs().contains("refs/synesis/"), "nothing went through a remote");
    }

    @Test
    void aCommitThatChangesMoreThanTheRecordDeclaresIsRefusedNotIntegrated(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, true, false);
        link = computers.link();
        Project leader = computers.leader;
        Lane lane = leader.announce("agt_member", "src/a.txt");
        String head = leader.head();
        // The record claims one claimed file; the commit also carries a file nobody claimed.
        computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "claimed\n", "src/secret.txt", "smuggled\n"), List.of("src/a.txt"), false);

        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.BLOCKED, pass.status(), pass.toString());
        assertEquals(head, leader.head());
        assertFalse(leader.headHas("src/secret.txt"));
        assertFalse(leader.headHas("src/a.txt"));
        assertEquals(0, leader.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
    }

    @Test
    void aMemberThatIsNotConnectedStartsNoAttemptAndTheNextPassFetchesOnceItIs(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, true, false);
        link = computers.link();
        Project leader = computers.leader;
        Lane lane = leader.announce("agt_member", "src/a.txt");
        computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "x\n"), List.of("src/a.txt"), false);
        link.disconnect();
        String head = leader.head();

        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.WAITING, pass.status(), pass.toString());
        assertEquals(AgentReason.INTEGRATION_PENDING, pass.reason(), pass.toString());
        assertEquals("CODE_REMOTE_UNAVAILABLE", ((Map<?, ?>) pass.result()).get("failure"));
        assertEquals(0, leader.count(PredictionEventType.INTEGRATION_ATTEMPT_STARTED));
        assertEquals(0, leader.count(PredictionEventType.TASK_INTEGRATED));
        assertEquals(head, leader.head());

        link.reconnect();
        AgentResponse next = leader.pump();

        assertEquals(AgentStatus.COMPLETED, next.status(), next.toString());
        assertEquals(TaskCompletionState.INTEGRATED, leader.state("snap_1"));
    }

    @Test
    void aMirrorGetsTheControlBranchAndANotConnectedMemberIsFetchedFromTheMirror(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, true, true);
        link = computers.link();
        link.disconnect();
        Project leader = computers.leader;
        Lane lane = leader.announce("agt_member", "src/a.txt");
        computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "x\n"), List.of("src/a.txt"), true);
        String branchBefore = TestGit.output(computers.remote, "rev-parse", "refs/heads/" + computers.branch);
        // Somebody else moved the remote branch, so the leader's push cannot fast-forward it.
        TestGit.run(computers.member, "commit", "--allow-empty", "-m", "someone else");
        TestGit.run(computers.member, "push", "origin", "HEAD:refs/heads/" + computers.branch);
        String moved = TestGit.output(computers.remote, "rev-parse", "refs/heads/" + computers.branch);

        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.COMPLETED, pass.status(), pass.toString());
        assertEquals(TaskCompletionState.INTEGRATED, leader.state("snap_1"));
        assertEquals(moved, TestGit.output(computers.remote, "rev-parse", "refs/heads/" + computers.branch), "the remote refused the push");

        TestGit.run(computers.remote, "update-ref", "refs/heads/" + computers.branch, branchBefore);
        AgentResponse next = leader.pump();

        assertEquals(AgentStatus.READY, next.status(), next.toString());
        assertEquals(leader.head(), TestGit.output(computers.remote, "rev-parse", "refs/heads/" + computers.branch), "the next pass published the branch");
    }

    @Test
    void aSingleComputerProjectNeverTouchesItsRemote(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, false, false);
        Project leader = computers.leader;
        String refsBefore = computers.remoteRefs();
        Lane lane = leader.announce("agt_lane", "src/a.txt");
        leader.publish(lane, "snap_1", 1, "src/a.txt", "x\n");

        AgentResponse pass = leader.pump();

        assertEquals(AgentStatus.COMPLETED, pass.status(), pass.toString());
        assertEquals(refsBefore, computers.remoteRefs(), "no ref was pushed");
        assertNotEquals(leader.head(), TestGit.output(computers.remote, "rev-parse", "refs/heads/" + computers.branch), "the control branch was not pushed");
    }

    @Test
    void aReviewOrValidationWorktreeFetchesTheNamedCommitOnlyWhenItIsMissing(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, true, false);
        link = computers.link();
        Project leader = computers.leader;
        UUID project = leader.location.projectId();
        Lane lane = leader.announce("agt_member", "src/a.txt");
        String commit = computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "x\n"), List.of("src/a.txt"), false);
        assertFalse(hasCommit(leader.root, commit));

        CodeExchange.ensureCommit(computers.coordination(), project, leader.root, commit);

        assertTrue(hasCommit(leader.root, commit));
        link.disconnect();
        CodeExchange.ensureCommit(computers.coordination(), project, leader.root, commit);

        String absent = "0123456789012345678901234567890123456789";
        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class,
                () -> CodeExchange.ensureCommit(computers.coordination(), project, leader.root, absent));
        assertEquals(CodeRemote.UNAVAILABLE, refused.code());
    }

    @Test
    void aSingleComputerNeverFetchesAMissingCommit(@TempDir Path temp) throws Exception {
        Computers computers = new Computers(temp, false, false);
        Project leader = computers.leader;
        Lane lane = leader.announce("agt_member", "src/a.txt");
        String commit = computers.publishFromMember(lane, "snap_1", Map.of("src/a.txt", "x\n"), List.of("src/a.txt"), false);

        CodeExchange.ensureCommit(computers.coordination(), leader.location.projectId(), leader.root, commit);

        assertFalse(hasCommit(leader.root, commit));
    }

    private static boolean hasCommit(Path repository, String commit) {
        try {
            TestGit.output(repository, "cat-file", "-e", commit + "^{commit}");
            return true;
        } catch (IOException absent) {
            return false;
        }
    }
}
