package org.synesis.cli.command.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.HumanTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.cli.terminal.Terminal;

import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine;

/**
 * {@code synesis agents log}: the newest run by default, one agent or all, Claude's and Codex's transcripts read as what
 * the agent said, called and was answered, and no secret printed.
 */
final class AgentsLogCommandTest {

    private static final String CLAUDE = """
            {"type":"system","subtype":"init","session_id":"c92398cb"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"I will claim a lane."}]}}
            {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__synesis__ensure_session","input":{"goal":"primes","token":"tok-CLAUDE-SECRET"}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","content":"{\\"status\\":\\"ready\\"} access_token=SECRET-ANSWER-1"}]}}
            """;

    private static final String CODEX = """
            {"type":"thread.started","thread_id":"019da8"}
            {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"Reviewing fib."}}
            {"type":"item.completed","item":{"id":"item_1","type":"mcp_tool_call","server":"synesis","tool":"get_next_action","arguments":{},"result":{"content":[{"type":"text","text":"{\\"status\\":\\"blocked\\"}"}]},"error":null,"status":"completed"}}
            """;

    @TempDir
    Path tmp;
    private Path state;
    private ProjectLocation location;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private record Run(int exit, List<String> stdout, List<String> stderr) {

        @Override
        public String toString() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    @BeforeEach
    void project() throws Exception {
        state = Files.createDirectories(tmp.resolve("state"));
        location = new ProjectApplicationService().init(Files.createDirectories(tmp.resolve("project"))).location();
    }

    private Path run(String name, long modifiedAt, String... files) throws Exception {
        Path run = Files.createDirectories(state.resolve("agent-runs").resolve(location.projectId().toString()).resolve(name));
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(run.resolve(files[i]), files[i + 1]);
        }
        Files.setLastModifiedTime(run, FileTime.fromMillis(modifiedAt));
        return run;
    }

    private Run log(boolean person, String... arguments) {
        out.reset();
        err.reset();
        ConsoleTerminal console = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        Terminal terminal = person ? new HumanTerminal(console, false) : console;
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        List<String> all = new ArrayList<>(List.of("--project", location.root().toString()));
        all.addAll(List.of(arguments));
        int exit = new CommandLine(new AgentsLogCommand(runtime, () -> state)).execute(all.toArray(String[]::new));
        return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    @Test
    void theNewestRunIsShownForEveryAgentInTheOrderTheyStartedWithoutAnySecret() throws Exception {
        String older = "run-" + UUID.randomUUID();
        String newer = "run-" + UUID.randomUUID();
        run(older, 1_000L, "claude-1-1.log", "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"from the older run\"}]}}\n");
        run(newer, 9_000L, "codex-2-2.log", CODEX, "claude-1-1.log", CLAUDE);

        Run shown = log(false);

        assertEquals(List.of("AGENT_LOG_RUN=" + newer, "AGENT_LOG_FILES=2",
                "AGENT_LOG_FILE=claude#1 launch=1 file=claude-1-1.log entries=3",
                "AGENT_LOG_SAID=I will claim a lane.",
                "AGENT_LOG_CALLED=mcp__synesis__ensure_session {\"goal\":\"primes\",\"token\":\"[redacted]\"}",
                "AGENT_LOG_RESULT={\"status\":\"ready\"} access_token=[redacted]",
                "AGENT_LOG_FILE=codex#2 launch=2 file=codex-2-2.log entries=3",
                "AGENT_LOG_SAID=Reviewing fib.",
                "AGENT_LOG_CALLED=synesis.get_next_action {}",
                "AGENT_LOG_RESULT={\"status\":\"blocked\"}"), shown.stdout(), shown.toString());
        assertEquals(0, shown.exit());
        assertFalse((shown.stdout() + " " + shown.stderr()).contains("SECRET"), "no secret is printed: " + shown);
    }

    @Test
    void oneAgentOrAnOlderRunCanBeChosen() throws Exception {
        String older = "run-" + UUID.randomUUID();
        run(older, 1_000L, "claude-1-1.log", "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"from the older run\"}]}}\n");
        run("run-" + UUID.randomUUID(), 9_000L, "codex-2-2.log", CODEX, "claude-1-1.log", CLAUDE);

        Run codex = log(false, "codex#2");
        Run older_ = log(false, "--run", older);

        assertEquals(List.of("AGENT_LOG_RUN=" + codex.stdout().getFirst().substring("AGENT_LOG_RUN=".length()), "AGENT_LOG_FILES=1"), codex.stdout().subList(0, 2), codex.toString());
        assertTrue(codex.stdout().stream().noneMatch(line -> line.contains("primes") || line.startsWith("AGENT_LOG_FILE=claude")), codex.toString());
        assertEquals(List.of("AGENT_LOG_RUN=" + older, "AGENT_LOG_FILES=1", "AGENT_LOG_FILE=claude#1 launch=1 file=claude-1-1.log entries=1", "AGENT_LOG_SAID=from the older run"), older_.stdout(), older_.toString());
    }

    @Test
    void aPersonReadsTheSameLinesAlignedAndNoSecretEither() throws Exception {
        run("run-" + UUID.randomUUID(), 9_000L, "claude-1-1.log", CLAUDE);

        Run shown = log(true);

        assertEquals(0, shown.exit(), shown.toString());
        assertTrue(shown.stdout().stream().anyMatch(line -> line.trim().startsWith("Agent log said") && line.endsWith("I will claim a lane.")), shown.toString());
        assertFalse(shown.stdout().toString().contains("SECRET"), shown.toString());
    }

    @Test
    void refusalsNameTheirCauseAndTheFix() throws Exception {
        Run none = log(false);

        assertEquals(ExitCodes.COMMAND_FAILED, none.exit(), none.toString());
        assertEquals(List.of("ERROR=NO_AGENT_RUNS", "MESSAGE=this project has no run of agents to show", "FIX=start agents with: synesis agents run --agent claude --prompt \"<task>\""), none.stderr());

        run("run-" + UUID.randomUUID(), 1_000L, "claude-1-1.log", CLAUDE, "codex-2-2.log", CODEX);
        Run unknown = log(false, "gemini");
        assertEquals(ExitCodes.COMMAND_FAILED, unknown.exit(), unknown.toString());
        assertEquals(List.of("ERROR=NO_SUCH_AGENT", "MESSAGE=the run has no log of gemini; its agents are claude#1, codex#2", "FIX=name one of the run's agents, or leave the agent out for all of them"), unknown.stderr());

        Run invalid = log(false, "--run", "../elsewhere");
        assertEquals(ExitCodes.USAGE, invalid.exit(), invalid.toString());
        assertEquals("ERROR=RUN_INVALID", invalid.stderr().getFirst());

        Run missing = log(false, "--run", "run-" + UUID.randomUUID());
        assertEquals(ExitCodes.COMMAND_FAILED, missing.exit(), missing.toString());
        assertTrue(missing.stderr().getFirst().equals("ERROR=NO_AGENT_RUNS") && missing.stderr().get(1).startsWith("MESSAGE=this project has no run named run-"), missing.toString());
    }
}
