package org.synesis.coordination.domain.capability;




import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Encodes and decodes durable payload data for Stage 2B capability events.
 * Supports version 1 (node-only) and version 2 (worker and supervisor aware).
 *
 * @param handle                public request handle
 * @param capability            capability identifier
 * @param requesterNodeId       requester node identity
 * @param requesterSupervisorId requester supervisor identity
 * @param requesterWorkerId     requester worker identity
 * @param ownerNodeId           assigned owner node identity
 * @param ownerSupervisorId     assigned owner supervisor identity
 * @param ownerWorkerId         assigned owner worker identity
 * @param authorityLineageId    durable authority lineage required by the request
 * @param contract              capability contract specification
 * @param state                 current request lifecycle state
 * @param reason                optional rejection or revision reason
 * @since 1.0
 */
public record CapabilityRequestPayload(
        CapabilityRequestHandle handle,
        String capability,
        String requesterNodeId,
        String requesterSupervisorId,
        String requesterWorkerId,
        String ownerNodeId,
        String ownerSupervisorId,
        String ownerWorkerId,
        UUID authorityLineageId,
        CapabilityContract contract,
        CapabilityLifecycleState state,
        String reason
) {

    private static final int MAGIC = 0x53435250; // "SCRP"

    /**
     * Compact constructor enforcing nullability and bounds.
     *
     * @param handle                public request handle
     * @param capability            capability identifier
     * @param requesterNodeId       requester node identity
     * @param requesterSupervisorId requester supervisor identity
     * @param requesterWorkerId     requester worker identity
     * @param ownerNodeId           assigned owner node identity
     * @param ownerSupervisorId     assigned owner supervisor identity
     * @param ownerWorkerId         assigned owner worker identity
     * @param authorityLineageId    durable authority lineage required by the request
     * @param contract              capability contract specification
     * @param state                 current request lifecycle state
     * @param reason                optional rejection or revision reason
     */
    public CapabilityRequestPayload {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        requesterSupervisorId = requesterSupervisorId == null ? "" : requesterSupervisorId;
        requesterWorkerId = requesterWorkerId == null ? "" : requesterWorkerId;
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        ownerSupervisorId = ownerSupervisorId == null ? "" : ownerSupervisorId;
        ownerWorkerId = ownerWorkerId == null ? "" : ownerWorkerId;
        Objects.requireNonNull(authorityLineageId, "authorityLineageId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(state, "state");
        if (capability.isBlank() || capability.length() > 128) {
            throw new IllegalArgumentException("capability must be between 1 and 128 characters");
        }
    }

    /** Constructs a worker-aware payload without an explicit lineage.
     * @param handle request handle
     * @param capability capability identifier
     * @param requesterNodeId requester node
     * @param requesterSupervisorId requester supervisor
     * @param requesterWorkerId requester worker
     * @param ownerNodeId owner node
     * @param ownerSupervisorId owner supervisor
     * @param ownerWorkerId owner worker
     * @param contract capability contract
     * @param state lifecycle state
     * @param reason optional reason
     */
    public CapabilityRequestPayload(
            CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String requesterSupervisorId,
            String requesterWorkerId,
            String ownerNodeId,
            String ownerSupervisorId,
            String ownerWorkerId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason
    ) {
        this(handle, capability, requesterNodeId, requesterSupervisorId, requesterWorkerId,
                ownerNodeId, ownerSupervisorId, ownerWorkerId, unresolvedLineage(handle),
                contract, state, reason);
    }

    /**
     * Convenience constructor for version 1 node-only payloads.
     *
     * @param handle          public request handle
     * @param capability      capability identifier
     * @param requesterNodeId requester node identity
     * @param ownerNodeId     assigned owner node identity
     * @param contract        capability contract specification
     * @param state           current request lifecycle state
     * @param reason          optional rejection or revision reason
     */
    public CapabilityRequestPayload(
            CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason
    ) {
        this(handle, capability, requesterNodeId, "", "", ownerNodeId, "", "",
                unresolvedLineage(handle), contract, state, reason);
    }

    /** Constructs a node-only payload with an explicit authority lineage.
     * @param handle request handle
     * @param capability capability identifier
     * @param requesterNodeId requester node
     * @param ownerNodeId owner node
     * @param authorityLineageId durable authority lineage
     * @param contract capability contract
     * @param state lifecycle state
     * @param reason optional reason
     */
    public CapabilityRequestPayload(
            CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            UUID authorityLineageId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason
    ) {
        this(handle, capability, requesterNodeId, "", "", ownerNodeId, "", "",
                authorityLineageId, contract, state, reason);
    }

    /**
     * Encodes this payload into binary event format (Version 3).
     *
     * @return encoded payload bytes
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(3); // Version 3
            writeText(out, handle.value());
            writeText(out, capability);
            writeText(out, requesterNodeId);
            writeText(out, requesterSupervisorId);
            writeText(out, requesterWorkerId);
            writeText(out, ownerNodeId);
            writeText(out, ownerSupervisorId);
            writeText(out, ownerWorkerId);
            writeUuid(out, authorityLineageId);
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
     * Decodes a binary payload (supports historical Versions 1 and 2 and the
     * current lineage-bearing Version 3).
     *
     * @param encoded encoded payload bytes
     * @return decoded payload instance
     * @throws IOException if binary payload is malformed
     */
    public static CapabilityRequestPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || (version != 1 && version != 2 && version != 3)) {
                throw new IOException("Unsupported capability request payload format");
            }

            CapabilityRequestHandle handle = CapabilityRequestHandle.parse(readText(in));
            String capability = readText(in);
            String requesterNodeId = readText(in);
            String requesterSupervisorId = "";
            String requesterWorkerId = "";
            String ownerNodeId;
            String ownerSupervisorId = "";
            String ownerWorkerId = "";
            UUID authorityLineageId;

            if (version == 1) {
                ownerNodeId = readText(in);
            } else {
                requesterSupervisorId = readText(in);
                requesterWorkerId = readText(in);
                ownerNodeId = readText(in);
                ownerSupervisorId = readText(in);
                ownerWorkerId = readText(in);
            }
            authorityLineageId = version >= 3 ? readUuid(in) : unresolvedLineage(handle);

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

            return new CapabilityRequestPayload(
                    handle, capability,
                    requesterNodeId, requesterSupervisorId, requesterWorkerId,
                    ownerNodeId, ownerSupervisorId, ownerWorkerId, authorityLineageId,
                    contract, state, reason
            );
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

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static UUID unresolvedLineage(CapabilityRequestHandle handle) {
        return UUID.nameUUIDFromBytes(("synesis-unresolved-capability-lineage:" + handle.value())
                .getBytes(StandardCharsets.UTF_8));
    }
}
