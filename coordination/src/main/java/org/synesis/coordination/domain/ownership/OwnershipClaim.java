package org.synesis.coordination.domain.ownership;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.IntentVersion;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable semantic ownership claim for one task capability.
 *
 * @param taskId            task identifier
 * @param capability        capability name
 * @param ownerNodeId       owning node identity
 * @param ownerSupervisorId owning supervisor identity
 * @param protectedScopes   protected scope patterns
 * @param intentVersion     ownership intent version
 */
@SuppressWarnings("DuplicatedCode")
public record OwnershipClaim(UUID taskId, String capability, String ownerNodeId, String ownerSupervisorId,
                             List<String> protectedScopes, long intentVersion) {

    private static final int MAGIC = 0x534f4331;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final int MAX_LIST_ENTRIES = 128;

    /**
     * Validates ownership identity, scope bounds, and intent version.
     */
    public OwnershipClaim {
        CoordinationNullException.requireNonNull(taskId, "task ID");
        requireText(capability, "capability");
        requireText(ownerNodeId, "owner node ID");
        requireText(ownerSupervisorId, "owner supervisor ID");
        CoordinationNullException.requireNonNull(protectedScopes, "protected scopes");
        if (protectedScopes.size() > MAX_LIST_ENTRIES) {
            throw new CoordinationArgumentException("protected scopes exceed bound");
        }
        protectedScopes = List.copyOf(protectedScopes);
        protectedScopes.forEach(scope -> requireText(scope, "scope"));
        if (intentVersion < 0) {
            throw new CoordinationArgumentException("intent version must be non-negative");
        }
    }

    /**
     * Creates a claim using identity types for its task, capability, owner, and version.
     *
     * @param taskId            claimed task
     * @param capability        capability being reserved
     * @param ownerNodeId       owning node
     * @param ownerSupervisorId owning supervisor
     * @param protectedScopes   protected scope patterns
     * @param intentVersion     ownership intent version
     */
    public OwnershipClaim(TaskId taskId,
            CapabilityId capability,
            NodeId ownerNodeId,
            SupervisorId ownerSupervisorId,
            List<String> protectedScopes,
            IntentVersion intentVersion) {
        this(CoordinationNullException.requireNonNull(taskId, "task ID").value(),
                CoordinationNullException.requireNonNull(capability, "capability ID").value(),
                CoordinationNullException.requireNonNull(ownerNodeId, "owner node ID").value(),
                CoordinationNullException.requireNonNull(ownerSupervisorId, "owner supervisor ID").value(),
                protectedScopes,
                CoordinationNullException.requireNonNull(intentVersion, "intent version").value());
    }

    /**
     * Decodes one ownership claim.
     *
     * @param encoded canonical bytes
     * @return claim
     * @throws IOException malformed or unsupported bytes
     */
    public static OwnershipClaim decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new CoordinationException("unsupported ownership format");
            }
            UUID taskId = new UUID(in.readLong(), in.readLong());
            String capability = readText(in);
            String node = readText(in);
            String supervisor = readText(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_LIST_ENTRIES) {
                throw new CoordinationException("scope bound");
            }
            ArrayList<String> scopes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                scopes.add(readText(in));
            }
            OwnershipClaim claim = new OwnershipClaim(taskId, capability, node, supervisor, scopes, in.readLong());
            if (in.available() != 0) {
                throw new CoordinationException("trailing ownership bytes");
            }
            return claim;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed ownership claim", failure);
        }
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static void requireText(String value, String label) {
        CoordinationNullException.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new CoordinationArgumentException(label + " is empty or exceeds bound");
        }
    }

    /**
     * Writes bounded UTF-8 text in the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeText(DataOutputStream out, String value) throws IOException {
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
        if (length < 1 || length > MAX_TEXT_BYTES) {
            throw new CoordinationException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the claimed task identity.
     *
     * @return the claimed task identity.
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the capability ownership key with its domain type.
     *
     * @return the capability ownership key with its domain type.
     */
    public CapabilityId capabilityIdentity() {
        return new CapabilityId(capability);
    }

    /**
     * Returns the authenticated owner node identity.
     *
     * @return the authenticated owner node identity.
     */
    public NodeId ownerNode() {
        return new NodeId(ownerNodeId);
    }

    /**
     * Returns the owner supervisor identity.
     *
     * @return the owner supervisor identity.
     */
    public SupervisorId ownerSupervisor() {
        return new SupervisorId(ownerSupervisorId);
    }

    /**
     * Encodes the claim for a signed command payload.
     *
     * @return canonical bytes
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeLong(taskId.getMostSignificantBits());
            out.writeLong(taskId.getLeastSignificantBits());
            writeText(out, capability);
            writeText(out, ownerNodeId);
            writeText(out, ownerSupervisorId);
            out.writeInt(protectedScopes.size());
            for (String scope : protectedScopes) {
                writeText(out, scope);
            }
            out.writeLong(intentVersion);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
