package org.synesis.coordination;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and decodes durable payload data for Stage 2B capability events.
 *
 * @param handle          public request handle
 * @param capability      capability identifier
 * @param requesterNodeId requester node identity
 * @param ownerNodeId     assigned owner node identity
 * @param contract        capability contract specification
 * @param state           current request lifecycle state
 * @param reason          optional rejection or revision reason
 * @since 1.0
 */
public record CapabilityRequestPayload(
        CapabilityRequestHandle handle,
        String capability,
        String requesterNodeId,
        String ownerNodeId,
        CapabilityContract contract,
        CapabilityLifecycleState state,
        String reason
) {

    private static final int MAGIC = 0x53435250; // "SCRP"

    /**
     * Compact constructor enforcing nullability and bounds.
     *
     * @param handle          public request handle
     * @param capability      capability identifier
     * @param requesterNodeId requester node identity
     * @param ownerNodeId     assigned owner node identity
     * @param contract        capability contract specification
     * @param state           current request lifecycle state
     * @param reason          optional rejection or revision reason
     */
    public CapabilityRequestPayload {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(state, "state");
        if (capability.isBlank() || capability.length() > 128) {
            throw new IllegalArgumentException("capability must be between 1 and 128 characters");
        }
    }

    /**
     * Encodes this payload into binary event format.
     *
     * @return encoded payload bytes
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1); // Version 1
            writeText(out, handle.value());
            writeText(out, capability);
            writeText(out, requesterNodeId);
            writeText(out, ownerNodeId);
            writeText(out, contract.inputs());
            writeText(out, contract.output());

            out.writeInt(contract.requiredBehavior().size());
            for (String b : contract.requiredBehavior()) {
                writeText(out, b);
            }

            out.writeInt(contract.acceptanceTests().size());
            for (String a : contract.acceptanceTests()) {
                writeText(out, a);
            }

            writeText(out, state.value());
            writeText(out, reason != null ? reason : "");
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes a binary payload.
     *
     * @param encoded encoded payload bytes
     * @return decoded payload instance
     * @throws IOException if binary payload is malformed
     */
    public static CapabilityRequestPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new IOException("Unsupported capability request payload format");
            }

            CapabilityRequestHandle handle = CapabilityRequestHandle.parse(readText(in));
            String capability = readText(in);
            String requesterNodeId = readText(in);
            String ownerNodeId = readText(in);
            String inputs = readText(in);
            String output = readText(in);

            int behaviorCount = in.readInt();
            List<String> requiredBehavior = new ArrayList<>(behaviorCount);
            for (int i = 0; i < behaviorCount; i++) {
                requiredBehavior.add(readText(in));
            }

            int testCount = in.readInt();
            List<String> acceptanceTests = new ArrayList<>(testCount);
            for (int i = 0; i < testCount; i++) {
                acceptanceTests.add(readText(in));
            }

            CapabilityContract contract = new CapabilityContract(inputs, output, requiredBehavior, acceptanceTests);
            CapabilityLifecycleState state = CapabilityLifecycleState.fromValue(readText(in));
            String reasonRaw = readText(in);
            String reason = reasonRaw.isBlank() ? null : reasonRaw;

            return new CapabilityRequestPayload(handle, capability, requesterNodeId, ownerNodeId, contract, state, reason);
        } catch (RuntimeException failure) {
            throw new IOException("Malformed capability request payload", failure);
        }
    }

    private static void writeText(DataOutputStream out, String val) throws IOException {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readText(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 64 * 1024) {
            throw new IOException("Invalid text length bound");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new IOException("Truncated text payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
