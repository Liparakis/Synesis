package org.synesis.coordination.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
public record OwnershipClaim(UUID taskId, String capability, String ownerNodeId, String ownerSupervisorId,
                             List<String> protectedScopes, long intentVersion) {

    private static final int MAGIC = 0x534f4331;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final int MAX_LIST_ENTRIES = 128;

    /**
     * Validates ownership identity, scope bounds, and intent version.
     */
    public OwnershipClaim {
        Objects.requireNonNull(taskId, "task ID");
        requireText(capability, "capability");
        requireText(ownerNodeId, "owner node ID");
        requireText(ownerSupervisorId, "owner supervisor ID");
        Objects.requireNonNull(protectedScopes, "protected scopes");
        if (protectedScopes.size() > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("protected scopes exceed bound");
        }
        protectedScopes = List.copyOf(protectedScopes);
        protectedScopes.forEach(scope -> requireText(scope, "scope"));
        if (intentVersion < 0) {
            throw new IllegalArgumentException("intent version must be non-negative");
        }
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
                throw new IOException("unsupported ownership format");
            }
            UUID taskId = new UUID(in.readLong(), in.readLong());
            String capability = readText(in);
            String node = readText(in);
            String supervisor = readText(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_LIST_ENTRIES) {
                throw new IOException("scope bound");
            }
            java.util.ArrayList<String> scopes = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                scopes.add(readText(in));
            }
            OwnershipClaim claim = new OwnershipClaim(taskId, capability, node, supervisor, scopes, in.readLong());
            if (in.available() != 0) {
                throw new IOException("trailing ownership bytes");
            }
            return claim;
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed ownership claim", failure);
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " is empty or exceeds bound");
        }
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_TEXT_BYTES) {
            throw new IOException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
            throw new AssertionError(impossible);
        }
    }
}
