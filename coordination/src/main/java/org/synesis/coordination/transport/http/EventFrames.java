package org.synesis.coordination.transport.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * The server-sent-event frames of the {@code /events} route, written by {@link CoordinationHttpServer} and read by
 * {@link CoordinationHttpClient}. The frame layout is a wire format, so both sides go through this one definition.
 */
final class EventFrames {

    private static final String DATA_PREFIX = "data: ";

    /** Comment frame sent when the subscriber queue overflowed; the stream ends after it. */
    static final byte[] REFRESH_REQUIRED = ": refresh_required\n\n".getBytes(StandardCharsets.UTF_8);

    /** Comment frame sent when a poll found no further event; the stream ends after it. */
    static final byte[] EMPTY = ": empty\n\n".getBytes(StandardCharsets.UTF_8);

    private EventFrames() {
    }

    /**
     * Encodes one event as an {@code id} line and a {@code data} line holding the base64 event and its type name.
     */
    static byte[] encode(PredictionEvent event) {
        return ("id: " + event.sequence() + "\n" + DATA_PREFIX + Base64.getEncoder().encodeToString(event.encoded())
                + " " + event.type().name() + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the event of one stream line.
     *
     * @return the event, or {@code null} when the line is not a {@code data} line
     * @throws IOException when the event bytes are malformed
     */
    static PredictionEvent decode(String line) throws IOException {
        if (!line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String encoded = line.substring(DATA_PREFIX.length()).split(" ", 2)[0];
        return PredictionEvent.decode(Base64.getDecoder().decode(encoded));
    }
}
