package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies deterministic bounded CP-R4 message framing. */
final class RecordMessageTest {
    @Test
    void allKindsRoundTripDeterministically() throws Exception {
        UUID project = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID record = UUID.fromString("22222222-2222-2222-2222-222222222222");
        byte[] digest = new byte[32];
        RecordMessage[] messages = {
            RecordMessage.syncRequest(project, record, 3, digest),
            RecordMessage.record(new byte[] {1, 2, 3}),
            RecordMessage.result(RecordMessage.ResultCode.APPLIED, record, 3, digest),
            RecordMessage.error(RecordMessage.ErrorCode.MALFORMED, "bad")
        };
        for (RecordMessage message : messages) {
            byte[] encoded = message.encoded();
            assertArrayEquals(encoded, RecordMessage.decode(encoded).encoded());
        }
    }

    @Test
    void rejectsTrailingBytesAndOversizedPayload() {
        byte[] encoded = RecordMessage.result(RecordMessage.ResultCode.APPLIED,
                UUID.randomUUID(), 0, null).encoded();
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> RecordMessage.decode(trailing));
        assertThrows(IllegalArgumentException.class, () -> RecordMessage.record(new byte[RecordMessage.MAX_BYTES]).encoded());
    }
}
