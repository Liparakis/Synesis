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

/** Verifies immutable heads, append results, corruption detection, and recovery. */
final class DecisionStoreTest {
    private static final UUID PROJECT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RECORD = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void duplicateStaleAndConflictNeverOverwriteHead() throws Exception {
        Path root = Files.createTempDirectory("decision-store-");
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord first = record(signer, 1, null, "one");
        assertEquals(DecisionStore.SaveResult.APPLIED, store.save(first, null));
        DecisionStore.Head firstHead = store.headState(RECORD).orElseThrow();
        assertEquals(DecisionStore.SaveResult.DUPLICATE, store.save(first, null));

        DecisionRecord second = record(signer, 2, first.digest(), "two");
        assertEquals(DecisionStore.SaveResult.STALE_BASE, store.save(second, null));
        assertEquals(DecisionStore.SaveResult.APPLIED, store.save(second, firstHead));
        DecisionRecord divergent = record(signer, 3, first.digest(), "divergent");
        assertEquals(DecisionStore.SaveResult.CONFLICT, store.save(divergent, store.headState(RECORD).orElseThrow()));
        assertEquals(2, store.head(RECORD).orElseThrow().revision());
    }

    @Test
    void corruptionIsRejectedAndMissingHeadRecoversOnRestart() throws Exception {
        Path root = Files.createTempDirectory("decision-recovery-");
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord first = record(signer, 1, null, "one");
        assertEquals(DecisionStore.SaveResult.APPLIED, store.save(first, null));
        Path head = root.resolve("heads").resolve(RECORD + ".head");
        Files.delete(head);
        DecisionStore restarted = new DecisionStore(root, PROJECT);
        assertEquals(first.digestHex(), restarted.head(RECORD).orElseThrow().digestHex());

        Path revision = root.resolve("decisions").resolve(RECORD.toString()).resolve("1.sdr");
        byte[] corrupted = Files.readAllBytes(revision);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(revision, corrupted);
        assertThrows(java.io.IOException.class, () -> new DecisionStore(root, PROJECT));
    }

    @Test
    void invalidSignatureCandidateIsNotPersisted() throws Exception {
        Path root = Files.createTempDirectory("decision-corrupt-");
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionStore store = new DecisionStore(root, PROJECT);
        DecisionRecord record = record(signer, 1, null, "one");
        byte[] bytes = record.encoded();
        bytes[bytes.length - 1] ^= 1;
        DecisionRecord tampered = DecisionRecord.decode(bytes);
        assertFalse(tampered.verify());
        assertEquals(DecisionStore.SaveResult.CORRUPT, store.save(tampered, null));
        assertTrue(store.head(RECORD).isEmpty());
    }

    private static DecisionRecord record(Ed25519Signer signer, long revision, byte[] previous, String title)
            throws Exception {
        return DecisionRecord.create(PROJECT, RECORD, revision, previous, signer.nodeId(), signer.nodeId(),
                DecisionStatus.PROPOSED, TIME, TIME, title, "rationale", List.of(
                        new DecisionEvidence("test", "reference", new byte[32])), signer);
    }
}
