package org.synesis.coordination.domain.task;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
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
    }

    /**
     * Constructs a payload with the pre-review-gate shape.
     *
     * <p>Historical and direct test callers default to an unreviewed snapshot;
     * reviewed publication uses the canonical constructor with the explicit review flag.</p>
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
     * @param provenance             immutable lane provenance
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
            String summary,
            SnapshotProvenance provenance) {
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
                provenance,
                false);
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
                        commitSha));
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
            int version = readVersion(in);
            return readPayload(in, version);
        } catch (RuntimeException failure) {
            throw new CoordinationException("Malformed task snapshot payload", failure);
        }
    }

    /**
     * Reads and validates the binary payload header.
     *
     * @param in input positioned at the payload start
     * @return supported payload version
     * @throws IOException when the magic or version is unsupported
     */
    private static int readVersion(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new CoordinationException("Unsupported task snapshot payload format");
        }
        int version = in.readInt();
        if (version < 1 || version > VERSION) {
            throw new CoordinationException("Unsupported task snapshot payload version");
        }
        return version;
    }

    /**
     * Reads the payload fields following a validated header.
     *
     * @param in      input positioned after the header
     * @param version supported payload version
     * @return decoded payload
     * @throws IOException when a bounded field is truncated or malformed
     */
    private static TaskSnapshotPayload readPayload(DataInputStream in, int version) throws IOException {
        UUID taskId = readUuid(in);
        String snapshotId = readText(in);
        String nodeId = readText(in);
        String supervisorId = readText(in);
        String workerId = readText(in);
        String providerSessionId = readText(in);
        String baseCommit = readText(in);
        String commitSha = readText(in);

        List<String> changedPaths = readTextList(in);
        List<String> dependencies = readTextList(in);

        String summary = readText(in);
        SnapshotProvenance provenance = readProvenance(in,
                version,
                taskId,
                nodeId,
                providerSessionId,
                dependencies,
                commitSha);
        boolean reviewRequired = readReviewRequirement(in, version);
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
     * Reads a bounded-count list of text values from the payload.
     *
     * @param in input positioned at the list count
     * @return values in their encoded order
     * @throws IOException when a value is truncated or malformed
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
     * Decodes the version-specific lane provenance section.
     *
     * @param in                input positioned at the provenance section
     * @param version           supported payload version
     * @param taskId            task identity from the payload header
     * @param nodeId            node identity from the payload header
     * @param providerSessionId provider session from the payload header
     * @param dependencies      capability dependencies from the payload body
     * @param commitSha         committed snapshot hash
     * @return decoded provenance
     * @throws IOException when provenance fields are truncated or malformed
     */
    private static SnapshotProvenance readProvenance(DataInputStream in,
            int version,
            UUID taskId,
            String nodeId,
            String providerSessionId,
            List<String> dependencies,
            String commitSha) throws IOException {
        if (version == 1) {
            return new SnapshotProvenance(taskId,
                    taskId,
                    nodeId,
                    providerSessionId,
                    1,
                    dependencies,
                    List.of(),
                    List.of(),
                    commitSha,
                    commitSha);
        }
        UUID group = readUuid(in);
        UUID lane = readUuid(in);
        UUID authorityLineage = version >= 4 ? readUuid(in) : WorkIntent.defaultAuthorityLineage(lane);
        String participant = readText(in);
        String binding = readText(in);
        long epoch = in.readLong();
        List<String> contracts = readList(in);
        List<String> lineage = readList(in);
        List<String> claims = readList(in);
        String snapshotRef = readText(in);
        String integrity = readText(in);
        String artifacts = version >= 3 ? readText(in) : "UNRECORDED";
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
     * Reads the review-gate bit introduced in payload version 5.
     *
     * @param in      input positioned at the optional review flag
     * @param version supported payload version
     * @return whether the snapshot requires review before integration
     * @throws IOException if the encoded flag is truncated
     */
    private static boolean readReviewRequirement(DataInputStream in, int version) throws IOException {
        return version == 5 && in.readBoolean();
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeUuid(DataOutputStream out, UUID val) throws IOException {
        out.writeLong(val.getMostSignificantBits());
        out.writeLong(val.getLeastSignificantBits());
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
     * Writes bounded UTF-8 text in the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeText(DataOutputStream out, String val) throws IOException {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) {
            throw new CoordinationException("text exceeds payload bound");
        }
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
        if (len < 0 || len > MAX_TEXT) {
            throw new CoordinationException("Invalid text length in payload");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new CoordinationException("Truncated payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /**
     * Writes a bounded list using the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeText(out, value);
        }
    }

    /**
     * Reads a bounded list from the canonical payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 128) {
            throw new CoordinationException("list bound");
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readText(in));
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeUuid(out, taskId);
            writeText(out, snapshotId);
            writeText(out, nodeId);
            writeText(out, supervisorId);
            writeText(out, workerId);
            writeText(out, providerSessionId);
            writeText(out, baseCommit);
            writeText(out, commitSha);

            out.writeInt(changedPaths.size());
            for (String p : changedPaths) {
                writeText(out, p);
            }
            out.writeInt(capabilityDependencies.size());
            for (String dep : capabilityDependencies) {
                writeText(out, dep);
            }
            writeText(out, summary);
            writeUuid(out, provenance.workGroupId());
            writeUuid(out, provenance.laneId());
            writeUuid(out, provenance.authorityLineageId());
            writeText(out, provenance.participant());
            writeText(out, provenance.bindingIdentity());
            out.writeLong(provenance.claimEpoch());
            writeList(out, provenance.contractRevisions());
            writeList(out, provenance.handoffLineage());
            writeList(out, provenance.claimSelectors());
            writeText(out, provenance.snapshotRef());
            writeText(out, provenance.integrityEvidence());
            writeText(out, provenance.artifactManifestDigest());
            out.writeBoolean(reviewRequired);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }
}
