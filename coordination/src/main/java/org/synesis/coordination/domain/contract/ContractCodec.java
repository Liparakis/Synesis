package org.synesis.coordination.domain.contract;

import static org.synesis.coordination.domain.identity.WireFormat.encode;
import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Canonical payloads of the contract events. Magic values prevent one payload from being decoded
 * as another.
 */
public final class ContractCodec {

    private static final int MAGIC_PUBLISHED = 0x53435032;
    private static final int MAGIC_BOUND = 0x53434232;
    private static final int MAGIC_CHANGE_REQUESTED = 0x53435132;
    private static final int MAGIC_CHANGE_ANSWERED = 0x53434132;
    private static final int MAGIC_LANE_ENDED = 0x53434532;

    private static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final int MAX_LIST_ITEMS = 64;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, true, "contract text bound", "truncated contract text").checkingWrites(MAX_TEXT_BYTES, "contract text bound");

    private ContractCodec() {
    }

    /**
     * Encodes the publication of a contract revision.
     *
     * @param record the revision as published
     * @return canonical event payload
     */
    public static byte[] encodePublished(ContractRecord record) {
        return encode(MAGIC_PUBLISHED, out -> {
            writeUuid(out, record.contractId());
            writeUuid(out, record.ownerLineageId());
            out.writeLong(record.revision());
            writeList(out, record.paths().stream().map(ResourceSelector::encode).toList());
            CapabilityContract terms = record.terms();
            TEXT.writeText(out, terms.purpose());
            TEXT.writeText(out, terms.inputs());
            TEXT.writeText(out, terms.output());
            writeList(out, terms.requiredBehavior());
            TEXT.writeText(out, terms.errorSemantics());
            TEXT.writeText(out, terms.sideEffects());
            TEXT.writeText(out, terms.invariants());
            TEXT.writeText(out, terms.compatibility());
            TEXT.writeText(out, terms.performance());
            TEXT.writeText(out, terms.concurrency());
            writeList(out, terms.acceptanceTests());
        });
    }

    static ContractRecord decodePublished(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_PUBLISHED, in -> {
            UUID contractId = readUuid(in);
            UUID ownerLineageId = readUuid(in);
            long revision = in.readLong();
            List<ResourceSelector> paths = new ArrayList<>();
            for (String path : readList(in)) {
                paths.add(ResourceSelector.parse(path).orElseThrow(() -> new CoordinationException("contract path")));
            }
            String purpose = TEXT.readText(in), inputs = TEXT.readText(in), output = TEXT.readText(in);
            List<String> requiredBehavior = readList(in);
            String errorSemantics = TEXT.readText(in), sideEffects = TEXT.readText(in), invariants = TEXT.readText(in);
            String compatibility = TEXT.readText(in), performance = TEXT.readText(in), concurrency = TEXT.readText(in);
            List<String> acceptanceTests = readList(in);
            return new ContractRecord(contractId, ownerLineageId, revision, paths,
                    new CapabilityContract(purpose, inputs, output, requiredBehavior, errorSemantics, sideEffects, invariants, compatibility, performance, concurrency, acceptanceTests),
                    ContractRecord.State.ACTIVE);
        });
    }

    /**
     * Encodes a consumer's binding to one revision of a contract.
     *
     * @param binding the binding
     * @return canonical event payload
     */
    public static byte[] encodeBound(ContractBinding binding) {
        return encode(MAGIC_BOUND, out -> {
            TEXT.writeText(out, binding.handle());
            writeUuid(out, binding.contractId());
            out.writeLong(binding.revision());
        });
    }

    static ContractBinding decodeBound(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_BOUND, in -> new ContractBinding(TEXT.readText(in), readUuid(in), in.readLong()));
    }

    /**
     * Encodes the filing of a change request.
     *
     * @param request the open request
     * @return canonical event payload
     */
    public static byte[] encodeChangeRequested(ContractChangeRequest request) {
        return encode(MAGIC_CHANGE_REQUESTED, out -> {
            writeUuid(out, request.id());
            writeUuid(out, request.ownerLineageId());
            TEXT.writeText(out, request.path().encode());
            writeUuid(out, request.filerLineageId());
            TEXT.writeText(out, request.need());
            out.writeLong(request.baseRevision());
        });
    }

    static ContractChangeRequest decodeChangeRequested(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_CHANGE_REQUESTED, in -> {
            UUID id = readUuid(in);
            UUID ownerLineageId = readUuid(in);
            ResourceSelector path = ResourceSelector.parse(TEXT.readText(in)).orElseThrow(() -> new CoordinationException("change request path"));
            return ContractChangeRequest.open(id, ownerLineageId, path, readUuid(in), TEXT.readText(in), in.readLong());
        });
    }

    /**
     * Encodes the owner's answer to one change request.
     *
     * @param id       change request identifier
     * @param answer   {@code ACCEPTED} or {@code DECLINED}
     * @param revision revision that covers the need, or zero when declined
     * @param reason   why it was declined, or empty
     * @return canonical event payload
     */
    public static byte[] encodeChangeAnswered(UUID id, ContractChangeRequest.State answer, long revision, String reason) {
        return encode(MAGIC_CHANGE_ANSWERED, out -> {
            writeUuid(out, id);
            TEXT.writeText(out, answer.name());
            out.writeLong(revision);
            TEXT.writeText(out, reason == null ? "" : reason);
        });
    }

    static ChangeAnswer decodeChangeAnswered(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_CHANGE_ANSWERED, in -> new ChangeAnswer(readUuid(in), ContractChangeRequest.State.valueOf(TEXT.readText(in)), in.readLong(), TEXT.readText(in)));
    }

    /**
     * Encodes the end of a lane, for the contracts it owned and the change requests it filed.
     *
     * @param lineageId authority lineage of the lane that ended
     * @return canonical event payload
     */
    public static byte[] encodeLaneEnded(UUID lineageId) {
        return encode(MAGIC_LANE_ENDED, out -> writeUuid(out, lineageId));
    }

    static UUID decodeLaneEnded(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_LANE_ENDED, WireFormat::readUuid);
    }

    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            TEXT.writeText(out, value);
        }
    }

    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST_ITEMS) {
            throw new CoordinationException("contract list bound");
        }
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(TEXT.readText(in));
        }
        return values;
    }

    private static <T> T decode(byte[] encoded, int magic, Fields<T> fields) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != magic) {
                throw new CoordinationException("contract payload format");
            }
            T value = fields.read(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing contract payload bytes");
            }
            return value;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed contract payload", failure);
        }
    }

    @FunctionalInterface
    private interface Fields<T> {
        T read(DataInputStream in) throws IOException;
    }

    /**
     * The owner's answer to one change request.
     *
     * @param id       change request identifier
     * @param answer   how it was answered
     * @param revision revision that covers the need, or zero
     * @param reason   why it was declined, or empty
     */
    record ChangeAnswer(UUID id, ContractChangeRequest.State answer, long revision, String reason) {
    }
}
