package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.exception.CoordinationException;

/**
 * {@code code.remote} names the Git remote that code moves through (ADR-0098). It is a name and nothing else, and a
 * file written before it existed still reads as it did.
 */
final class ReplicationCodeRemoteSettingTest {

    private static ReplicationSettings read(Path root, String text) throws Exception {
        Files.writeString(root.resolve(ReplicationSettings.FILE), text, StandardCharsets.UTF_8);
        return ReplicationSettings.read(root).orElseThrow();
    }

    @Test
    void theRemoteIsReadFromAMemberFileAndALeaderFile(@TempDir Path root) throws Exception {
        ReplicationSettings member = read(root, "role=member\nleader=sl1-a\ncode.remote=origin\n");
        assertEquals("origin", member.codeRemote());
        assertEquals("sl1-a", member.leaderNodeId());

        ReplicationSettings leader = read(root, "role=leader\ncode.remote=upstream\nmember=sl1-b\n");
        assertEquals("upstream", leader.codeRemote());
        assertEquals(Set.of("sl1-b"), leader.members());
    }

    @Test
    void aFileWithoutTheRemoteStillReadsAndHasNone(@TempDir Path root) throws Exception {
        assertNull(read(root, "role=member\nleader=sl1-a\n").codeRemote());
        assertNull(read(root, "role=leader\nmember=sl1-b\n").codeRemote());
    }

    @Test
    void theRemoteSurvivesAWrite(@TempDir Path root) throws Exception {
        ReplicationSettings.member("sl1-a").withCodeRemote("origin").write(root);

        assertEquals(ReplicationSettings.member("sl1-a").withCodeRemote("origin"), ReplicationSettings.read(root).orElseThrow());
    }

    @Test
    void aRemoteThatCouldBeAnOptionAUrlOrAPathIsRefused(@TempDir Path root) throws Exception {
        for (String name : new String[] {"", "-delete", "--upload-pack=x", "https://host/repo.git", "../other", "a b", "a/b", ".hidden"}) {
            CoordinationException refused = assertThrows(CoordinationException.class,
                    () -> read(root, "role=member\nleader=sl1-a\ncode.remote=" + name + "\n"), name);
            assertEquals(ReplicationSettings.INVALID, refused.code());
        }
    }

    @Test
    void aRemoteNamedTwiceIsRefused(@TempDir Path root) {
        assertThrows(CoordinationException.class, () -> read(root, "role=leader\ncode.remote=a\ncode.remote=b\n"));
    }
}
