package org.synesis.coordination.domain.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ImplementationRevision;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Immutable record representing a published implementation revision for a capability request.
 *
 * <p>Each revision is content-addressed by the owner Git commit SHA and is permanently
 * retained even after further revisions are published. Older revisions remain immutable and traceable.
 *
 * @param handle             request handle
 * @param authorityLineageId durable authority lineage of the publisher
 * @param revisionNumber     monotonically increasing revision counter (1-based)
 * @param baseCommit         Git base commit SHA in the owner worktree at time of publication
 * @param commitSha          Git commit SHA produced in the owner worktree (snapshot reference)
 * @param changedPaths       bounded list of changed paths relative to project root
 * @param summary            human-readable implementation summary (1-500 chars)
 * @param publishedAtMillis  publication timestamp
 * @since 1.0
 */
public record ImplementationRevisionRecord(CapabilityRequestHandle handle, UUID authorityLineageId, int revisionNumber,
                                           String baseCommit, String commitSha, List<String> changedPaths,
                                           String summary, long publishedAtMillis) {

    /**
     * Maximum number of changed paths per revision.
     */
    public static final int MAX_CHANGED_PATHS = 64;
    /**
     * Maximum length of the summary text in characters.
     */
    public static final int MAX_SUMMARY_LENGTH = 500;

    /**
     * Compact constructor enforcing invariants.
     *
     * @param handle            request handle
     * @param revisionNumber    monotonically increasing revision counter (1-based)
     * @param baseCommit        Git base commit SHA
     * @param commitSha         Git commit SHA for the implementation snapshot
     * @param changedPaths      bounded list of changed paths relative to project root
     * @param summary           human-readable implementation summary
     * @param publishedAtMillis publication timestamp
     */
    public ImplementationRevisionRecord {
        CoordinationNullException.requireNonNull(handle, "handle");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(commitSha, "commitSha");
        changedPaths = List.copyOf(CoordinationNullException.requireNonNull(changedPaths, "changedPaths"));
        CoordinationNullException.requireNonNull(summary, "summary");
        if (revisionNumber < 1) {
            throw new CoordinationArgumentException("revisionNumber must be >= 1");
        }
        if (changedPaths.size() > MAX_CHANGED_PATHS) {
            throw new CoordinationArgumentException("too many changed paths (max " + MAX_CHANGED_PATHS + ")");
        }
        if (summary.isBlank() || summary.length() > MAX_SUMMARY_LENGTH) {
            throw new CoordinationArgumentException("summary must be 1-" + MAX_SUMMARY_LENGTH + " characters");
        }
    }

    /**
     * Constructs a historical implementation record without lineage metadata.
     *
     * @param handle            request handle
     * @param revisionNumber    revision number
     * @param baseCommit        base commit
     * @param commitSha         implementation commit
     * @param changedPaths      changed paths
     * @param summary           summary
     * @param publishedAtMillis publication time
     */
    public ImplementationRevisionRecord(CapabilityRequestHandle handle,
            int revisionNumber,
            String baseCommit,
            String commitSha,
            List<String> changedPaths,
            String summary,
            long publishedAtMillis) {
        this(handle,
                unresolvedLineage(handle),
                revisionNumber,
                baseCommit,
                commitSha,
                changedPaths,
                summary,
                publishedAtMillis);
    }

    /**
     * Derives a stable lineage when an older payload omitted it.
     */
    private static UUID unresolvedLineage(CapabilityRequestHandle handle) {
        return UUID.nameUUIDFromBytes(("synesis-unresolved-capability-lineage:" + handle.value()).getBytes(
                StandardCharsets.UTF_8));
    }

    /**
     * Returns the authority lineage that published this revision.
     *
     * @return the authority lineage that published this revision.
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

}
