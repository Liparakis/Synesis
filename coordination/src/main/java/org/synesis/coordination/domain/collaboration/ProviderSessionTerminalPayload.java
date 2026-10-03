package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
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
            ProviderSessionTerminalPayload payload = new ProviderSessionTerminalPayload(readText(in),
                    readText(in),
                    readText(in),
                    readText(in),
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
     * Encodes bounded UTF-8 text with its byte length in the terminal payload.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 256) {
            throw new CoordinationException("terminal payload text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated terminal payload text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
    public ProviderSessionId sessionIdentity() {
        return new ProviderSessionId(sessionId);
    }

    /**
     * Returns the typed participant incarnation fenced by this payload.
     *
     * @return the typed participant incarnation fenced by this payload.
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Encodes this payload using the bounded collaboration binary convention.
     *
     * @return encoded payload
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            text(out, sessionId);
            text(out, provider);
            text(out, participant);
            text(out, reason);
            out.writeLong(validatedRevision);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
