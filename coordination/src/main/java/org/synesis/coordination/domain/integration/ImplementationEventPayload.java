package org.synesis.coordination.domain.integration;

import org.synesis.coordination.domain.capability.CapabilityRequestHandle;


import org.synesis.coordination.domain.capability.CapabilityRequestHandle;



import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Binary codec for Stage 2B Slice 2 capability implementation events.
 *
 * <p>Covers {@code CAPABILITY_IMPLEMENTATION_PUBLISHED}, {@code CAPABILITY_VALIDATION_STARTED},
 * {@code CAPABILITY_IMPLEMENTATION_VALIDATED}, and {@code CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED}.
 *
 * @param handle               request handle
 * @param revisionNumber       implementation revision number (1-based)
 * @param baseCommit           Git base commit SHA (empty string when not applicable)
 * @param commitSha            Git commit SHA of the implementation snapshot
 * @param changedPaths         list of changed paths relative to project root
 * @param summary              human-readable implementation summary
 * @param validationResult     validation outcome: {@code ""}, {@code "accepted"}, or {@code "revision_required"}
 * @param validationReason     free-text validation failure reason (may be empty)
 * @param failedAcceptanceTests list of failed acceptance test names
 * @param worktreePath         absolute path to validation worktree (empty when not applicable)
 * @since 1.0
 */
public record ImplementationEventPayload(
        CapabilityRequestHandle handle,
        int revisionNumber,
        String baseCommit,
        String commitSha,
        List<String> changedPaths,
        String summary,
        String validationResult,
        String validationReason,
        List<String> failedAcceptanceTests,
        String worktreePath
) {

    private static final int MAGIC = 0x494D504C; // "IMPL"
    private static final int VERSION = 1;
    private static final int MAX_TEXT = 64 * 1024;

    /**
     * Compact constructor enforcing non-null invariants.
     *
     * @param handle                request handle
     * @param revisionNumber        implementation revision number (1-based)
     * @param baseCommit            Git base commit SHA
     * @param commitSha             Git commit SHA of the implementation snapshot
     * @param changedPaths          list of changed paths relative to project root
     * @param summary               human-readable implementation summary
     * @param validationResult      validation outcome string
     * @param validationReason      validation failure reason
     * @param failedAcceptanceTests list of failed acceptance test names
     * @param worktreePath          absolute path to validation worktree
     */
    public ImplementationEventPayload {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(validationResult, "validationResult");
        Objects.requireNonNull(validationReason, "validationReason");
        failedAcceptanceTests = List.copyOf(Objects.requireNonNull(failedAcceptanceTests, "failedAcceptanceTests"));
        Objects.requireNonNull(worktreePath, "worktreePath");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be >= 1");
        }
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
            writeText(out, handle.value());
            out.writeInt(revisionNumber);
            writeText(out, baseCommit);
            writeText(out, commitSha);
            out.writeInt(changedPaths.size());
            for (String path : changedPaths) {
                writeText(out, path);
            }
            writeText(out, summary);
            writeText(out, validationResult);
            writeText(out, validationReason);
            out.writeInt(failedAcceptanceTests.size());
            for (String test : failedAcceptanceTests) {
                writeText(out, test);
            }
            writeText(out, worktreePath);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes an {@link ImplementationEventPayload} from binary format.
     *
     * @param encoded encoded payload bytes
     * @return decoded payload
     * @throws IOException if the payload is malformed or uses an unsupported version
     */
    public static ImplementationEventPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported implementation event payload format");
            }
            CapabilityRequestHandle handle = CapabilityRequestHandle.parse(readText(in));
            int revisionNumber = in.readInt();
            String baseCommit = readText(in);
            String commitSha = readText(in);

            int pathCount = in.readInt();
            if (pathCount < 0 || pathCount > ImplementationRevisionRecord.MAX_CHANGED_PATHS) {
                throw new IOException("Invalid changed path count: " + pathCount);
            }
            List<String> changedPaths = new ArrayList<>(pathCount);
            for (int i = 0; i < pathCount; i++) {
                changedPaths.add(readText(in));
            }
            String summary = readText(in);
            String validationResult = readText(in);
            String validationReason = readText(in);

            int testCount = in.readInt();
            if (testCount < 0 || testCount > 32) {
                throw new IOException("Invalid failed acceptance test count");
            }
            List<String> failedTests = new ArrayList<>(testCount);
            for (int i = 0; i < testCount; i++) {
                failedTests.add(readText(in));
            }
            String worktreePath = readText(in);
            return new ImplementationEventPayload(
                    handle, revisionNumber, baseCommit, commitSha, changedPaths,
                    summary, validationResult, validationReason, failedTests, worktreePath);
        } catch (RuntimeException failure) {
            throw new IOException("Malformed implementation event payload", failure);
        }
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
            throw new IOException("Invalid text length in implementation payload");
        }
        byte[] b = in.readNBytes(len);
        if (b.length != len) {
            throw new IOException("Truncated implementation payload");
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
