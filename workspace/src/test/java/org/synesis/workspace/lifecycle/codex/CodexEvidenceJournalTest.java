package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evidence queue, journal ceiling, truncation, and retention tests. */
class CodexEvidenceJournalTest {

    @TempDir
    Path temp;

    @Test
    void oversizedEntryIsSummarizedAndClosingManifestIsBounded() throws Exception {
        Path journal = temp.resolve("generation-1.jsonl");
        try (CodexEvidenceJournal evidence = new CodexEvidenceJournal(journal)) {
            assertTrue(evidence.offer("visible_message", Map.of("text", "x".repeat(200_000)), true));
        }
        String content = Files.readString(journal, StandardCharsets.UTF_8);
        assertTrue(content.contains("truncated"));
        assertTrue(Files.size(journal.resolveSibling("generation-1.jsonl.manifest.json"))
                <= CodexEvidenceJournal.MAX_CLOSING_SUMMARY_BYTES);
    }

    @Test
    void journalCeilingMarksEvidenceIncomplete() throws Exception {
        Path journal = temp.resolve("generation-2.jsonl");
        try (CodexEvidenceJournal evidence = new CodexEvidenceJournal(journal)) {
            for (int i = 0; i < 220; i++) {
                evidence.offer("item_delta", Map.of("text", "x".repeat(60_000)), false);
            }
        }
        assertTrue(Files.size(journal) <= CodexEvidenceJournal.MAX_JOURNAL_BYTES);
        String manifest = Files.readString(journal.resolveSibling("generation-2.jsonl.manifest.json"));
        assertTrue(manifest.contains("evidenceComplete\":false"));
    }

    @Test
    void retentionDeletesOnlyOldUnreferencedGenerations() throws Exception {
        Path evidence = Files.createDirectories(temp.resolve("evidence"));
        for (int i = 1; i <= 10; i++) {
            Files.writeString(evidence.resolve("generation-" + i + ".jsonl"), "{}\n");
            Files.writeString(evidence.resolve("generation-" + i + ".jsonl.manifest.json"), "{}\n");
        }
        CodexEvidenceRetention.CleanupResult result = CodexEvidenceRetention.cleanup(evidence, 10L, temp);
        assertEquals(0, result.failures());
        assertFalse(Files.exists(evidence.resolve("generation-1.jsonl")));
        assertTrue(Files.exists(evidence.resolve("generation-10.jsonl")));
    }
}
