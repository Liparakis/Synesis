package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.daemon.ProjectRuntime;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * An interactive agent's MCP server starts the runtime of a project shared with another computer, which nothing else
 * starts after a reboot, and leaves a single computer's project alone.
 */
final class McpCommandTest {

    @TempDir
    Path tmp;
    private Path root;
    private final List<Path> ensured = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

    @BeforeEach
    void project() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        new ProjectApplicationService().init(root);
    }

    @Test
    void aStoppedRuntimeOfASharedProjectIsStartedFromAnyDirectoryInsideIt() throws Exception {
        ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
        Path inside = Files.createDirectories(root.resolve("src/deep"));

        assertTrue(McpCommand.ensureSharedRuntime(new ProjectApplicationService(), inside, ensured::add, terminal));

        assertEquals(List.of(root.toRealPath()), ensured.stream().map(path -> realPath(path)).toList());
        assertEquals("", out.toString(StandardCharsets.UTF_8), "standard output belongs to the protocol");
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aSingleComputerProjectStartsNothing() {
        assertFalse(McpCommand.ensureSharedRuntime(new ProjectApplicationService(), root, ensured::add, terminal));

        assertEquals(List.of(), ensured);
    }

    @Test
    void aDirectoryThatIsNotAProjectStartsNothingAndSaysNothing() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));

        assertFalse(McpCommand.ensureSharedRuntime(new ProjectApplicationService(), elsewhere, ensured::add, terminal));

        assertEquals(List.of(), ensured);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRuntimeThatCannotStartIsReportedOnStandardErrorOnly() throws Exception {
        ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
        ProjectRuntime unavailable = project -> {
            throw new IOException("daemon_not_ready");
        };

        assertFalse(McpCommand.ensureSharedRuntime(new ProjectApplicationService(), root, unavailable, terminal));

        assertEquals(List.of("SYNESIS_MCP_RUNTIME=UNAVAILABLE reason=daemon_not_ready"), err.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
