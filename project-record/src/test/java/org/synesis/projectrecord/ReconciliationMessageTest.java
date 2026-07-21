package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies deterministic bounded PRP1 message framing. */
final class ReconciliationMessageTest {

    @Test
    void allKindsRoundTripDeterministically() throws Exception {
        UUID project = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID record = UUID.fromString("22222222-2222-2222-2222-222222222222");
        byte[] digest = new byte[32];
        digest[0] = 42;

        ReconciliationMessage[] messages = {
            ReconciliationMessage.validateProject(project),
            ReconciliationMessage.validateProjectAck(project),
            ReconciliationMessage.inventoryChunk(project, 1, 3, 0, List.of(
                    new ReconciliationMessage.InventoryEntry(record, 5, digest)
            )),
            ReconciliationMessage.inventoryChunkAck(project, 2),
            ReconciliationMessage.clientUploadRevision(project, new byte[] { 1, 2, 3 }),
            ReconciliationMessage.clientUploadAck(project, record, 5, 0),
            ReconciliationMessage.clientDownloadRequest(project, record, 5),
            ReconciliationMessage.clientDownloadResponse(project, record, 5, 0, new byte[] { 4, 5 }),
            ReconciliationMessage.finalInventoryChunk(project, 1, 2, 1, List.of(
                    new ReconciliationMessage.InventoryEntry(record, 5, digest)
            )),
            ReconciliationMessage.finalInventoryChunkAck(project, 1, 0),
            ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "corrupt message payload")
        };

        for (ReconciliationMessage message : messages) {
            byte[] encoded = message.encoded();
            byte[] decodedEncoded = ReconciliationMessage.decode(encoded).encoded();
            assertArrayEquals(encoded, decodedEncoded);
        }
    }

    @Test
    void rejectsTrailingBytesAndOversizedPayload() {
        byte[] encoded = ReconciliationMessage.validateProject(UUID.randomUUID()).encoded();
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> ReconciliationMessage.decode(trailing));
    }
}
