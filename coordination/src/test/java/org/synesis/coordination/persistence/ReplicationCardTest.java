package org.synesis.coordination.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where this computer's member card listens is kept in {@code replication.card} so a restart listens on the same port
 * (ADR-0098 step 6, designer item 13); a file that cannot be read restores nothing.
 */
final class ReplicationCardTest {

    @TempDir
    Path root;

    private void file(String content) throws IOException {
        Files.writeString(root.resolve(ReplicationCard.FILE), content, StandardCharsets.UTF_8);
    }

    @Test
    void aCardWrittenIsReadBackAndWritingAgainReplacesIt() throws IOException {
        new ReplicationCard(40_123, "synesis://link/SLB3-abc_DEF-123").write(root);

        assertEquals(Optional.of(new ReplicationCard(40_123, "synesis://link/SLB3-abc_DEF-123")), ReplicationCard.read(root));

        new ReplicationCard(40_124, "SLB3-next").write(root);

        assertEquals(Optional.of(new ReplicationCard(40_124, "SLB3-next")), ReplicationCard.read(root));
    }

    @Test
    void noFileMeansNoCard() {
        assertEquals(Optional.empty(), ReplicationCard.read(root));
    }

    @Test
    void aFileThatCannotBeReadRestoresNothing() throws IOException {
        for (String bad : new String[] {"", "\n", "40123", "40123 \n", "not-a-port SLB3-x", "0 SLB3-x", "65536 SLB3-x", "-1 SLB3-x", "40123 SLB3-x extra", "40123  SLB3-x", "40123\tSLB3-x",
                "40123 " + "x".repeat(4_097), "\u0000\u0001\u0002"}) {
            file(bad);
            assertEquals(Optional.empty(), ReplicationCard.read(root), "must restore nothing: [" + bad + "]");
        }
        Files.write(root.resolve(ReplicationCard.FILE), new byte[] {(byte) 0xff, (byte) 0xfe, '4', '0', ' ', 'x'});
        assertEquals(Optional.empty(), ReplicationCard.read(root));
        Files.delete(root.resolve(ReplicationCard.FILE));
        Files.createDirectory(root.resolve(ReplicationCard.FILE));
        assertEquals(Optional.empty(), ReplicationCard.read(root), "a directory in its place is unreadable, not an error");
    }

    @Test
    void aCardThatIsNotOneTokenIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReplicationCard(0, "SLB3-x"));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationCard(65_536, "SLB3-x"));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationCard(1, ""));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationCard(1, "two words"));
        assertThrows(IllegalArgumentException.class, () -> new ReplicationCard(1, "line\nbreak"));
    }

    @Test
    void theLastLineEndingIsTolerated() throws IOException {
        file("40123 SLB3-x\r\n");

        assertEquals(Optional.of(new ReplicationCard(40_123, "SLB3-x")), ReplicationCard.read(root));
    }
}
