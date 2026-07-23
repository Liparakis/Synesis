package org.synesis.coordination;

import java.io.ByteArrayOutputStream;
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

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
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
}
