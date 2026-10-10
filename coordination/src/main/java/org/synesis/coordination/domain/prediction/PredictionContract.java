package org.synesis.coordination.domain.prediction;

import static org.synesis.coordination.domain.identity.WireFormat.encode;
import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;

/**
 * Immutable, bounded description of the capability a requester predicts. Text is canonicalized as strict UTF-8 and the
 * encoded contract is
 * safe to sign and persist.
 *
 * @param predictionId          prediction identifier
 * @param projectId             project identifier
 * @param requesterNodeId       requester node identifier
 * @param requesterSupervisorId requester supervisor identifier
 * @param requesterWorkerId     requester worker identifier
 * @param requestingTaskId      requesting task identifier
 * @param owningCapability      semantic capability name
 * @param ownerNodeId           owner node identifier
 * @param ownerSupervisorId     owner supervisor identifier
 * @param protectedScopes       protected scope names
 * @param baseProjectSequence   project sequence used as the base
 * @param baseCommit            base commit identifier
 * @param baseScopeHashes       scope hashes used as the base
 * @param ownerIntentVersion    owner intent version
 * @param purpose               capability purpose
 * @param inputs                input contract
 * @param outputs               output contract
 * @param behavior              behavior contract
 * @param errorSemantics        error contract
 * @param sideEffects           side-effect contract
 * @param invariants            invariants
 * @param compatibility         compatibility requirements
 * @param performance           performance requirements
 * @param concurrency           concurrency requirements
 * @param acceptanceTests       acceptance tests
 * @param confidence            confidence score
 * @param speculationRisk       speculation risk score
 * @param expiresAtEpochMillis  expiry timestamp
 */
@SuppressWarnings("DuplicatedCode")
public record PredictionContract(UUID predictionId, UUID projectId, String requesterNodeId,
                                 String requesterSupervisorId, String requesterWorkerId, UUID requestingTaskId,
                                 String owningCapability, String ownerNodeId, String ownerSupervisorId,
                                 List<String> protectedScopes, long baseProjectSequence, String baseCommit,
                                 List<String> baseScopeHashes, long ownerIntentVersion, String purpose, String inputs,
                                 String outputs, String behavior, String errorSemantics, String sideEffects,
                                 String invariants, String compatibility, String performance, String concurrency,
                                 List<String> acceptanceTests, int confidence, int speculationRisk,
                                 long expiresAtEpochMillis) {

    /**
     * Maximum encoded contract size.
     */
    static final int MAX_ENCODED_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, false, "text bound", "truncated text");
    private static final int MAX_LIST_ENTRIES = 128;

    /**
     * Validates identities, bounds, and deterministic collection ordering.
     */
    public PredictionContract {
        CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        CoordinationNullException.requireNonNull(projectId, "project ID");
        requireText(requesterNodeId, "requester node ID");
        requireText(requesterSupervisorId, "requester supervisor ID");
        requireText(requesterWorkerId, "requester worker ID");
        CoordinationNullException.requireNonNull(requestingTaskId, "requesting task ID");
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
            throw new CoordinationArgumentException("sequence, version, and expiry must be non-negative");
        }
        if (confidence < 0 || confidence > 100 || speculationRisk < 0 || speculationRisk > 100) {
            throw new CoordinationArgumentException("confidence and risk must be between 0 and 100");
        }
    }

    /**
     * Decodes one bounded canonical contract.
     *
     * @param encoded canonical contract bytes
     * @return decoded contract
     * @throws IOException when the bytes are malformed or unsupported
     */
    public static PredictionContract decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded contract");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new CoordinationException("contract exceeds bound");
        }
        try {
            return readCanonicalPayload(encoded);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed prediction contract", failure);
        }
    }

    /**
     * Reads the canonical contract fields after the public decoder checks the input bound.
     *
     * @param encoded canonical contract bytes
     * @return decoded contract
     * @throws IOException when the header or field sequence is malformed
     */
    private static PredictionContract readCanonicalPayload(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != 0x53435031 || in.readUnsignedByte() != 1) {
            throw new CoordinationException("unsupported contract format");
        }
        UUID predictionId = readUuid(in);
        UUID projectId = readUuid(in);
        String requesterNodeId = TEXT.readText(in);
        String requesterSupervisorId = TEXT.readText(in);
        String requesterWorkerId = TEXT.readText(in);
        String owningCapability = TEXT.readText(in);
        String ownerNodeId = TEXT.readText(in);
        String ownerSupervisorId = TEXT.readText(in);
        String baseCommit = TEXT.readText(in);
        String purpose = TEXT.readText(in);
        String inputs = TEXT.readText(in);
        String outputs = TEXT.readText(in);
        String behavior = TEXT.readText(in);
        String errorSemantics = TEXT.readText(in);
        String sideEffects = TEXT.readText(in);
        String invariants = TEXT.readText(in);
        String compatibility = TEXT.readText(in);
        String performance = TEXT.readText(in);
        String concurrency = TEXT.readText(in);
        UUID requestingTaskId = readUuid(in);
        List<String> protectedScopes = readList(in);
        long baseProjectSequence = in.readLong();
        List<String> baseScopeHashes = readList(in);
        long ownerIntentVersion = in.readLong();
        List<String> acceptanceTests = readList(in);
        int confidence = in.readInt();
        int speculationRisk = in.readInt();
        long expiresAtEpochMillis = in.readLong();
        if (in.available() != 0) {
            throw new CoordinationException("trailing contract bytes");
        }
        return new PredictionContract(predictionId,
                projectId,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                requestingTaskId,
                owningCapability,
                ownerNodeId,
                ownerSupervisorId,
                protectedScopes,
                baseProjectSequence,
                baseCommit,
                baseScopeHashes,
                ownerIntentVersion,
                purpose,
                inputs,
                outputs,
                behavior,
                errorSemantics,
                sideEffects,
                invariants,
                compatibility,
                performance,
                concurrency,
                acceptanceTests,
                confidence,
                speculationRisk,
                expiresAtEpochMillis);
    }

    /**
     * Copies a list after checking its maximum size and validating each bounded value.
     */
    private static List<String> boundedList(List<String> values, String label) {
        CoordinationNullException.requireNonNull(values, label);
        if (values.size() > MAX_LIST_ENTRIES) {
            throw new CoordinationArgumentException(label + " exceed bound");
        }
        List<String> copy = List.copyOf(values);
        copy.forEach(value -> requireText(value, label + " entry"));
        return copy;
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
     * Writes a bounded list using the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        values.forEach(value -> {
            try {
                TEXT.writeText(out, value);
            } catch (IOException impossible) {
                throw new CoordinationStateException(impossible);
            }
        });
    }

    /**
     * Reads a bounded list from the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST_ENTRIES) {
            throw new CoordinationException("list bound");
        }
        ArrayList<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(TEXT.readText(in));
        }
        return List.copyOf(values);
    }

    /**
     * Returns the prediction stream identity.
     *
     * @return the prediction stream identity.
     */
    public PredictionId predictionIdentity() {
        return new PredictionId(predictionId);
    }

    /**
     * Returns the project identity.
     *
     * @return the project identity.
     */
    public ProjectId projectIdentity() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the requester node identity.
     *
     * @return the requester node identity.
     */
    public NodeId requesterNode() {
        return new NodeId(requesterNodeId);
    }

    /**
     * Returns the requester supervisor identity.
     *
     * @return the requester supervisor identity.
     */
    public SupervisorId requesterSupervisor() {
        return new SupervisorId(requesterSupervisorId);
    }

    /**
     * Returns the requester worker identity.
     *
     * @return the requester worker identity.
     */
    public WorkerId requesterWorker() {
        return new WorkerId(requesterWorkerId);
    }

    /**
     * Returns the owner node identity.
     *
     * @return the owner node identity.
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
     * Returns deterministic UTF-8 bytes suitable for signing.
     *
     * @return canonical encoded contract
     */
    public byte[] encoded() {
        byte[] encoded = encode(0x53435031, out -> {
            out.writeByte(1);
            writeUuid(out, predictionId);
            writeUuid(out, projectId);
            String[] values = {requesterNodeId, requesterSupervisorId, requesterWorkerId, owningCapability, ownerNodeId,
                    ownerSupervisorId, baseCommit, purpose, inputs, outputs, behavior, errorSemantics, sideEffects,
                    invariants, compatibility, performance, concurrency};
            for (String value : values) {
                TEXT.writeText(out, value);
            }
            writeUuid(out, requestingTaskId);
            writeList(out, protectedScopes);
            out.writeLong(baseProjectSequence);
            writeList(out, baseScopeHashes);
            out.writeLong(ownerIntentVersion);
            writeList(out, acceptanceTests);
            out.writeInt(confidence);
            out.writeInt(speculationRisk);
            out.writeLong(expiresAtEpochMillis);
        });
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new CoordinationArgumentException("contract exceeds bound");
        }
        return encoded;
    }
}
