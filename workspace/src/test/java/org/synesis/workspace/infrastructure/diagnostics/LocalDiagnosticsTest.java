package org.synesis.workspace.infrastructure.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Verifies that a failure leaves its cause where it can be found, and that the answer only names it.
 */
final class LocalDiagnosticsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyEntry(Path project) throws IOException {
        List<String> lines = Files.readAllLines(project.resolve(".synesis/local/diagnostics/failure-points.jsonl"));
        assertEquals(1, lines.size(), lines.toString());
        return (Map<String, Object>) ProviderJson.parse(lines.getFirst());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedAnswerNamesAnEntryThatHoldsTheCauseChainAndTheStack(@TempDir Path project) throws Exception {
        Exception cause = new IllegalStateException("could not establish provider session", new IOException("the file is locked by another process"));

        AgentResponse answer = AgentFailures.internal(project, "session.bind", cause, Map.of("reason", "SESSION_BINDING_FAILED"));

        assertEquals(AgentStatus.FAILED, answer.status());
        Map<String, Object> said = (Map<String, Object>) answer.result();
        assertEquals("SESSION_BINDING_FAILED", said.get("reason"));
        assertFalse(answer.toJson().contains("locked by another process"), "the answer names the entry and does not carry the cause: " + answer.toJson());
        Map<String, Object> entry = onlyEntry(project);
        assertEquals(said.get(AgentFailures.FAILURE_POINT), entry.get("id"));
        assertEquals("session", entry.get("component"));
        assertEquals("bind", entry.get("operation"));
        assertEquals(List.of("java.lang.IllegalStateException: could not establish provider session", "java.io.IOException: the file is locked by another process"), entry.get("causes"));
        assertTrue(String.valueOf(entry.get("stack")).contains("LocalDiagnosticsTest"), String.valueOf(entry.get("stack")));
    }

    /** Recording must never be the thing that fails: the operation it describes already has. */
    @Test
    void anEntryThatCannotBeWrittenLeavesTheAnswerWithoutAnIdentifier(@TempDir Path directory) throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("project"), "a file where the project should be");

        AgentResponse answer = AgentFailures.internal(notADirectory, "session.bind", new IOException("x"), null);

        assertEquals(AgentStatus.FAILED, answer.status());
        assertEquals(null, answer.result());
        assertEquals("", LocalDiagnostics.failurePoint(notADirectory, "session", "bind", "x", null));
    }
}
