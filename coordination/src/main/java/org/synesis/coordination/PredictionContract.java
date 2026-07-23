package org.synesis.coordination;

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
 * Immutable, bounded description of the capability a requester predicts.
 * Text is canonicalized as strict UTF-8 and the encoded contract is safe to
 * sign and persist.
 * @param predictionId prediction identifier
 * @param projectId project identifier
 * @param requesterNodeId requester node identifier
 * @param requesterSupervisorId requester supervisor identifier
 * @param requesterWorkerId requester worker identifier
 * @param requestingTaskId requesting task identifier
 * @param owningCapability semantic capability name
 * @param ownerNodeId owner node identifier
 * @param ownerSupervisorId owner supervisor identifier
 * @param protectedScopes protected scope names
 * @param baseProjectSequence project sequence used as the base
 * @param baseCommit base commit identifier
 * @param baseScopeHashes scope hashes used as the base
 * @param ownerIntentVersion owner intent version
 * @param purpose capability purpose
 * @param inputs input contract
 * @param outputs output contract
 * @param behavior behavior contract
 * @param errorSemantics error contract
 * @param sideEffects side-effect contract
 * @param invariants invariants
 * @param compatibility compatibility requirements
 * @param performance performance requirements
 * @param concurrency concurrency requirements
 * @param acceptanceTests acceptance tests
 * @param confidence confidence score
 * @param speculationRisk speculation risk score
 * @param expiresAtEpochMillis expiry timestamp
 */
public record PredictionContract(
        UUID predictionId,
        UUID projectId,
        String requesterNodeId,
        String requesterSupervisorId,
        String requesterWorkerId,
        UUID requestingTaskId,
        String owningCapability,
        String ownerNodeId,
        String ownerSupervisorId,
        List<String> protectedScopes,
        long baseProjectSequence,
        String baseCommit,
        List<String> baseScopeHashes,
        long ownerIntentVersion,
        String purpose,
        String inputs,
        String outputs,
        String behavior,
        String errorSemantics,
        String sideEffects,
        String invariants,
        String compatibility,
        String performance,
        String concurrency,
        List<String> acceptanceTests,
        int confidence,
        int speculationRisk,
        long expiresAtEpochMillis
) {
    /** Maximum encoded contract size. */
    public static final int MAX_ENCODED_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final int MAX_LIST_ENTRIES = 128;

    /** Validates identities, bounds, and deterministic collection ordering. */
    public PredictionContract {
        Objects.requireNonNull(predictionId, "prediction ID");
        Objects.requireNonNull(projectId, "project ID");
        requireText(requesterNodeId, "requester node ID");
        requireText(requesterSupervisorId, "requester supervisor ID");
        requireText(requesterWorkerId, "requester worker ID");
        Objects.requireNonNull(requestingTaskId, "requesting task ID");
        requireText(owningCapability, "owning capability");
        requireText(ownerNodeId, "owner node ID");
        requireText(ownerSupervisorId, "owner supervisor ID");
        protectedScopes = boundedList(protectedScopes, "protected scopes");
        baseScopeHashes = boundedList(baseScopeHashes, "base scope hashes");
        requireText(baseCommit, "base commit");
        requireText(purpose, "purpose");
        requireText(inputs, "inputs");
        requireText(outputs, "outputs");
        requireText(behavior, "behavior");
        requireText(errorSemantics, "error semantics");
        requireText(sideEffects, "side effects");
        requireText(invariants, "invariants");
        requireText(compatibility, "compatibility");
        requireText(performance, "performance");
        requireText(concurrency, "concurrency");
        acceptanceTests = boundedList(acceptanceTests, "acceptance tests");
        if (baseProjectSequence < 0 || ownerIntentVersion < 0 || expiresAtEpochMillis < 0) {
            throw new IllegalArgumentException("sequence, version, and expiry must be non-negative");
        }
        if (confidence < 0 || confidence > 100 || speculationRisk < 0 || speculationRisk > 100) {
            throw new IllegalArgumentException("confidence and risk must be between 0 and 100");
        }
    }

    /**
     * Returns deterministic UTF-8 bytes suitable for signing.
     * @return canonical encoded contract
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x53435031);
            out.writeByte(1);
            writeUuid(out, predictionId);
            writeUuid(out, projectId);
            String[] values = { requesterNodeId, requesterSupervisorId, requesterWorkerId, owningCapability,
                    ownerNodeId, ownerSupervisorId, baseCommit, purpose, inputs, outputs, behavior, errorSemantics,
                    sideEffects, invariants, compatibility, performance, concurrency };
            for (String value : values) writeText(out, value);
            writeUuid(out, requestingTaskId);
            writeList(out, protectedScopes);
            out.writeLong(baseProjectSequence);
            writeList(out, baseScopeHashes);
            out.writeLong(ownerIntentVersion);
            writeList(out, acceptanceTests);
            out.writeInt(confidence);
            out.writeInt(speculationRisk);
            out.writeLong(expiresAtEpochMillis);
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("contract exceeds bound");
            return encoded;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Decodes one bounded canonical contract.
     * @param encoded canonical contract bytes
     * @return decoded contract
     * @throws IOException when the bytes are malformed or unsupported
     */
    public static PredictionContract decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded contract");
        if (encoded.length > MAX_ENCODED_BYTES) throw new IOException("contract exceeds bound");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != 0x53435031 || in.readUnsignedByte() != 1) {
                throw new IOException("unsupported contract format");
            }
            UUID predictionId = readUuid(in);
            UUID projectId = readUuid(in);
            String requesterNodeId = readText(in);
            String requesterSupervisorId = readText(in);
            String requesterWorkerId = readText(in);
            String owningCapability = readText(in);
            String ownerNodeId = readText(in);
            String ownerSupervisorId = readText(in);
            String baseCommit = readText(in);
            String purpose = readText(in);
            String inputs = readText(in);
            String outputs = readText(in);
            String behavior = readText(in);
            String errorSemantics = readText(in);
            String sideEffects = readText(in);
            String invariants = readText(in);
            String compatibility = readText(in);
            String performance = readText(in);
            String concurrency = readText(in);
            UUID requestingTaskId = readUuid(in);
            List<String> protectedScopes = readList(in);
            long baseProjectSequence = in.readLong();
            List<String> baseScopeHashes = readList(in);
            long ownerIntentVersion = in.readLong();
            List<String> acceptanceTests = readList(in);
            int confidence = in.readInt();
            int speculationRisk = in.readInt();
            long expiresAtEpochMillis = in.readLong();
            if (in.available() != 0) throw new IOException("trailing contract bytes");
            return new PredictionContract(predictionId, projectId, requesterNodeId, requesterSupervisorId,
                    requesterWorkerId, requestingTaskId, owningCapability, ownerNodeId, ownerSupervisorId,
                    protectedScopes, baseProjectSequence, baseCommit, baseScopeHashes, ownerIntentVersion,
                    purpose, inputs, outputs, behavior, errorSemantics, sideEffects, invariants, compatibility,
                    performance, concurrency, acceptanceTests, confidence, speculationRisk, expiresAtEpochMillis);
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed prediction contract", failure);
        }
    }

    private static List<String> boundedList(List<String> values, String label) {
        Objects.requireNonNull(values, label);
        if (values.size() > MAX_LIST_ENTRIES) throw new IllegalArgumentException(label + " exceed bound");
        List<String> copy = List.copyOf(values);
        copy.forEach(value -> requireText(value, label + " entry"));
        return copy;
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " is empty or exceeds bound");
        }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_TEXT_BYTES) throw new IOException("text bound");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated text");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        values.forEach(value -> {
            try {
                writeText(out, value);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
    }

    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST_ENTRIES) throw new IOException("list bound");
        java.util.ArrayList<String> values = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(readText(in));
        return List.copyOf(values);
    }
}
