package org.synesis.link.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Internal fixed-size codec for authenticated heartbeat payloads. */
record HeartbeatMessage(UUID sessionId, long sequence, long relatedSequence, long marker, boolean ack) {
    static final byte VERSION = 1;
    static final int BYTES = 41;

    static HeartbeatMessage heartbeat(UUID sessionId, long sequence, long highestReceived, long marker) {
        return create(sessionId, sequence, highestReceived, marker, false);
    }

    static HeartbeatMessage acknowledgement(UUID sessionId, long sequence, long receiverSequence, long marker) {
        return create(sessionId, sequence, receiverSequence, marker, true);
    }

    byte[] encoded() {
        ByteBuffer buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.put(VERSION).putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits())
                .putLong(sequence).putLong(relatedSequence).putLong(marker);
        return buffer.array();
    }

    static HeartbeatMessage decode(byte[] encoded, boolean ack) {
        return decode(encoded, null, ack);
    }

    static HeartbeatMessage decode(byte[] encoded, UUID expectedSession, boolean ack) {
        if (encoded == null || encoded.length != BYTES) throw new IllegalArgumentException("invalid heartbeat length");
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        if (buffer.get() != VERSION) throw new IllegalArgumentException("unsupported heartbeat version");
        UUID sessionId = new UUID(buffer.getLong(), buffer.getLong());
        if (expectedSession != null && !expectedSession.equals(sessionId)) {
            throw new IllegalArgumentException("heartbeat session mismatch");
        }
        long sequence = buffer.getLong();
        long related = buffer.getLong();
        long marker = buffer.getLong();
        if (sequence < 0 || (related < -1) || marker < 0) {
            throw new IllegalArgumentException("invalid heartbeat sequence");
        }
        return create(sessionId, sequence, related, marker, ack);
    }

    private static HeartbeatMessage create(UUID sessionId, long sequence, long related,
            long marker, boolean ack) {
        if (sessionId == null || sequence < 0 || related < -1 || marker < 0) {
            throw new IllegalArgumentException("invalid heartbeat fields");
        }
        return new HeartbeatMessage(sessionId, sequence, related, marker, ack);
    }
}
