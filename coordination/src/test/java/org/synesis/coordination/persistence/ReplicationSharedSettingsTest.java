package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.exception.CoordinationException;

/**
 * {@code role=shared} is the step-6 settings file: it says the project is shared and names no one, because who
 * belongs is in the owner's log (ADR-0098 step 6, designer item 14). The step-5 forms still read, for the migration.
 */
final class ReplicationSharedSettingsTest {

    private static Path file(Path root) {
        return root.resolve(ReplicationSettings.FILE);
    }

    private static ReplicationSettings read(Path root, String text) throws Exception {
        Files.writeString(file(root), text, StandardCharsets.UTF_8);
        return ReplicationSettings.read(root).orElseThrow();
    }

    @Test
    void aSharedFileReadsWithAndWithoutACodeRemote(@TempDir Path root) throws Exception {
        ReplicationSettings bare = read(root, "role=shared\n");
        assertTrue(bare.shared());
        assertNull(bare.codeRemote());
        assertNull(bare.leaderNodeId());
        assertEquals(Set.of(), bare.members());
        assertFalse(bare.isMember(), "a shared file is not a step-5 member file");

        assertEquals(ReplicationSettings.sharedProject("origin"), read(root, "role=shared\ncode.remote=origin\n"));
    }

    @Test
    void sharedSettingsWriteExactlyTheRoleAndTheRemote(@TempDir Path root) throws Exception {
        ReplicationSettings.sharedProject(null).write(root);
        assertEquals("role=shared\n", Files.readString(file(root)));

        ReplicationSettings.sharedProject("origin").write(root);
        assertEquals("role=shared\ncode.remote=origin\n", Files.readString(file(root)));
    }

    @Test
    void aSharedFileSurvivesARoundTripAndReplacesAStepFiveFile(@TempDir Path root) throws Exception {
        ReplicationSettings.leader(Set.of("sl1-a", "sl1-b")).withCodeRemote("origin").write(root);
        ReplicationSettings.sharedProject("origin").write(root);

        ReplicationSettings read = ReplicationSettings.read(root).orElseThrow();
        assertEquals(ReplicationSettings.sharedProject("origin"), read);
        assertEquals(Set.of(), read.members(), "no member of the step-5 file is kept");
    }

    @Test
    void withCodeRemoteKeepsTheSharedForm() {
        ReplicationSettings settings = ReplicationSettings.sharedProject(null).withCodeRemote("upstream");

        assertTrue(settings.shared());
        assertEquals("upstream", settings.codeRemote());
    }

    @Test
    void aSharedFileThatNamesALeaderOrMembersIsInvalid(@TempDir Path root) {
        for (String text : new String[] {"role=shared\nleader=sl1-a\n", "role=shared\nmember=sl1-a\n", "role=shared\nleader=sl1-a\ncode.remote=origin\n",
                "role=shared\ncode.remote=origin\nmember=sl1-a\n", "role=shared\nanything\n"}) {
            CoordinationException refused = assertThrows(CoordinationException.class, () -> read(root, text), text);
            assertEquals(ReplicationSettings.INVALID, refused.code(), text);
        }
    }

    @Test
    void aSharedFileWithOtherDefectsIsInvalid(@TempDir Path root) {
        for (String text : new String[] {"role=shared", "role=shared\ncode.remote=a\ncode.remote=b\n", "role=shared\ncode.remote=-x\n", "role=shared\r\n",
                "role=Shared\n", "role=shared\n\n", ""}) {
            CoordinationException refused = assertThrows(CoordinationException.class, () -> read(root, text), text);
            assertEquals(ReplicationSettings.INVALID, refused.code(), text);
        }
    }

    @Test
    void aSharedRecordCannotBeBuiltWithALeaderOrMembers() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicationSettings("sl1-a", Set.of(), null, true));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationSettings(null, Set.of("sl1-a"), null, true));
    }

    @Test
    void stepFiveFilesStillReadAsTheyDid(@TempDir Path root) throws Exception {
        ReplicationSettings leader = read(root, "role=leader\nmember=sl1-b\nmember=sl1-c\ncode.remote=origin\n");
        assertFalse(leader.shared());
        assertEquals(Set.of("sl1-b", "sl1-c"), leader.members());
        assertEquals("origin", leader.codeRemote());

        ReplicationSettings member = read(root, "role=member\nleader=sl1-a\n");
        assertFalse(member.shared());
        assertTrue(member.isMember());
        assertEquals("sl1-a", member.leaderNodeId());
    }
}
