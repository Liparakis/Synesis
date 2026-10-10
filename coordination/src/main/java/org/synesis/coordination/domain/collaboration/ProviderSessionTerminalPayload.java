package org.synesis.coordination.domain.collaboration;


import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable payload for an explicit terminal fence on one provider session.
 *
 * @param sessionId         exact provider session identity
 * @param provider          stable provider identifier
 * @param participant       derived participant handle
 * @param reason            bounded operator or provider reason
 * @param validatedRevision event-log head validated before append
 */
public record ProviderSessionTerminalPayload(String sessionId, String provider, String participant, String reason,
                                             long validatedRevision) {

    private static final int MAGIC = 0x53544d31;
    private static final WireFormat TEXT = WireFormat.bounded(256,
            false,
            "terminal payload text bound",
            "truncated terminal payload text");

    /**
     * Validates the exact session identity and optimistic revision proof.
     */
    public ProviderSessionTerminalPayload {
        requireText(sessionId, "sessionId");
        requireText(provider, "provider");
        requireText(participant, "participant");
        requireText(reason, "reason");
        if (validatedRevision < 0L) {
            throw new CoordinationArgumentException("validatedRevision must not be negative");
        }
    }

    /**
     * Decodes a durable terminal fence payload.
     *
     * @param encoded encoded payload
     * @return decoded payload
     * @throws IOException when the payload is malformed
     */
    public static ProviderSessionTerminalPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported provider session terminal format");
            }
            ProviderSessionTerminalPayload payload = new ProviderSessionTerminalPayload(TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    TEXT.readText(in),
                    in.readLong());
            if (in.available() != 0) {
                throw new CoordinationException("trailing provider session terminal bytes");
            }
            return payload;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed provider session terminal payload", failure);
        }
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static void requireText(String value, String name) {
        CoordinationNullException.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256) {
            throw new CoordinationArgumentException(name + " is invalid");
        }
    }

    /**
     * Returns the typed identity of the provider session fenced by this payload.
     *
     * @return the typed identity of the provider session fenced by this payload.
     */
    ProviderSessionId sessionIdentity() {
        return new ProviderSessionId(sessionId);
    }

    /**
     * Returns the typed participant incarnation fenced by this payload.
     *
     * @return the typed participant incarnation fenced by this payload.
     */
    ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Encodes this payload using the bounded collaboration binary convention.
     *
     * @return encoded payload
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            TEXT.writeText(out, sessionId);
            TEXT.writeText(out, provider);
            TEXT.writeText(out, participant);
            TEXT.writeText(out, reason);
            out.writeLong(validatedRevision);
        });
    }
}
