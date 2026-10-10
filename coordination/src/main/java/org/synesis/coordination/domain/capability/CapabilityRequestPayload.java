package org.synesis.coordination.domain.capability;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Encodes and decodes durable payload data for capability events. The payload stores
 * the complete API contract and the contract revision expected by a state-changing response.
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
 * @param expectedContractRevision revision required by a response
 * @since 1.0
 */
public record CapabilityRequestPayload(CapabilityRequestHandle handle, String capability, String requesterNodeId,
                                       String requesterSupervisorId, String requesterWorkerId, String ownerNodeId,
                                       String ownerSupervisorId, String ownerWorkerId, UUID authorityLineageId,
                                       CapabilityContract contract, CapabilityLifecycleState state, String reason,
                                       long expectedContractRevision) {

    private static final int MAGIC = 0x53435250; // "SCRP"
    private static final int VERSION = 4;
    private static final int MAX_ENCODED_BYTES = 64 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(64 * 1024,
            true,
            "Invalid text length bound",
            "Truncated text payload");

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
     * @param expectedContractRevision revision being answered
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
        if (expectedContractRevision < 0) {
            throw new CoordinationArgumentException("expectedContractRevision must not be negative");
        }
        if (capability.isBlank() || capability.length() > 128) {
            throw new CoordinationArgumentException("capability must be between 1 and 128 characters");
        }
    }

    /**
     * Constructs a node-only payload with an explicit authority lineage and contract revision fence.
     *
     * @param handle public request handle
     * @param capability requested capability identifier
     * @param requesterNodeId authenticated requester node
     * @param ownerNodeId assigned owner node
     * @param authorityLineageId durable authority lineage
     * @param contract complete negotiated capability contract
     * @param state request lifecycle state
     * @param reason optional rejection or revision reason
     * @param expectedContractRevision revision the response is based on
     */
    public CapabilityRequestPayload(CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            UUID authorityLineageId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason,
            long expectedContractRevision) {
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
                reason,
                expectedContractRevision);
    }

    /**
     * Decodes a complete-contract payload.
     *
     * @param encoded encoded payload bytes
     * @return decoded payload instance
     * @throws IOException if binary payload is malformed
     */
    public static CapabilityRequestPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new CoordinationException("Capability request payload exceeds 64 KB");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new CoordinationException("Unsupported capability request payload format");
            }

            CapabilityRequestHandle handle = CapabilityRequestHandle.parse(TEXT.readText(in));
            String capability = TEXT.readText(in);
            String requesterNodeId = TEXT.readText(in);
            String requesterSupervisorId = TEXT.readText(in);
            String requesterWorkerId = TEXT.readText(in);
            String ownerNodeId = TEXT.readText(in);
            String ownerSupervisorId = TEXT.readText(in);
            String ownerWorkerId = TEXT.readText(in);
            UUID authorityLineageId = readUuid(in);
            long expectedContractRevision = in.readLong();

            String purpose = TEXT.readText(in);
            String inputs = TEXT.readText(in);
            String output = TEXT.readText(in);
            List<String> requiredBehavior = readTextList(in);
            List<String> acceptanceTests = readTextList(in);
            String errorSemantics = TEXT.readText(in);
            String sideEffects = TEXT.readText(in);
            String invariants = TEXT.readText(in);
            String compatibility = TEXT.readText(in);
            String performance = TEXT.readText(in);
            String concurrency = TEXT.readText(in);
            CapabilityContract contract = new CapabilityContract(purpose,
                    inputs,
                    output,
                    requiredBehavior,
                    errorSemantics,
                    sideEffects,
                    invariants,
                    compatibility,
                    performance,
                    concurrency,
                    acceptanceTests);
            CapabilityLifecycleState state = CapabilityLifecycleState.fromValue(TEXT.readText(in));
            String reasonRaw = TEXT.readText(in);
            String reason = reasonRaw.isBlank() ? null : reasonRaw;
            if (in.available() != 0) {
                throw new CoordinationException("Trailing capability request payload bytes");
            }

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
                    reason,
                    expectedContractRevision);
        } catch (RuntimeException | EOFException failure) {
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
        if (count < 0 || count > 16) {
            throw new CoordinationException("Invalid text list bound");
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(TEXT.readText(in));
        }
        return values;
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
     * Encodes this payload into binary event format.
     *
     * @return encoded payload bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            out.writeInt(VERSION);
            TEXT.writeText(out, handle.value());
            TEXT.writeText(out, capability);
            TEXT.writeText(out, requesterNodeId);
            TEXT.writeText(out, requesterSupervisorId);
            TEXT.writeText(out, requesterWorkerId);
            TEXT.writeText(out, ownerNodeId);
            TEXT.writeText(out, ownerSupervisorId);
            TEXT.writeText(out, ownerWorkerId);
            writeUuid(out, authorityLineageId);
            out.writeLong(expectedContractRevision);
            TEXT.writeText(out, contract.purpose());
            TEXT.writeText(out, contract.inputs());
            TEXT.writeText(out, contract.output());

            out.writeInt(contract.requiredBehavior().size());
            for (String b : contract.requiredBehavior()) {
                TEXT.writeText(out, b);
            }

            out.writeInt(contract.acceptanceTests().size());
            for (String a : contract.acceptanceTests()) {
                TEXT.writeText(out, a);
            }

            TEXT.writeText(out, contract.errorSemantics());
            TEXT.writeText(out, contract.sideEffects());
            TEXT.writeText(out, contract.invariants());
            TEXT.writeText(out, contract.compatibility());
            TEXT.writeText(out, contract.performance());
            TEXT.writeText(out, contract.concurrency());

            TEXT.writeText(out, state.value());
            TEXT.writeText(out, reason != null ? reason : "");
        });
    }
}
