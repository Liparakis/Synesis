package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.provider.AgentLogEntry;
import org.synesis.workspace.provider.AgentLogEntry.Kind;

/**
 * What {@code synesis agents log} reads back: the runs of a project, the log files of a run, the entries each provider's
 * output holds, and that none of it shows a secret.
 */
class AgentRunLogsTest {

    @TempDir
    Path state;

    private final UUID project = UUID.randomUUID();

    private Path run(String name, long modifiedAt) throws IOException {
        Path run = Files.createDirectories(state.resolve("agent-runs").resolve(project.toString()).resolve(name));
        Files.setLastModifiedTime(run, FileTime.fromMillis(modifiedAt));
        return run;
    }

    private static String runName() {
        return "run-" + UUID.randomUUID();
    }

    private static List<AgentLogEntry> decode(Path run, String file) throws IOException {
        return AgentRunLogs.read(AgentRunLogs.logs(run, null).stream().filter(log -> log.file().getFileName().toString().equals(file)).findFirst().orElseThrow());
    }

    /** Lines of a real Claude Code {@code stream-json} run: bookkeeping events are not entries. */
    private static final String CLAUDE = """
            {"type":"system","subtype":"init","cwd":"/work/app","session_id":"c92398cb","tools":["Read"]}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I will read the README\\nfirst."}]}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"mcp__synesis__read_file","input":{"path":"README.md"}}]}}
            {"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"# Number toolkit\\n\\nGoal: four packages."}]}}
            {"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}
            not json at all
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_2","name":"ToolSearch","input":{"query":"select:a"}}]}}
            {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2","content":[{"type":"tool_reference","tool_name":"mcp__synesis__ensure_session"},{"type":"text","text":"loaded"}]}]}}
            {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_3","is_error":true,"content":"Permission denied"}]}}
            {"type":"result","subtype":"success","is_error":false,"result":"Done."}
            """;

    /** Lines of a real Codex {@code exec --json} run. */
    private static final String CODEX = """
            {"type":"thread.started","thread_id":"019da8"}
            {"type":"turn.started"}
            {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I am applying the Synesis workflow."}}
            {"type":"item.started","item":{"id":"item_1","type":"mcp_tool_call","server":"synesis","tool":"ensure_session","arguments":{},"result":null,"error":null,"status":"in_progress"}}
            {"type":"item.completed","item":{"id":"item_1","type":"mcp_tool_call","server":"synesis","tool":"ensure_session","arguments":{},"result":{"content":[{"type":"text","text":"{\\"status\\":\\"ready\\"}"}],"structured_content":null},"error":null,"status":"completed"}}
            {"type":"item.completed","item":{"id":"item_2","type":"mcp_tool_call","server":"synesis","tool":"read_file","arguments":{"path":"README.md"},"result":null,"error":{"message":"no such file"},"status":"failed"}}
            {"type":"item.completed","item":{"id":"item_3","type":"command_execution","command":"ls","aggregated_output":"a.txt\\n","exit_code":0,"status":"completed"}}
            {"type":"item.completed","item":{"id":"item_4","type":"reasoning","text":"thinking"}}
            {"type":"turn.completed","usage":{"input_tokens":3}}
            """;

    @Test
    void aClaudeTranscriptReadsAsWhatTheAgentSaidCalledAndWasAnswered() throws Exception {
        Path run = run(runName(), 1_000L);
        Files.writeString(run.resolve("claude-1-1.log"), CLAUDE);

        assertEquals(List.of(
                new AgentLogEntry(Kind.SAID, "", "I will read the README first."),
                new AgentLogEntry(Kind.CALLED, "mcp__synesis__read_file", "{\"path\":\"README.md\"}"),
                new AgentLogEntry(Kind.RESULT, "", "# Number toolkit Goal: four packages."),
                new AgentLogEntry(Kind.CALLED, "ToolSearch", "{\"query\":\"select:a\"}"),
                new AgentLogEntry(Kind.RESULT, "", "mcp__synesis__ensure_session loaded"),
                new AgentLogEntry(Kind.FAILED, "", "Permission denied")), decode(run, "claude-1-1.log"));
    }

    @Test
    void aCodexTranscriptReadsCompletedItemsOnly() throws Exception {
        Path run = run(runName(), 1_000L);
        Files.writeString(run.resolve("codex-2-3.log"), CODEX);

        assertEquals(List.of(
                new AgentLogEntry(Kind.SAID, "", "I am applying the Synesis workflow."),
                new AgentLogEntry(Kind.CALLED, "synesis.ensure_session", "{}"),
                new AgentLogEntry(Kind.RESULT, "synesis.ensure_session", "{\"status\":\"ready\"}"),
                new AgentLogEntry(Kind.CALLED, "synesis.read_file", "{\"path\":\"README.md\"}"),
                new AgentLogEntry(Kind.FAILED, "synesis.read_file", "no such file"),
                new AgentLogEntry(Kind.CALLED, "command", "ls"),
                new AgentLogEntry(Kind.RESULT, "command", "a.txt")), decode(run, "codex-2-3.log"));
    }

    @Test
    void aFailedClaudeRunIsAFailure() throws Exception {
        Path run = run(runName(), 1_000L);
        Files.writeString(run.resolve("claude-1-1.log"), "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"result\":\"rate limited\"}\n");

        assertEquals(List.of(new AgentLogEntry(Kind.FAILED, "", "rate limited")), decode(run, "claude-1-1.log"));
    }

    @Test
    void noSecretIsShownFromAnArgumentOrAnAnswer() throws Exception {
        Path run = run(runName(), 1_000L);
        String words = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima";
        Files.writeString(run.resolve("claude-1-1.log"), """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"curl -H 'Authorization: Bearer abcdefghijklmnop123' https://x --data token=tok-SECRET-1"}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","content":"{\\"access_token\\":\\"SECRETACCESS999\\",\\"name\\":\\"fine\\"} GITHUB_TOKEN=ghp_0123456789abcdefghijABCDEFGHIJ0123 sk-live-0123456789abcdefghij"}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","content":"RECOVERY_WORDS=%s\\nnext line stays"}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","content":"-----BEGIN OPENSSH PRIVATE KEY-----\\nb3BlbnNzaC1rZXktdjEAAAAA\\n-----END OPENSSH PRIVATE KEY----- after"}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEF123 and password: hunter2"}]}}
                """.formatted(words));

        String shown = decode(run, "claude-1-1.log").toString();

        for (String secret : List.of("abcdefghijklmnop123", "tok-SECRET-1", "SECRETACCESS999", "ghp_0123456789", "sk-live-0123456789", words, "alpha", "b3BlbnNzaC1rZXktdjEAAAAA",
                "eyJhbGciOiJIUzI1NiJ9", "hunter2")) {
            assertFalse(shown.contains(secret), secret + " was shown: " + shown);
        }
        assertTrue(shown.contains("[redacted]"), shown);
        assertTrue(shown.contains("fine"), "text next to a secret stays: " + shown);
        assertTrue(shown.contains("after"), shown);
    }

    @Test
    void textIsOneLineWithoutControlCharactersAndIsCut() {
        assertEquals("a b [31m c", AgentRunLogs.clean("a\n\tb\u001b[31m\r\n c", 100), "the escape character is gone, so what is left drives nothing");
        assertEquals("abcde... (+5 characters)", AgentRunLogs.clean("abcdefghij", 5));
        assertEquals("abcde", AgentRunLogs.clean("abcde", 5));
    }

    @Test
    void aLongRunOfNameLikeTextIsCleanedInLinearTime() {
        String text = "x".repeat(2_000_000);
        long start = System.nanoTime();

        assertEquals(10 + "... (+1999990 characters)".length(), AgentRunLogs.clean(text, 10).length());
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "cleaning a long run of letters must not take quadratic time");
    }

    @Test
    void theNewestRunIsTheDefaultAndARunCanBeNamed() throws Exception {
        String older = runName();
        String newer = runName();
        run(older, 1_000L);
        run(newer, 9_000L);

        assertEquals(newer, AgentRunLogs.run(state, project, null).orElseThrow().getFileName().toString());
        assertEquals(older, AgentRunLogs.run(state, project, older).orElseThrow().getFileName().toString());
        assertEquals(Optional.empty(), AgentRunLogs.run(state, project, runName()));
        assertEquals(Optional.empty(), AgentRunLogs.run(state, UUID.randomUUID(), null));
    }

    @Test
    void aRunIsNamedByItsDirectoryNameOnly() {
        for (String invalid : List.of("..", "../x", "run-1", "run-" + UUID.randomUUID() + "/..", "other")) {
            assertThrows(IllegalArgumentException.class, () -> AgentRunLogs.run(state, project, invalid), invalid);
        }
    }

    @Test
    void logsAreInTheOrderTheProcessesStartedAndCanBeChosenByAgentOrProvider() throws Exception {
        Path run = run(runName(), 1_000L);
        for (String file : List.of("claude-1-4.log", "codex-2-2.log", "claude-1-1.log", "claude-3-3.log", "notes.txt", "claude-x-y.log")) {
            Files.writeString(run.resolve(file), "");
        }

        assertEquals(List.of("claude#1/1", "codex#2/2", "claude#3/3", "claude#1/4"), labels(AgentRunLogs.logs(run, null)));
        assertEquals(List.of("claude#1/1", "claude#3/3", "claude#1/4"), labels(AgentRunLogs.logs(run, "claude")));
        assertEquals(List.of("claude#1/1", "claude#1/4"), labels(AgentRunLogs.logs(run, "claude#1")));
        assertEquals(List.of("claude#1/1", "claude#1/4"), labels(AgentRunLogs.logs(run, "claude-1")));
        assertEquals(List.of(), labels(AgentRunLogs.logs(run, "gemini")));
    }

    @Test
    void aLinkInTheRunIsNotFollowedToReadSomethingElse() throws Exception {
        Path run = run(runName(), 1_000L);
        Path elsewhere = Files.writeString(state.resolve("elsewhere.log"), CLAUDE);
        try {
            Files.createSymbolicLink(run.resolve("claude-1-1.log"), elsewhere);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException noLinks) {
            org.junit.jupiter.api.Assumptions.abort("this file system makes no symbolic links");
        }

        assertEquals(List.of(), AgentRunLogs.logs(run, null));
    }

    private static List<String> labels(List<AgentRunLogs.Log> logs) {
        return logs.stream().map(log -> log.agent() + "/" + log.launch()).toList();
    }
}
