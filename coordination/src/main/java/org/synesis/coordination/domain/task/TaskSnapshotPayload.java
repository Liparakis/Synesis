package org.synesis.coordination.domain.task;

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
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Binary codec for Stage 2B Slice 3 task snapshot events.
 *
 * @param taskId                 task UUID
 * @param snapshotId             snapshot locator ID
 * @param nodeId                 worker node ID
 * @param supervisorId           worker supervisor ID
 * @param workerId               worker ID
 * @param providerSessionId      provider session ID
 * @param baseCommit             base commit SHA
 * @param commitSha              commit SHA
 * @param changedPaths           list of changed paths
 * @param capabilityDependencies list of capability request handles
 * @param summary                task completion summary
 * @param provenance             immutable lane provenance
 * @param reviewRequired         whether exact durable review acceptance gates integration
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public record TaskSnapshotPayload(UUID taskId, String snapshotId, String nodeId, String supervisorId, String workerId,
                                  String providerSessionId, String baseCommit, String commitSha,
                                  List<String> changedPaths, List<String> capabilityDependencies, String summary,
                                  SnapshotProvenance provenance, boolean reviewRequired) {

    private static final int MAGIC = 0x534E4150; // "SNAP"
    private static final int VERSION = 5;
    private static final int MAX_TEXT = 64 * 1024;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT,
            true,
            "Invalid text length in payload",
            "Truncated payload").checkingWrites(MAX_TEXT, "text exceeds payload bound");

    /**
     * Compact constructor.
     *
     * @param taskId                 task UUID
     * @param snapshotId             snapshot locator ID
     * @param nodeId                 worker node ID
     * @param supervisorId           worker supervisor ID
     * @param workerId               worker ID
     * @param providerSessionId      provider session ID
     * @param baseCommit             base commit SHA
     * @param commitSha              commit SHA
     * @param changedPaths           list of changed paths
     * @param capabilityDependencies list of capability dependencies
     * @param summary                task completion summary
     * @param provenance             immutable lane provenance
     * @param reviewRequired         whether review acceptance gates integration
     */
    public TaskSnapshotPayload {
        CoordinationNullException.requireNonNull(taskId, "taskId");
        CoordinationNullException.requireNonNull(snapshotId, "snapshotId");
        CoordinationNullException.requireNonNull(nodeId, "nodeId");
        CoordinationNullException.requireNonNull(supervisorId, "supervisorId");
        CoordinationNullException.requireNonNull(workerId, "workerId");
        CoordinationNullException.requireNonNull(providerSessionId, "providerSessionId");
        CoordinationNullException.requireNonNull(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(CoordinationNullException.requireNonNull(changedPaths, "changedPaths"));
        capabilityDependencies = List.copyOf(CoordinationNullException.requireNonNull(capabilityDependencies,
                "capabilityDependencies"));
        CoordinationNullException.requireNonNull(summary, "summary");
        CoordinationNullException.requireNonNull(provenance, "provenance");
        for (String text : List.of(snapshotId, nodeId, supervisorId, workerId, providerSessionId, baseCommit, commitSha,
                summary, provenance.participant(), provenance.bindingIdentity(), provenance.snapshotRef(),
                provenance.integrityEvidence(), provenance.artifactManifestDigest())) {
            TEXT.requireWritable(text, "text");
        }
        for (List<String> list : List.of(changedPaths, capabilityDependencies, provenance.contractRevisions(),
                provenance.handoffLineage(), provenance.claimSelectors())) {
            if (list.size() > MAX_LIST_ENTRIES) {
                throw new CoordinationArgumentException("list exceeds " + MAX_LIST_ENTRIES + " entries");
            }
            TEXT.requireWritable(list, "list entry");
        }
    }

    /**
     * Constructs a payload with default provenance for a minimal snapshot record.
     *
     * @param taskId                 task ID
     * @param snapshotId             snapshot ID
     * @param nodeId                 node ID
     * @param supervisorId           supervisor ID
     * @param workerId               worker ID
     * @param providerSessionId      session ID
     * @param baseCommit             base commit
     * @param commitSha              commit SHA
     * @param changedPaths           changed paths
     * @param capabilityDependencies dependencies
     * @param summary                summary
     */
    public TaskSnapshotPayload(UUID taskId,
            String snapshotId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            String baseCommit,
            String commitSha,
            List<String> changedPaths,
            List<String> capabilityDependencies,
            String summary) {
        this(taskId,
                snapshotId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                baseCommit,
                commitSha,
                changedPaths,
                capabilityDependencies,
                summary,
                new SnapshotProvenance(taskId,
                        taskId,
                        nodeId,
                        providerSessionId,
                        1,
                        capabilityDependencies,
                        List.of(),
                        List.of(),
                        commitSha,
                        commitSha),
                false);
    }

    /**
     * Decodes a {@link TaskSnapshotPayload} from binary format.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException if malformed or unsupported format
     */
    public static TaskSnapshotPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            readHeader(in);
            TaskSnapshotPayload payload = readPayload(in);
            if (in.available() != 0) {
                throw new CoordinationException("Trailing task snapshot payload bytes");
            }
            return payload;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("Malformed task snapshot payload", failure);
        }
    }

    /**
     * Reads and validates the binary payload header.
     *
     * @param in input positioned at the payload start
     * @throws IOException when the magic or version is unsupported
     */
    private static void readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new CoordinationException("Unsupported task snapshot payload format");
        }
        if (in.readInt() != VERSION) {
            throw new CoordinationException("Unsupported task snapshot payload version");
        }
    }

    /**
     * Reads the payload fields following a validated header.
     *
     * @param in input positioned after the header
     * @return decoded payload
     * @throws IOException when a bounded field is truncated or malformed
     */
    private static TaskSnapshotPayload readPayload(DataInputStream in) throws IOException {
        UUID taskId = readUuid(in);
        String snapshotId = TEXT.readText(in);
        String nodeId = TEXT.readText(in);
        String supervisorId = TEXT.readText(in);
        String workerId = TEXT.readText(in);
        String providerSessionId = TEXT.readText(in);
        String baseCommit = TEXT.readText(in);
        String commitSha = TEXT.readText(in);

        List<String> changedPaths = readList(in);
        List<String> dependencies = readList(in);

        String summary = TEXT.readText(in);
        SnapshotProvenance provenance = readProvenance(in);
        boolean reviewRequired = in.readBoolean();
        return new TaskSnapshotPayload(taskId,
                snapshotId,
                nodeId,
                supervisorId,
                workerId,
                providerSessionId,
                baseCommit,
                commitSha,
                changedPaths,
                dependencies,
                summary,
                provenance,
                reviewRequired);
    }

    /**
     * Decodes the lane provenance section.
     *
     * @param in input positioned at the provenance section
     * @return decoded provenance
     * @throws IOException when provenance fields are truncated or malformed
     */
    private static SnapshotProvenance readProvenance(DataInputStream in) throws IOException {
        UUID group = readUuid(in);
        UUID lane = readUuid(in);
        UUID authorityLineage = readUuid(in);
        String participant = TEXT.readText(in);
        String binding = TEXT.readText(in);
        long epoch = in.readLong();
        List<String> contracts = readList(in);
        List<String> lineage = readList(in);
        List<String> claims = readList(in);
        String snapshotRef = TEXT.readText(in);
        String integrity = TEXT.readText(in);
        String artifacts = TEXT.readText(in);
        return new SnapshotProvenance(group,
                lane,
                authorityLineage,
                participant,
                binding,
                epoch,
                contracts,
                lineage,
                claims,
                snapshotRef,
                integrity,
                artifacts);
    }

    /**
     * Writes a bounded list using the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            TEXT.writeText(out, value);
        }
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
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(TEXT.readText(in));
        }
        return values;
    }

    /**
     * Returns the typed task identity carried by this snapshot event.
     *
     * @return the typed task identity carried by this snapshot event.
     */
    public TaskId taskIdentity() {
        return new TaskId(taskId);
    }

    /**
     * Returns the typed immutable snapshot identity carried by this event.
     *
     * @return the typed immutable snapshot identity carried by this event.
     */
    public SnapshotId snapshotIdentity() {
        return new SnapshotId(snapshotId);
    }

    /**
     * Encodes this payload into binary format.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            out.writeInt(VERSION);
            writeUuid(out, taskId);
            TEXT.writeText(out, snapshotId);
            TEXT.writeText(out, nodeId);
            TEXT.writeText(out, supervisorId);
            TEXT.writeText(out, workerId);
            TEXT.writeText(out, providerSessionId);
            TEXT.writeText(out, baseCommit);
            TEXT.writeText(out, commitSha);

            out.writeInt(changedPaths.size());
            for (String p : changedPaths) {
                TEXT.writeText(out, p);
            }
            out.writeInt(capabilityDependencies.size());
            for (String dep : capabilityDependencies) {
                TEXT.writeText(out, dep);
            }
            TEXT.writeText(out, summary);
            writeUuid(out, provenance.workGroupId());
            writeUuid(out, provenance.laneId());
            writeUuid(out, provenance.authorityLineageId());
            TEXT.writeText(out, provenance.participant());
            TEXT.writeText(out, provenance.bindingIdentity());
            out.writeLong(provenance.claimEpoch());
            writeList(out, provenance.contractRevisions());
            writeList(out, provenance.handoffLineage());
            writeList(out, provenance.claimSelectors());
            TEXT.writeText(out, provenance.snapshotRef());
            TEXT.writeText(out, provenance.integrityEvidence());
            TEXT.writeText(out, provenance.artifactManifestDigest());
            out.writeBoolean(reviewRequired);
        });
    }
}
