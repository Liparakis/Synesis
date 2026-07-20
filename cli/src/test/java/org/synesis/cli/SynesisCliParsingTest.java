package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.transport.Onboarding;

/** Verifies command parsing without mutating global console streams. */
final class SynesisCliParsingTest {
    @Test
    void rootAndVersionHelpSucceed() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[0], invocation.runtime()));
        assertTrue(invocation.stdout().contains("Usage: synesis"));

        Invocation version = invocation();
        assertEquals(0, SynesisCli.execute(new String[] {"--version"}, version.runtime()));
        assertTrue(version.stdout().contains("synesis 0.1.0-SNAPSHOT"));
    }

    @Test
    void malformedAndUnknownSyntaxReturnsUsageExit() throws Exception {
        assertEquals(2, SynesisCli.execute(new String[] {"unknown"}, invocation().runtime()));
        assertEquals(2, SynesisCli.execute(new String[] {"join"}, invocation().runtime()));
        assertEquals(2, SynesisCli.execute(new String[] {"host", "--expect-peer"}, invocation().runtime()));
    }

    @Test
    void identityShowUsesTheParsedCommand() throws Exception {
        Invocation invocation = invocation();
        assertEquals(0, SynesisCli.execute(new String[] {"identity", "show"}, invocation.runtime()));
        assertTrue(invocation.stdout().contains("NODE_ID=sl1-"));
        assertTrue(Files.exists(invocation.profile().resolve("identity.bin")));
    }

    private static Invocation invocation() throws Exception {
        Path profile = Files.createTempDirectory("synesis-cli-parse").resolve("profile");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        StatusRenderer renderer = new StatusRenderer(terminal);
        return new Invocation(new CliRuntime(new Onboarding(profile, renderer), terminal,
                new ReadinessInspector(profile)), profile, out, err);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private record Invocation(CliRuntime runtime, Path profile, ByteArrayOutputStream out,
            ByteArrayOutputStream err) {
        private String stdout() { return out.toString(StandardCharsets.UTF_8); }
    }
}
