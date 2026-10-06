package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable amendment of one active intent's claims and task description.
 *
 * @param intent amended intent at the successor claim epoch
 * @param expectedClaimEpoch current epoch required for the amendment
 */
public record WorkIntentScopeExtension(WorkIntent intent, long expectedClaimEpoch) {

    private static final int MAGIC = 0x53584531;
    private static final int MAX_INTENT_BYTES = 64 * 1024 - 16;

    /**
     * Validates the exact epoch fence.
     */
    public WorkIntentScopeExtension {
        CoordinationNullException.requireNonNull(intent, "intent");
        if (expectedClaimEpoch < 1 || intent.version() != expectedClaimEpoch + 1) {
            throw new IllegalArgumentException("scope extension epoch must advance by one");
        }
    }

    /**
     * Encodes the amendment and its expected source epoch.
     *
     * @return canonical payload bytes
     */
    public byte[] encode() {
        try {
            byte[] encodedIntent = CollaborationCodec.encodeIntent(intent);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeLong(expectedClaimEpoch);
            out.writeInt(encodedIntent.length);
            out.write(encodedIntent);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory scope extension encoding failed", impossible);
        }
    }

    /**
     * Decodes a versioned extension while leaving historical intent formats untouched.
     *
     * @param encoded durable event payload
     * @return decoded extension
     * @throws IOException when the payload is malformed
     */
    public static WorkIntentScopeExtension decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported work-intent scope extension format");
            }
            long expectedEpoch = in.readLong();
            int length = in.readInt();
            if (length < 1 || length > MAX_INTENT_BYTES || length > in.available()) {
                throw new CoordinationException("work-intent scope extension payload bound");
            }
            byte[] intentBytes = in.readNBytes(length);
            if (in.available() != 0) {
                throw new CoordinationException("trailing work-intent scope extension bytes");
            }
            return new WorkIntentScopeExtension(CollaborationCodec.decodeIntent(intentBytes), expectedEpoch);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed work-intent scope extension", failure);
        }
    }
}
