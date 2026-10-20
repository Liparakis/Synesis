package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.workspace.test.CodeLinkPair;
import org.synesis.workspace.test.TestGit;

/**
 * A member's checked-out branch follows the leader's over Link (ADR-0098 step 3): the first checkout goes only into a
 * folder that holds nothing but what Synesis wrote, and a later one fast-forwards a branch.
 */
final class CodeOverLinkFollowTest {

    private final UUID project = UUID.randomUUID();
    @TempDir
    Path temp;
    private Path leader;
    private Path member;
    private CodeLinkPair link;

    @BeforeEach
    void leaderWithHistory() throws Exception {
        leader = Files.createDirectories(temp.resolve("leader"));
        TestGit.run(leader, "init", "-q");
        Files.writeString(leader.resolve("board.txt"), "first\n");
        TestGit.run(leader, "add", "board.txt");
        TestGit.run(leader, "commit", "-q", "-m", "first");
        member = Files.createDirectories(temp.resolve("member"));
        Files.createDirectories(member.resolve(".synesis/coordination"));
        link = new CodeLinkPair(project, leader, Files.createDirectories(temp.resolve("leader-coordination")), "leader-node",
                member, member.resolve(".synesis/coordination"), "member-node");
    }

    @AfterEach
    void unlink() throws Exception {
        link.close();
    }

    private CodeTransferMessage followLeader() throws Exception {
        return CodeTransferCodec.decode(link.member.fetch(CodeTransferCodec.encode(new CodeTransferMessage.Fetch(project, "HEAD"))));
    }

    @Test
    void aFolderWithOnlySynesisFilesGetsTheLeadersBranchAndThenFollowsIt() throws Exception {
        Files.writeString(member.resolve(".mcp.json"), "{}\n");
        String branch = TestGit.output(leader, "symbolic-ref", "--short", "HEAD");

        assertEquals(new CodeTransferMessage.Fetched(TestGit.output(leader, "rev-parse", "HEAD")), followLeader());
        assertEquals(branch, TestGit.output(member, "symbolic-ref", "--short", "HEAD"));
        assertEquals("first", Files.readString(member.resolve("board.txt")).strip());

        Files.writeString(leader.resolve("board.txt"), "second\n");
        TestGit.run(leader, "commit", "-q", "-am", "second");

        assertEquals(new CodeTransferMessage.Fetched(TestGit.output(leader, "rev-parse", "HEAD")), followLeader());
        assertEquals("second", Files.readString(member.resolve("board.txt")).strip());
    }

    @Test
    void aFolderWithFilesOfItsOwnIsNeverCheckedOutOver() throws Exception {
        Files.writeString(member.resolve("board.txt"), "a person's own file\n");

        assertEquals(new CodeTransferMessage.Unavailable(CodeOverLink.FOLDER_NOT_EMPTY), followLeader());
        assertEquals("a person's own file", Files.readString(member.resolve("board.txt")).strip());
        assertFalse(Files.exists(member.resolve(".git")), "no repository was made either");
    }

    /** Synesis writes no AGENTS.md (ADR-0103 decision 9), so one in the folder is a person's own file. */
    @Test
    void anAgentsFileInTheFolderIsAPersonsOwnAndIsNeverCheckedOutOver() throws Exception {
        Files.writeString(member.resolve("AGENTS.md"), "a person's own rules\n");

        assertEquals(new CodeTransferMessage.Unavailable(CodeOverLink.FOLDER_NOT_EMPTY), followLeader());
        assertEquals("a person's own rules", Files.readString(member.resolve("AGENTS.md")).strip());
    }

    @Test
    void aMemberWhoseLeaderIsAwayIsToldSoAndKeepsWhatItHas() throws Exception {
        link.disconnect();

        assertEquals(new CodeTransferMessage.Unavailable(CodeOverLink.PEER_UNREACHABLE), followLeader());
        assertTrue(Files.exists(member.resolve(".synesis")));
    }
}
