package org.synesis.workspace.lifecycle.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;

/**
 * Immutable admission snapshot used to validate the lease release/reacquire gap.
 *
 * @param bindingDigest             exact binding authority digest
 * @param blockerSetDigest          blocker projection digest
 * @param leaseDigest               lease evidence digest
 * @param leaseHeartbeatEpochMillis captured lease heartbeat timestamp
 * @param worktreeLocator           verified physical-worktree locator
 * @param branch                    authoritative branch
 * @param baseCommit                authoritative base commit
 * @param authorityEpoch            binding/sequence authority epoch
 */
public record ProjectCommandAuthoritySnapshot(
        String bindingDigest,
        String blockerSetDigest,
        String leaseDigest,
        long leaseHeartbeatEpochMillis,
        String worktreeLocator,
        String branch,
        String baseCommit,
        long authorityEpoch
) {

    /**
     * Validates the bounded authority snapshot.
     */
    public ProjectCommandAuthoritySnapshot {
        Objects.requireNonNull(bindingDigest, "bindingDigest");
        Objects.requireNonNull(blockerSetDigest, "blockerSetDigest");
        Objects.requireNonNull(leaseDigest, "leaseDigest");
        Objects.requireNonNull(worktreeLocator, "worktreeLocator");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(baseCommit, "baseCommit");
    }

    /**
     * Captures binding, worktree, blocker, and lease evidence for admission.
     *
     * @param binding          exact provider binding
     * @param worktree         verified worktree identity
     * @param lease            current lease, or {@code null} before first renewal
     * @param blockerSetDigest blocker projection digest
     * @return immutable authority snapshot
     */
    public static ProjectCommandAuthoritySnapshot capture(
            ProviderSessionBindingService.Binding binding,
            PhysicalWorktreeIdentity worktree,
            SessionLeaseRecord lease,
            String blockerSetDigest) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(blockerSetDigest, "blockerSetDigest");
        String bindingDigest = digest(java.util.Arrays.asList(
                binding.schemaVersion(), binding.sessionId(), binding.projectId(), binding.nodeId(), binding.provider(),
                binding.providerInstanceFingerprint(), binding.supervisorId(), binding.workerId(), binding.worktreeId(),
                binding.worktreePath(), binding.controlCheckoutPath(), binding.branch(), binding.baseCommit(),
                binding.gitCommonDir(), binding.creationState(), binding.verificationState(), binding.lastSeenState(),
                binding.status(), binding.createdAtEpochMillis(), binding.lastSeenEpochMillis(),
                binding.lastVerifiedProjectSequence(), binding.providerTrustState(), binding.bindingVersion(),
                binding.completedAt()));
        String leaseDigest = lease == null ? "missing" : digest(java.util.Arrays.asList(
                lease.schemaVersion(),
                lease.projectId(),
                lease.provider(),
                lease.connectionInstanceId(),
                lease.workerNodeId(),
                lease.sessionId(),
                lease.processIdentity()
                .pid(),
                lease.processIdentity()
                .executableIdentity(),
                lease.processIdentity()
                .commandLine(),
                lease.processIdentity()
                .processStartTime(),
                lease.processIdentity()
                .connectionNonce(),
                lease.createdAtEpochMillis(),
                lease.lastHeartbeatEpochMillis(),
                lease.leaseState()
                .name()));
        long heartbeat = lease == null ? 0L : lease.lastHeartbeatEpochMillis();
        long epoch = Math.addExact(binding.bindingVersion(), binding.lastVerifiedProjectSequence());
        return new ProjectCommandAuthoritySnapshot(bindingDigest, blockerSetDigest, leaseDigest, heartbeat,
                worktree.locator(), binding.branch() == null ? "" : binding.branch(),
                binding.baseCommit() == null ? "" : binding.baseCommit(), epoch);
    }

    private static String digest(List<?> values) {
        try {
            String material = values.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining("\u001f"));
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("authority digest unavailable", failure);
        }
    }

    /**
     * Returns whether all non-lease authority remained identical.
     *
     * @param other post-renewal snapshot
     * @return true when only lease evidence may have changed
     */
    public boolean sameAuthorityExceptLease(ProjectCommandAuthoritySnapshot other) {
        Objects.requireNonNull(other, "other");
        return bindingDigest.equals(other.bindingDigest)
                && blockerSetDigest.equals(other.blockerSetDigest)
                && worktreeLocator.equals(other.worktreeLocator)
                && branch.equals(other.branch)
                && baseCommit.equals(other.baseCommit)
                && authorityEpoch == other.authorityEpoch;
    }
}
