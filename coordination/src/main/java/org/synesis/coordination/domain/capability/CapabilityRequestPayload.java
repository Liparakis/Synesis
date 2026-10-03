package org.synesis.coordination.domain.capability;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Encodes and decodes durable payload data for Stage 2B capability events. Supports version 1 (node-only) and version 2
 * (worker and
 * supervisor aware).
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
public record CapabilityRequestPayload(CapabilityRequestHandle handle, String capability, String requesterNodeId,
                                       String requesterSupervisorId, String requesterWorkerId, String ownerNodeId,
                                       String ownerSupervisorId, String ownerWorkerId, UUID authorityLineageId,
                                       CapabilityContract contract, CapabilityLifecycleState state, String reason) {

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
        CoordinationNullException.requireNonNull(handle, "handle");
        CoordinationNullException.requireNonNull(capability, "capability");
        CoordinationNullException.requireNonNull(requesterNodeId, "requesterNodeId");
        requesterSupervisorId = requesterSupervisorId == null ? "" : requesterSupervisorId;
        requesterWorkerId = requesterWorkerId == null ? "" : requesterWorkerId;
        CoordinationNullException.requireNonNull(ownerNodeId, "ownerNodeId");
        ownerSupervisorId = ownerSupervisorId == null ? "" : ownerSupervisorId;
        ownerWorkerId = ownerWorkerId == null ? "" : ownerWorkerId;
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(contract, "contract");
        CoordinationNullException.requireNonNull(state, "state");
        if (capability.isBlank() || capability.length() > 128) {
            throw new CoordinationArgumentException("capability must be between 1 and 128 characters");
        }
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
    public CapabilityRequestPayload(CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason) {
        this(handle,
                capability,
                requesterNodeId,
                "",
                "",
                ownerNodeId,
                "",
                "",
                unresolvedLineage(handle),
                contract,
                state,
                reason);
    }

    /**
     * Constructs a node-only payload with an explicit authority lineage.
     *
     * @param handle             request handle
     * @param capability         capability identifier
     * @param requesterNodeId    requester node
     * @param ownerNodeId        owner node
     * @param authorityLineageId durable authority lineage
     * @param contract           capability contract
     * @param state              lifecycle state
     * @param reason             optional reason
     */
    public CapabilityRequestPayload(CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            UUID authorityLineageId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason) {
        this(handle,
                capability,
                requesterNodeId,
                "",
                "",
                ownerNodeId,
                "",
                "",
                authorityLineageId,
                contract,
                state,
                reason);
    }

    /**
     * Decodes a binary payload (supports historical Versions 1 and 2 and the current lineage-bearing Version 3).
     *
     * @param encoded encoded payload bytes
     * @return decoded payload instance
     * @throws IOException if binary payload is malformed
     */
    public static CapabilityRequestPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || (version != 1 && version != 2 && version != 3)) {
                throw new CoordinationException("Unsupported capability request payload format");
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
            authorityLineageId = version == 3 ? readUuid(in) : unresolvedLineage(handle);

            String inputs = readText(in);
            String output = readText(in);

            List<String> requiredBehavior = readTextList(in);
            List<String> acceptanceTests = readTextList(in);

            CapabilityContract contract = new CapabilityContract(inputs, output, requiredBehavior, acceptanceTests);
            CapabilityLifecycleState state = CapabilityLifecycleState.fromValue(readText(in));
            String reasonRaw = readText(in);
            String reason = reasonRaw.isBlank() ? null : reasonRaw;

            return new CapabilityRequestPayload(handle,
                    capability,
                    requesterNodeId,
                    requesterSupervisorId,
                    requesterWorkerId,
                    ownerNodeId,
                    ownerSupervisorId,
                    ownerWorkerId,
                    authorityLineageId,
                    contract,
                    state,
                    reason);
        } catch (RuntimeException failure) {
            throw new CoordinationException("Malformed capability request payload", failure);
        }
    }

    /**
     * Reads one count-prefixed list of text values from the payload.
     *
     * @param in input positioned at the list count
     * @return values in encoded order
     * @throws IOException when a text value is truncated or malformed
     */
    private static List<String> readTextList(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readText(in));
        }
        return values;
    }

    /**
     * Writes bounded UTF-8 text in the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeText(DataOutputStream out, String val) throws IOException {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 64 * 1024) {
            throw new CoordinationException("Invalid text length bound");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new CoordinationException("Truncated text payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    /**
     * Reads a UUID from the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    /**
     * Derives a stable lineage when an older payload omitted it.
     */
    private static UUID unresolvedLineage(CapabilityRequestHandle handle) {
        return UUID.nameUUIDFromBytes(("synesis-unresolved-capability-lineage:" + handle.value()).getBytes(
                StandardCharsets.UTF_8));
    }

    /**
     * Returns the requested capability as a typed identity.
     *
     * @return capability identity
     */
    public CapabilityId capabilityIdentity() {
        return new CapabilityId(capability);
    }

    /**
     * Returns the authenticated requester node identity.
     *
     * @return requester node identity
     */
    public NodeId requesterNodeIdentity() {
        return new NodeId(requesterNodeId);
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
            throw new CoordinationInvariantError(impossible);
        }
    }
}
