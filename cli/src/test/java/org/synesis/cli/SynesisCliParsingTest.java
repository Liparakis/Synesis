package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.RootCommand;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import picocli.CommandLine;

/**
 * Verifies command parsing without mutating global console streams.
 */
final class SynesisCliParsingTest {

    private static Invocation invocation() throws Exception {
        Path profile = Files.createTempDirectory("synesis-cli-parse").resolve("profile");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        StatusRenderer renderer = new StatusRenderer(terminal);
        return new Invocation(new CliRuntime(new Onboarding(profile, renderer), terminal), profile, out, err);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    @Test
    void rootAndVersionHelpSucceed() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[0], invocation.runtime()));
        assertTrue(invocation.stdout().contains("Usage: synesis"));

        Invocation version = invocation();
        assertEquals(0, SynesisCli.execute(new String[]{"--version"}, version.runtime()));
        Invocation metadata = invocation();
        assertEquals(0, SynesisCli.execute(new String[]{"version"}, metadata.runtime()));
        String buildVersion = metadata.stdout().lines().filter(line -> line.startsWith("SYNESIS_VERSION=")).findFirst().orElseThrow().substring("SYNESIS_VERSION=".length());
        assertTrue(version.stdout().contains("synesis " + buildVersion));
    }

    @Test
    void publicCoordinationSurfaceIsReachableFromRootHelp() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[0], invocation.runtime()));
        String help = invocation.stdout();
        for (String command : new String[]{"coordination", "task", "ownership", "prediction", "speculation", "events", "supervisor", "integration"}) {
            assertTrue(help.contains(command), "missing command: " + command);
        }
        assertFalse(help.contains("coordination-demo"));
        assertEquals(2, SynesisCli.execute(new String[]{"coordination-demo"}, invocation().runtime()));
    }

    @Test
    void linkAndConversationsAreListedUnderTheirHelpGroups() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[0], invocation.runtime()));
        assertTrue(commandsUnder(invocation.stdout(), "Work with another person").contains("link"), invocation.stdout());
        assertTrue(commandsUnder(invocation.stdout(), "Coordinate work").contains("conversations"), invocation.stdout());
        assertFalse(commandsUnder(invocation.stdout(), "Other commands").contains("link"), invocation.stdout());
        assertFalse(commandsUnder(invocation.stdout(), "Other commands").contains("conversations"), invocation.stdout());
    }

    private static List<String> commandsUnder(String help, String heading) {
        List<String> lines = help.lines().toList();
        int start = lines.indexOf(heading + ":");
        if (start < 0) {
            return List.of();
        }
        return lines.stream().skip(start + 1).takeWhile(line -> !line.isBlank()).map(line -> line.strip().split("\\s+")[0]).toList();
    }

    @Test
    void operatorSubcommandsAreInstantiable() throws Exception {
        for (String subcommand : new String[]{"list", "recheck"}) {
            Invocation invocation = invocation();
            assertEquals(0, SynesisCli.execute(new String[]{"operator", subcommand, "--help"}, invocation.runtime()), subcommand);
            assertTrue(invocation.stdout().contains("synesis operator " + subcommand), subcommand);
        }
    }

    @Test
    void trashSubcommandsAreInstantiable() throws Exception {
        for (String subcommand : new String[]{"list", "restore", "empty"}) {
            Invocation invocation = invocation();
            assertEquals(0, SynesisCli.execute(new String[]{"trash", subcommand, "--help"}, invocation.runtime()), subcommand);
            assertTrue(invocation.stdout().contains("synesis trash " + subcommand), subcommand);
        }
        Invocation parent = invocation();
        assertEquals(0, SynesisCli.execute(new String[]{"trash", "--help"}, parent.runtime()));
        assertTrue(parent.stdout().contains("synesis trash"));
    }

    @Test
    void emptyingTheTrashWithoutYesIsRefusedBeforeAnythingIsOpened() throws Exception {
        Invocation invocation = invocation();
        Path nowhere = invocation.profile().resolveSibling("no-such-project");

        int exit = SynesisCli.execute(new String[]{"trash", "empty", "--project", nowhere.toString(), "2f0b1c1e-6f6e-4b8c-9a55-0d8e7b6a1c11", "1"}, invocation.runtime());

        assertEquals(ExitCodes.COMMAND_FAILED, exit);
        assertTrue(invocation.stderr().contains("TRASH_NOT_EMPTIED=CONFIRMATION_REQUIRED"), invocation.stderr());
        assertFalse(Files.exists(nowhere));
    }

    @Test
    void emptyingNeedsALaneAndAnEpoch() throws Exception {
        assertEquals(2, SynesisCli.execute(new String[]{"trash", "empty", "--yes"}, invocation().runtime()));
    }

    @Test
    void contractSubcommandsAreInstantiable() throws Exception {
        for (String subcommand : new String[]{"status"}) {
            Invocation invocation = invocation();
            assertEquals(0, SynesisCli.execute(new String[]{"collaboration", "contract", subcommand, "--help"}, invocation.runtime()), subcommand);
            assertTrue(invocation.stdout().contains("synesis collaboration contract " + subcommand), subcommand);
        }
    }

    @Test
    void malformedAndUnknownSyntaxReturnsUsageExit() throws Exception {
        assertEquals(2, SynesisCli.execute(new String[]{"unknown"}, invocation().runtime()));
        assertEquals(2, SynesisCli.execute(new String[]{"join"}, invocation().runtime()));
        assertEquals(2, SynesisCli.execute(new String[]{"host", "--expect-peer"}, invocation().runtime()));
    }

    @Test
    void mcpUsesConnectionProjectContextInsteadOfAProjectOption() throws Exception {
        assertEquals(2, SynesisCli.execute(new String[]{"mcp", "--project", "C:/fixture"}, invocation().runtime()));
        assertEquals(2, SynesisCli.execute(new String[]{"provider", "uninstall", "codex", "--global", "--project", "C:/fixture"}, invocation().runtime()));
    }

    @Test
    void unhandledCommandFailureUsesRedactedInternalExitMapping() throws Exception {
        Invocation invocation = invocation();
        CommandLine command = new CommandLine(new RootCommand());
        command.addSubcommand("explode", new ExplodingCommand());
        SynesisCli.configureHandlers(command, invocation.runtime());

        assertEquals(ExitCodes.INTERNAL, command.execute("explode"));
        assertTrue(invocation.stdout().contains("FAILURE=INTERNAL"));
        assertTrue(invocation.stderr().contains("Unexpected internal failure."));
        assertFalse(invocation.stderr().contains("private failure detail"));
    }

    @Test
    void malformedUriArgumentsAreNotEchoedInUsageErrors() throws Exception {
        Invocation invocation = invocation();
        String privateUri = "synesis://answer/SLA3-private-value";

        int exit = SynesisCli.execute(new String[]{"open", "synesis://join/SLB3-valid", privateUri}, invocation.runtime());

        assertEquals(ExitCodes.USAGE, exit);
        assertFalse(invocation.stderr().contains(privateUri));
        assertTrue(invocation.stderr().contains("Usage error"));
    }

    @Test
    void identityShowUsesTheParsedCommand() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[]{"identity", "show"}, invocation.runtime()));
        assertTrue(invocation.stdout().contains("NODE_ID=sl1-"));
        assertTrue(Files.exists(invocation.profile().resolve("identity.bin")));
    }

    @Test
    void configuresWindowsLoopbackSocketDirectoryWithoutOverwritingExplicitValue() {
        String previous = System.getProperty("jdk.net.unixdomain.tmpdir");
        try {
            System.clearProperty("jdk.net.unixdomain.tmpdir");
            SynesisCli.configureWindowsLoopbackSocketDirectory();
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                assertTrue(System.getProperty("jdk.net.unixdomain.tmpdir", "").endsWith("\\s") || System.getProperty("jdk.net.unixdomain.tmpdir", "").endsWith("/s"));
            }
            System.setProperty("jdk.net.unixdomain.tmpdir", "explicit-value");
            SynesisCli.configureWindowsLoopbackSocketDirectory();
            assertEquals("explicit-value", System.getProperty("jdk.net.unixdomain.tmpdir"));
        } finally {
            if (previous == null) {
                System.clearProperty("jdk.net.unixdomain.tmpdir");
            } else {
                System.setProperty("jdk.net.unixdomain.tmpdir", previous);
            }
        }
    }

    /**
     * Holds the isolated CLI invocation resources used by parsing tests.
     */
    private record Invocation(CliRuntime runtime, Path profile, ByteArrayOutputStream out, ByteArrayOutputStream err) {

        private String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }

    @picocli.CommandLine.Command(name = "explode")
    private static final class ExplodingCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            throw new IllegalStateException("private failure detail");
        }
    }
}
