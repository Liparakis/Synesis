package org.synesis.coordination.domain.task;




import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * @since 1.0
 */
public record TaskSnapshotPayload(
        UUID taskId,
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
        SnapshotProvenance provenance
) {

    private static final int MAGIC = 0x534E4150; // "SNAP"
    private static final int VERSION = 2;
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
     */
    public TaskSnapshotPayload {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        capabilityDependencies = List.copyOf(Objects.requireNonNull(capabilityDependencies, "capabilityDependencies"));
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** Constructs a payload with default provenance for a minimal snapshot record.
     * @param taskId task ID
     * @param snapshotId snapshot ID
     * @param nodeId node ID
     * @param supervisorId supervisor ID
     * @param workerId worker ID
     * @param providerSessionId session ID
     * @param baseCommit base commit
     * @param commitSha commit SHA
     * @param changedPaths changed paths
     * @param capabilityDependencies dependencies
     * @param summary summary
     */
    public TaskSnapshotPayload(UUID taskId, String snapshotId, String nodeId, String supervisorId,
            String workerId, String providerSessionId, String baseCommit, String commitSha,
            List<String> changedPaths, List<String> capabilityDependencies, String summary) {
        this(taskId, snapshotId, nodeId, supervisorId, workerId, providerSessionId, baseCommit,
                commitSha, changedPaths, capabilityDependencies, summary,
                new SnapshotProvenance(taskId, taskId, nodeId, providerSessionId, 1,
                        capabilityDependencies, List.of(), List.of(), commitSha, commitSha));
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
            writeText(out, provenance.participant());
            writeText(out, provenance.bindingIdentity());
            out.writeLong(provenance.claimEpoch());
            writeList(out, provenance.contractRevisions());
            writeList(out, provenance.handoffLineage());
            writeList(out, provenance.claimSelectors());
            writeText(out, provenance.snapshotRef());
            writeText(out, provenance.integrityEvidence());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes a {@link TaskSnapshotPayload} from binary format.
     *
     * @param encoded encoded bytes
     * @return decoded payload
     * @throws IOException if malformed or unsupported format
     */
    public static TaskSnapshotPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new IOException("Unsupported task snapshot payload format");
            }
            int version = in.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported task snapshot payload version");
            UUID taskId = readUuid(in);
            String snapshotId = readText(in);
            String nodeId = readText(in);
            String supervisorId = readText(in);
            String workerId = readText(in);
            String providerSessionId = readText(in);
            String baseCommit = readText(in);
            String commitSha = readText(in);

            int pathCount = in.readInt();
            List<String> changedPaths = new ArrayList<>(pathCount);
            for (int i = 0; i < pathCount; i++) {
                changedPaths.add(readText(in));
            }

            int depCount = in.readInt();
            List<String> deps = new ArrayList<>(depCount);
            for (int i = 0; i < depCount; i++) {
                deps.add(readText(in));
            }

            String summary = readText(in);
            SnapshotProvenance provenance;
            if (version == 1) {
                provenance = new SnapshotProvenance(taskId, taskId, nodeId, providerSessionId, 1,
                        deps, List.of(), List.of(), commitSha, commitSha);
            } else {
                UUID group = readUuid(in), lane = readUuid(in);
                String participant = readText(in), binding = readText(in);
                long epoch = in.readLong();
                List<String> contracts = readList(in), lineage = readList(in), claims = readList(in);
                provenance = new SnapshotProvenance(group, lane, participant, binding, epoch,
                        contracts, lineage, claims, readText(in), readText(in));
            }
            return new TaskSnapshotPayload(taskId, snapshotId, nodeId, supervisorId, workerId,
                    providerSessionId, baseCommit, commitSha, changedPaths, deps, summary, provenance);
        } catch (RuntimeException failure) {
            throw new IOException("Malformed task snapshot payload", failure);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID val) throws IOException {
        out.writeLong(val.getMostSignificantBits());
        out.writeLong(val.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeText(DataOutputStream out, String val) throws IOException {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) {
            throw new IOException("text exceeds payload bound");
        }
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readText(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_TEXT) {
            throw new IOException("Invalid text length in payload");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new IOException("Truncated payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) writeText(out, value);
    }

    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 128) throw new IOException("list bound");
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readText(in));
        return values;
    }
}
