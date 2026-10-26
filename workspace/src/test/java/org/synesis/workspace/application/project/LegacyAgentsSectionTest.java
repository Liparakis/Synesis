package org.synesis.workspace.application.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins what retiring the section earlier versions kept in {@code AGENTS.md} may and may not touch
 * (ADR-0103 decision 9): only the marked section goes; the user's text stays.
 */
class LegacyAgentsSectionTest {

    private static final String SECTION = "<!-- SYNESIS-BEGIN -->\n## Synesis\n\nold rules\n<!-- SYNESIS-END -->";

    @TempDir
    Path root;

    private Path agents(String content) throws Exception {
        return Files.writeString(root.resolve("AGENTS.md"), content);
    }

    @Test
    void aMissingFileIsLeftMissing() throws Exception {
        assertFalse(LegacyAgentsSection.retire(root));
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test
    void aFileWithoutTheSectionIsNotTouched() throws Exception {
        Path file = agents("# Mine\n\nno markers here\n");

        assertFalse(LegacyAgentsSection.retire(root));
        assertEquals("# Mine\n\nno markers here\n", Files.readString(file));
    }

    @Test
    void userTextBeforeAndAfterTheSectionStaysWithOneBlankLineBetween() throws Exception {
        Path file = agents("# Mine\n\nkeep\n\n" + SECTION + "\n\n# After\n");

        assertTrue(LegacyAgentsSection.retire(root));
        assertEquals("# Mine\n\nkeep\n\n# After\n", Files.readString(file));
        assertFalse(LegacyAgentsSection.retire(root), "a second run changes nothing");
    }

    @Test
    void aSectionAppendedAfterUserTextLeavesExactlyTheUserText() throws Exception {
        Path file = agents("# Mine\n\nkeep\n\n" + SECTION + "\n");

        assertTrue(LegacyAgentsSection.retire(root));
        assertEquals("# Mine\n\nkeep\n", Files.readString(file));
    }

    @Test
    void theLineSeparatorTheFileAlreadyUsesIsKept() throws Exception {
        Path file = agents("# Mine\r\n\r\nkeep\r\n\r\n" + SECTION.replace("\n", "\r\n") + "\r\n\r\n# After\r\n");

        assertTrue(LegacyAgentsSection.retire(root));
        assertEquals("# Mine\r\n\r\nkeep\r\n\r\n# After\r\n", Files.readString(file));
    }

    @Test
    void aFileHoldingNothingElseIsDeleted() throws Exception {
        Path file = agents(SECTION + "\n");

        assertTrue(LegacyAgentsSection.retire(root));
        assertFalse(Files.exists(file));
    }

    @Test
    void markersThatDoNotPairUpLeaveTheFileExactlyAsItIs() throws Exception {
        for (String content : new String[] {
            "User\n<!-- SYNESIS-BEGIN -->\nunclosed\n",
            "User\n<!-- SYNESIS-END -->\n<!-- SYNESIS-BEGIN -->\n",
            SECTION + "\nmiddle\n" + SECTION + "\n",
            "<!-- SYNESIS-BEGIN -->\n<!-- SYNESIS-BEGIN -->\n<!-- SYNESIS-END -->\n"
        }) {
            Path file = agents(content);

            assertFalse(LegacyAgentsSection.retire(root), content);
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void aSymbolicLinkIsNeverFollowed() throws Exception {
        Path target = Files.writeString(root.resolve("elsewhere.md"), SECTION + "\n");
        try {
            Files.createSymbolicLink(root.resolve("AGENTS.md"), target);
        } catch (UnsupportedOperationException | java.io.IOException notAllowed) {
            return;
        }

        assertFalse(LegacyAgentsSection.retire(root));
        assertEquals(SECTION + "\n", Files.readString(target));
    }
}
