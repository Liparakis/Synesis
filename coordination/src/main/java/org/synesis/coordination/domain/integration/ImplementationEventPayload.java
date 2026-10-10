package org.synesis.coordination.domain.integration;

import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ImplementationRevision;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Binary codec for Stage 2B Slice 2 capability implementation events.
 *
 * <p>Covers {@code CAPABILITY_IMPLEMENTATION_PUBLISHED}, {@code CAPABILITY_VALIDATION_STARTED},
 * {@code CAPABILITY_IMPLEMENTATION_VALIDATED}, and {@code CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED}.
 *
 * @param handle                request handle
 * @param authorityLineageId    durable authority lineage of the publisher
 * @param revisionNumber        implementation revision number (1-based)
 * @param baseCommit            Git base commit SHA (empty string when not applicable)
 * @param commitSha             Git commit SHA of the implementation snapshot
 * @param changedPaths          list of changed paths relative to project root
 * @param summary               human-readable implementation summary
 * @param validationResult      validation outcome: {@code ""}, {@code "accepted"}, or {@code "revision_required"}
 * @param validationReason      free-text validation failure reason (may be empty)
 * @param failedAcceptanceTests list of failed acceptance test names
 * @param worktreePath          absolute path to validation worktree (empty when not applicable)
 * @param requesterSessionId    exact requester session that owns the validation worktree
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public record ImplementationEventPayload(CapabilityRequestHandle handle, UUID authorityLineageId, int revisionNumber,
                                         String baseCommit, String commitSha, List<String> changedPaths, String summary,
                                         String validationResult, String validationReason,
                                         List<String> failedAcceptanceTests, String worktreePath, String requesterSessionId) {

    private static final int MAGIC = 0x494D504C; // "IMPL"
    private static final int VERSION = 3;
    private static final int MAX_TEXT = 64 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT,
            true,
            "Invalid text length in implementation payload",
            "Truncated implementation payload").checkingWrites(MAX_TEXT, "text exceeds payload bound");

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
        CoordinationNullException.requireNonNull(handle, "handle");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(CoordinationNullException.requireNonNull(changedPaths, "changedPaths"));
        CoordinationNullException.requireNonNull(summary, "summary");
        CoordinationNullException.requireNonNull(validationResult, "validationResult");
        CoordinationNullException.requireNonNull(validationReason, "validationReason");
        failedAcceptanceTests = List.copyOf(CoordinationNullException.requireNonNull(failedAcceptanceTests,
                "failedAcceptanceTests"));
        CoordinationNullException.requireNonNull(worktreePath, "worktreePath");
        CoordinationNullException.requireNonNull(requesterSessionId, "requesterSessionId");
        if (revisionNumber < 1) {
            throw new CoordinationArgumentException("revisionNumber must be >= 1");
        }
    }

    /**
     * Creates an event for non-validation callers. The requester
     * session is empty, so it cannot establish authority for a new validation workspace.
     *
     * @param handle capability request handle
     * @param authorityLineageId publisher's durable authority lineage
     * @param revisionNumber implementation revision number
     * @param baseCommit source base commit
     * @param commitSha immutable implementation snapshot commit
     * @param changedPaths source paths in the snapshot
     * @param summary implementation summary
     * @param validationResult validation result, or empty before validation
     * @param validationReason reason for a revision request, or empty
     * @param failedAcceptanceTests failed acceptance tests
     * @param worktreePath validation workspace path, or empty before validation
     */
    public ImplementationEventPayload(CapabilityRequestHandle handle,
            UUID authorityLineageId,
            int revisionNumber,
            String baseCommit,
            String commitSha,
            List<String> changedPaths,
            String summary,
            String validationResult,
            String validationReason,
            List<String> failedAcceptanceTests,
            String worktreePath) {
        this(handle, authorityLineageId, revisionNumber, baseCommit, commitSha, changedPaths, summary, validationResult, validationReason,
                failedAcceptanceTests, worktreePath, "");
    }

    /**
     * Decodes an {@link ImplementationEventPayload} from binary format.
     *
     * @param encoded encoded payload bytes
     * @return decoded payload
     * @throws IOException if the payload is malformed or uses an unsupported version
     */
    public static ImplementationEventPayload decode(byte[] encoded) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new CoordinationException("Unsupported implementation event payload format");
            }
            CapabilityRequestHandle handle = CapabilityRequestHandle.parse(TEXT.readText(in));
            UUID authorityLineageId = readUuid(in);
            int revisionNumber = in.readInt();
            String baseCommit = TEXT.readText(in);
            String commitSha = TEXT.readText(in);

            int pathCount = in.readInt();
            if (pathCount < 0 || pathCount > ImplementationRevisionRecord.MAX_CHANGED_PATHS) {
                throw new CoordinationException("Invalid changed path count: " + pathCount);
            }
            List<String> changedPaths = new ArrayList<>(pathCount);
            for (int i = 0; i < pathCount; i++) {
                changedPaths.add(TEXT.readText(in));
            }
            String summary = TEXT.readText(in);
            String validationResult = TEXT.readText(in);
            String validationReason = TEXT.readText(in);

            int testCount = in.readInt();
            if (testCount < 0 || testCount > 32) {
                throw new CoordinationException("Invalid failed acceptance test count");
            }
            List<String> failedTests = new ArrayList<>(testCount);
            for (int i = 0; i < testCount; i++) {
                failedTests.add(TEXT.readText(in));
            }
            String worktreePath = TEXT.readText(in);
            String requesterSessionId = TEXT.readText(in);
            return new ImplementationEventPayload(handle,
                    authorityLineageId,
                    revisionNumber,
                    baseCommit,
                    commitSha,
                    changedPaths,
                    summary,
                    validationResult,
                    validationReason,
                    failedTests,
                    worktreePath,
                    requesterSessionId);
        } catch (RuntimeException failure) {
            throw new CoordinationException("Malformed implementation event payload", failure);
        }
    }

    /**
     * Returns the authority lineage that published this implementation.
     *
     * @return the authority lineage that published this implementation.
     */
    public AuthorityLineageId authorityLineageIdentity() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Returns this capability implementation's one-based revision identity.
     *
     * @return this capability implementation's one-based revision identity.
     */
    public ImplementationRevision revisionIdentity() {
        return new ImplementationRevision(revisionNumber);
    }

    /**
     * Encodes this payload into binary format.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            out.writeInt(VERSION);
            TEXT.writeText(out, handle.value());
            writeUuid(out, authorityLineageId);
            out.writeInt(revisionNumber);
            TEXT.writeText(out, baseCommit);
            TEXT.writeText(out, commitSha);
            out.writeInt(changedPaths.size());
            for (String path : changedPaths) {
                TEXT.writeText(out, path);
            }
            TEXT.writeText(out, summary);
            TEXT.writeText(out, validationResult);
            TEXT.writeText(out, validationReason);
            out.writeInt(failedAcceptanceTests.size());
            for (String test : failedAcceptanceTests) {
                TEXT.writeText(out, test);
            }
            TEXT.writeText(out, worktreePath);
            TEXT.writeText(out, requesterSessionId);
        });
    }
}
