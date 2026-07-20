package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies bounded matching over validated decision heads only. */
final class DecisionSearchTest {
    private static final UUID PROJECT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void matchesFiltersOrdersAndRendersStableRows() throws Exception {
        Path root = Files.createTempDirectory("decision-search-");
        Ed25519Signer firstSigner = Ed25519Signer.generate();
        Ed25519Signer secondSigner = Ed25519Signer.generate();
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord first = record(UUID.fromString("00000000-0000-0000-0000-000000000002"), firstSigner, 1, null,
                DecisionStatus.PROPOSED, "Bounded proof", "Keep the proof small");
        DecisionRecord second = record(UUID.fromString("00000000-0000-0000-0000-000000000001"), secondSigner, 1, null,
                DecisionStatus.ACCEPTED, "Other path", "Bounded proof remains preferred");
        store.save(first, null);
        store.save(second, null);
        DecisionSearch search = new DecisionSearch(store);

        DecisionSearch.SearchResult all = search.search(new DecisionSearch.Query("bounded proof", null, null, null, 8));
        assertTrue(all.isSuccessful());
        assertEquals(List.of(second.recordId(), first.recordId()), all.results().stream()
                .map(DecisionSearch.Result::recordId).toList());
        assertEquals(all.render(), search.search(new DecisionSearch.Query("bounded proof", null, null, null, 8)).render());

        DecisionSearch.SearchResult filtered = search.search(new DecisionSearch.Query("", null,
                DecisionStatus.ACCEPTED, secondSigner.nodeId(), 1));
        assertEquals(List.of(second.recordId()), filtered.results().stream().map(DecisionSearch.Result::recordId).toList());
        assertEquals("RESULTS=0\n", search.search(new DecisionSearch.Query("missing", null, null, null, 8)).render());
        assertFalse(all.render().contains(root.toString()));
    }

    @Test
    void excludesStaleRevisionsConflictsAndTemporaryFilesAcrossRestart() throws Exception {
        Path root = Files.createTempDirectory("decision-search-restart-");
        Ed25519Signer signer = Ed25519Signer.generate();
        UUID recordId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord first = record(recordId, signer, 1, null, DecisionStatus.PROPOSED, "first", "one");
        DecisionRecord second = record(recordId, signer, 2, first.digest(), DecisionStatus.ACCEPTED, "second", "two");
        DecisionRecord conflict = record(recordId, signer, 2, first.digest(), DecisionStatus.REJECTED, "conflict", "three");
        store.save(first, null);
        store.save(second, store.headState(recordId).orElseThrow());
        store.quarantine(conflict);
        Files.write(root.resolve("heads").resolve(recordId + ".head.tmp-test"), new byte[] {1, 2, 3});
        String before = new DecisionSearch(store).search(new DecisionSearch.Query("", null, null, null, 8)).render();
        assertTrue(before.contains("REVISION=2"));
        assertFalse(before.contains("TITLE=first"));
        assertFalse(before.contains("TITLE=conflict"));
        DecisionStore restarted = new DecisionStore(root, PROJECT);
        assertEquals(before, new DecisionSearch(restarted).search(new DecisionSearch.Query("", null, null, null, 8)).render());
    }

    @Test
    void reportsCorruptHeadsAndEnforcesQueryAndScanBounds() throws Exception {
        Path root = Files.createTempDirectory("decision-search-bounds-");
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord record = record(UUID.fromString("00000000-0000-0000-0000-000000000004"), signer, 1, null,
                DecisionStatus.PROPOSED, "safe", "state");
        store.save(record, null);
        DecisionSearch search = new DecisionSearch(store);
        assertThrows(IllegalArgumentException.class, () -> new DecisionSearch.Query("x".repeat(257), null, null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionSearch.Query("a b c d e", null, null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecisionSearch.Query("", null, null, null, DecisionSearch.MAX_RESULTS + 1));

        byte[] head = Files.readAllBytes(root.resolve("heads").resolve(record.recordId() + ".head"));
        head[head.length - 1] ^= 1;
        Files.write(root.resolve("heads").resolve(record.recordId() + ".head"), head);
        DecisionSearch.SearchResult corrupt = search.search(new DecisionSearch.Query("", null, null, null, 1));
        assertEquals(DecisionSearch.ErrorCode.LOCAL_STATE_INVALID, corrupt.errorCode());
        assertTrue(corrupt.results().isEmpty());

        Path many = Files.createTempDirectory("decision-search-scan-");
        DecisionStore manyStore = new DecisionStore(many, PROJECT);
        for (int index = 0; index <= DecisionSearch.MAX_RECORD_SCAN; index++) {
            UUID id = new UUID(0, index + 10L);
            manyStore.save(record(id, signer, 1, null, DecisionStatus.PROPOSED, "row" + index, "state"), null);
        }
        DecisionSearch.SearchResult bounded = new DecisionSearch(manyStore)
                .search(new DecisionSearch.Query("", null, null, null, 1));
        assertEquals(DecisionSearch.ErrorCode.SCAN_LIMIT, bounded.errorCode());
        assertTrue(bounded.results().isEmpty());
    }

    private static DecisionRecord record(UUID id, Ed25519Signer signer, long revision, byte[] previous,
            DecisionStatus status, String title, String rationale) throws Exception {
        return DecisionRecord.create(PROJECT, id, revision, previous, signer.nodeId(), signer.nodeId(), status,
                TIME, TIME, title, rationale, List.of(new DecisionEvidence("test", "search", new byte[32])), signer);
    }
}
