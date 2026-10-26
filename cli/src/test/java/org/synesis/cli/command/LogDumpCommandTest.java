package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.command.CliHarness.Result;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * {@code synesis log dump} on a project whose runtime is not running: it reads the files and prints one
 * {@code LOG_ENTRY=} line per entry, then the summary.
 */
class LogDumpCommandTest {

    @TempDir
    Path tmp;
    private ProjectLocation location;
    private String project;

    @BeforeEach
    void initProject() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        location = new ProjectApplicationService().init(root).location();
        project = root.toString();
    }

    private void agentSays(String text) throws Exception {
        UUID sender = UUID.randomUUID();
        NodeIdentity identity = location.loadOrCreateIdentity();
        location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGE_SENT,
                identity.nodeId(), ConversationMessage.encodeSent(UUID.randomUUID(), sender,
                        List.of(sender, UUID.randomUUID()), text), identity);
    }

    @Test
    void printsEachEntryInAppliedOrderThenTheSummaryAndNeverTheMessageText() throws Exception {
        agentSays("hello there SECRET-WORDS");
        agentSays("second");

        Result dump = CliHarness.run(tmp.resolve("cli-profile"), "log", "dump", "--project", project);

        assertEquals(0, dump.exit(), dump.toString());
        List<String> entries = dump.stdout().stream().filter(line -> line.startsWith("LOG_ENTRY=")).toList();
        assertEquals(2, entries.size(), dump.toString());
        assertTrue(entries.get(0).startsWith("LOG_ENTRY=pos=1 log=self "), entries.get(0));
        assertTrue(entries.get(1).startsWith("LOG_ENTRY=pos=2 log=self "), entries.get(1));
        assertTrue(entries.get(0).contains("type=CONVERSATION_MESSAGE_SENT"), entries.get(0));
        assertTrue(dump.stdout().contains("LOG_ENTRIES=2"), dump.toString());
        assertTrue(dump.stdout().contains("SUMMARY_LANES=0"), dump.toString());
        assertTrue(dump.stdout().stream().noneMatch(line -> line.contains("SECRET-WORDS")), dump.toString());
    }

    @Test
    void theTypeOptionFiltersEntriesOnly() throws Exception {
        agentSays("one");

        Result none = CliHarness.run(tmp.resolve("cli-profile"), "log", "dump", "--project", project, "--type",
                "work_intent_announced");

        assertEquals(0, none.exit(), none.toString());
        assertTrue(none.stdout().contains("LOG_ENTRIES=0"), none.toString());
        assertTrue(none.stdout().contains("SUMMARY_LANES=0"), none.toString());
    }

    @Test
    void aProjectWithNoEntriesPrintsAnEmptyDump() {
        Result dump = CliHarness.run(tmp.resolve("cli-profile"), "log", "dump", "--project", project);

        assertEquals(0, dump.exit(), dump.toString());
        assertTrue(dump.stdout().contains("LOG_ENTRIES=0"), dump.toString());
    }
}
