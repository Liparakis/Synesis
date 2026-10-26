package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task is text left for the agents of one project, outside the project and outside the coordination log.
 */
final class AgentTaskInboxTest {

    @TempDir
    Path state;

    private final UUID project = UUID.randomUUID();

    @Test
    void anInboxWithNothingInItHasNoNewestTask() throws Exception {
        assertFalse(new AgentTaskInbox(state, project).newest().isPresent());
    }

    @Test
    void theNewestTaskIsTheLastOneSentAndKeepsItsTextWithoutSurroundingSpace() throws Exception {
        AgentTaskInbox inbox = new AgentTaskInbox(state, project);
        String first = inbox.send("first task");
        String second = inbox.send("  second task\n");

        AgentRunController.Task newest = inbox.newest().orElseThrow();
        assertEquals(second, newest.id());
        assertEquals("second task", newest.text());
        assertTrue(second.compareTo(first) > 0, "a later task has the greater identifier: " + first + " " + second);
    }

    @Test
    void aBlankOrTooLongTaskIsRefusedAndLeavesNothingBehind() throws Exception {
        AgentTaskInbox inbox = new AgentTaskInbox(state, project);

        assertThrows(IllegalArgumentException.class, () -> inbox.send(null));
        assertThrows(IllegalArgumentException.class, () -> inbox.send("  \n "));
        assertThrows(IllegalArgumentException.class, () -> inbox.send("x".repeat(AgentTaskInbox.MAX_TASK_CHARS + 1)));
        assertFalse(inbox.newest().isPresent());
        inbox.send("x".repeat(AgentTaskInbox.MAX_TASK_CHARS));
        assertTrue(inbox.newest().isPresent());
    }

    @Test
    void aTaskIsOnlyAFileOfThatProjectsTasksDirectoryAndAHalfWrittenOneIsNotSeen() throws Exception {
        AgentTaskInbox inbox = new AgentTaskInbox(state, project);
        inbox.send("the task");
        Path tasks = state.resolve("agent-runs").resolve(project.toString()).resolve("tasks");
        Files.writeString(tasks.resolve("99999999999999999999-half.tmp"), "half written");

        assertEquals("the task", inbox.newest().orElseThrow().text());
        try (Stream<Path> everything = Files.walk(state)) {
            assertTrue(everything.filter(Files::isRegularFile).allMatch(file -> file.startsWith(tasks)), "nothing is written outside the project's tasks directory");
        }
        assertFalse(new AgentTaskInbox(state, UUID.randomUUID()).newest().isPresent(), "another project does not see it");
    }
}
