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
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Bounded canonical encoding for contract events.
 */
public final class ContractCodec {

    private static final int MAGIC_PUBLISH = 0x53435031;
    private static final int MAGIC_DEPEND = 0x53434431;
    private static final int MAGIC_SUPERSEDE = 0x53435331;

    private static final WireFormat TEXT = WireFormat.bounded(32768, false, "text bound", "truncated text")
            .checkingWrites(32768, "text bound");

    /**
     * Validates and initializes this contract codec.
     */
    private ContractCodec() {
    }

    /**
     * Encodes a contract publication.
     *
     * @param record contract record
     * @return canonical event payload
     */
    public static byte[] encodePublish(ContractRecord record) {
        return encode(MAGIC_PUBLISH, out -> {
            writeUuid(out, record.contractId());
            writeUuid(out, record.projectId());
            out.writeLong(record.revision());
            TEXT.writeText(out, record.owner());
            TEXT.writeText(out, record.contentHash());
            TEXT.writeText(out, record.body());
            uuidNullable(out, record.supersedes());
            out.writeInt(record.selectorRefs().size());
            for (String ref : record.selectorRefs()) {
                TEXT.writeText(out, ref);
            }
        });
    }

    /**
     * Decodes a contract publication.
     *
     * @param encoded event payload
     * @return decoded record
     * @throws IOException if malformed
     */
    public static ContractRecord decodePublish(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_PUBLISH) {
                throw new CoordinationException("contract format");
            }
            UUID id = readUuid(in), project = readUuid(in);
            long revision = in.readLong();
            String owner = TEXT.readText(in), hash = TEXT.readText(in), body = TEXT.readText(in);
            UUID supersedes = uuidNullable(in);
            int count = in.readInt();
            if (count < 0 || count > 128) {
                throw new CoordinationException("selector bound");
            }
            List<String> refs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                refs.add(TEXT.readText(in));
            }
            if (in.available() != 0) {
                throw new CoordinationException("trailing contract bytes");
            }
            return new ContractRecord(id,
                    project,
                    revision,
                    owner,
                    hash,
                    body,
                    ContractRecord.Status.ACTIVE,
                    supersedes,
                    refs);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed contract", failure);
        }
    }

    /**
     * Encodes an explicit contract dependency.
     *
     * @param dependency dependency
     * @return canonical event payload
     */
    public static byte[] encodeDependency(ContractDependency dependency) {
        return encode(MAGIC_DEPEND, out -> {
            writeUuid(out, dependency.intentId());
            TEXT.writeText(out, dependency.participant());
            writeUuid(out, dependency.contractId());
            out.writeLong(dependency.revision());
        });
    }

    /**
     * Decodes an explicit contract dependency.
     *
     * @param encoded event payload
     * @return decoded dependency
     * @throws IOException if malformed
     */
    public static ContractDependency decodeDependency(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_DEPEND) {
                throw new CoordinationException("dependency format");
            }
            UUID intent = readUuid(in);
            String participant = TEXT.readText(in);
            UUID contract = readUuid(in);
            long revision = in.readLong();
            if (in.available() != 0) {
                throw new CoordinationException("trailing dependency bytes");
            }
            return new ContractDependency(intent, participant, contract, revision, ContractDependency.State.ACCEPTED);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed dependency", failure);
        }
    }

    /**
     * Encodes a contract supersession.
     *
     * @param contractId  contract identifier
     * @param oldRevision replaced revision
     * @param newRevision replacement revision
     * @return canonical event payload
     */
    public static byte[] encodeSupersede(UUID contractId, long oldRevision, long newRevision) {
        return encode(MAGIC_SUPERSEDE, out -> {
            writeUuid(out, contractId);
            out.writeLong(oldRevision);
            out.writeLong(newRevision);
        });
    }

    /**
     * Decodes a supersession.
     *
     * @param encoded event payload
     * @return decoded supersession
     * @throws IOException if malformed
     */
    public static ContractSupersede decodeSupersede(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC_SUPERSEDE) {
            throw new CoordinationException("supersession format");
        }
        UUID id = readUuid(in);
        long oldRevision = in.readLong(), newRevision = in.readLong();
        if (in.available() != 0) {
            throw new CoordinationException("trailing supersession bytes");
        }
        return new ContractSupersede(id, oldRevision, newRevision);
    }

    /**
     * Encodes a nullable UUID with a presence marker followed by its two 64-bit halves.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void uuidNullable(DataOutputStream out, UUID id) throws IOException {
        out.writeBoolean(id != null);
        if (id != null) {
            writeUuid(out, id);
        }
    }

    /**
     * Decodes a nullable UUID from its presence marker and two 64-bit halves.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static UUID uuidNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? readUuid(in) : null;
    }

}
