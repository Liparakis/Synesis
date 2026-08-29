package org.synesis.workspace.application.integration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

/**
 * Performs deterministic, bounded pre-merge compatibility checks.
 */
public final class IntegrationCompatibilityService {

    /**
     * Creates a compatibility checker.
     */
    public IntegrationCompatibilityService() {
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IllegalArgumentException("invalid repository-relative path");
        }
        String value = path.replace('\\', '/');
        if (value.equals(".git") || value.startsWith(".git/") || value.contains("../") || value.equals("..")) {
            throw new IllegalArgumentException("invalid repository-relative path");
        }
        return value;
    }

    /**
     * Validates snapshots before an integration worktree is created.
     *
     * @param request compatibility facts
     * @return deterministic result
     */
    public CheckResult check(CheckRequest request) {
        Objects.requireNonNull(request, "request");
        Set<FailureCode> failures = new LinkedHashSet<>();
        List<String> actions = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        for (SnapshotInput snapshot : request.snapshots()) {
            if (!request.controlHead()
                    .equals(snapshot.baseCommit())) {
                failures.add(FailureCode.STALE_BASE);
                actions.add("Rebase snapshot " + snapshot.snapshotId() + " onto the current control head");
            }
            for (String path : snapshot.changedPaths()) {
                String normalized = normalize(path);
                if (!changed.add(normalized)) {
                    failures.add(FailureCode.OVERLAPPING_SNAPSHOT);
                    actions.add("Resolve overlapping snapshot path " + normalized);
                }
                boolean covered = snapshot.claims()
                        .stream()
                        .anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(normalized)));
                if (!covered) {
                    failures.add(FailureCode.UNCOVERED_PATH);
                    actions.add("Claim changed path before publishing " + normalized);
                }
            }
            if (!snapshot.outOfBandPaths()
                    .isEmpty()) {
                failures.add(FailureCode.OUT_OF_BAND_MUTATION);
                actions.add("Reconcile direct writes before integration: " + snapshot.outOfBandPaths());
            }
            for (ContractReference dependency : snapshot.contracts()) {
                boolean current = request.currentContracts()
                        .stream()
                        .anyMatch(contract ->
                                contract.contractId()
                                        .equals(dependency.contractId())
                                        && contract.revision() == dependency.revision() && contract.active());
                if (!current) {
                    failures.add(FailureCode.STALE_CONTRACT);
                    actions.add("Rebind snapshot " + snapshot.snapshotId() + " to the current contract revision");
                }
            }
        }
        if (!request.testsPassed()) {
            failures.add(FailureCode.TESTS_FAILED);
            actions.add("Run the configured project test command and resolve failures");
        }
        return new CheckResult(failures.isEmpty(), List.copyOf(failures), actions);
    }

    /**
     * Failure classifications returned by the check.
     */
    public enum FailureCode {
        /**
         * Snapshot base is not the expected control head.
         */
        STALE_BASE,
        /**
         * Two snapshots modify the same normalized path.
         */
        OVERLAPPING_SNAPSHOT,
        /**
         * A changed path is outside the snapshot's declared claims.
         */
        UNCOVERED_PATH,
        /**
         * A dependency references a non-current contract revision.
         */
        STALE_CONTRACT,
        /**
         * A direct filesystem mutation is outside declared ownership.
         */
        OUT_OF_BAND_MUTATION,
        /**
         * Configured project tests failed.
         */
        TESTS_FAILED
    }

    /**
     * Immutable contract reference used by a snapshot.
     *
     * @param contractId contract identifier
     * @param revision   exact revision
     */
    public record ContractReference(UUID contractId, long revision) {

        /**
         * Validates the reference.
         *
         * @param contractId contract identifier
         * @param revision   exact revision
         */
        public ContractReference {
            Objects.requireNonNull(contractId, "contractId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    /**
     * Snapshot facts required for compatibility validation.
     *
     * @param snapshotId     snapshot identifier
     * @param baseCommit     snapshot base commit
     * @param changedPaths   changed repository-relative paths
     * @param claims         selectors owned by the snapshot
     * @param contracts      exact contract dependencies
     * @param outOfBandPaths paths changed outside the declared claims
     */
    public record SnapshotInput(String snapshotId, String baseCommit, List<String> changedPaths,
                                List<ResourceSelector> claims, List<ContractReference> contracts,
                                List<String> outOfBandPaths) {

        /**
         * Validates and copies snapshot facts.
         *
         * @param snapshotId     snapshot identifier
         * @param baseCommit     snapshot base commit
         * @param changedPaths   changed repository-relative paths
         * @param claims         selectors owned by the snapshot
         * @param contracts      exact contract dependencies
         * @param outOfBandPaths paths changed outside the declared claims
         */
        public SnapshotInput {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(baseCommit, "baseCommit");
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            contracts = List.copyOf(Objects.requireNonNull(contracts, "contracts"));
            outOfBandPaths = List.copyOf(Objects.requireNonNull(outOfBandPaths, "outOfBandPaths"));
        }
    }

    /**
     * Current contract revision supplied by the replay projection.
     *
     * @param contractId contract identifier
     * @param revision   current revision
     * @param active     whether publication is active
     */
    public record CurrentContract(UUID contractId, long revision, boolean active) {

        /**
         * Validates the current contract.
         *
         * @param contractId contract identifier
         * @param revision   current revision
         * @param active     whether publication is active
         */
        public CurrentContract {
            Objects.requireNonNull(contractId, "contractId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }

    /**
     * Compatibility check input.
     *
     * @param controlHead      expected control branch head
     * @param snapshots        immutable snapshots
     * @param currentContracts current contract revisions
     * @param testsPassed      configured project test result
     */
    public record CheckRequest(String controlHead, List<SnapshotInput> snapshots,
                               List<CurrentContract> currentContracts, boolean testsPassed) {

        /**
         * Validates and copies check input.
         *
         * @param controlHead      expected control branch head
         * @param snapshots        immutable snapshots
         * @param currentContracts current contract revisions
         * @param testsPassed      configured project test result
         */
        public CheckRequest {
            Objects.requireNonNull(controlHead, "controlHead");
            snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
            currentContracts = List.copyOf(Objects.requireNonNull(currentContracts, "currentContracts"));
        }
    }

    /**
     * Check result with actionable failure codes.
     *
     * @param accepted whether integration may proceed
     * @param failures blocking failure codes
     * @param actions  actionable follow-up guidance
     */
    public record CheckResult(boolean accepted, List<FailureCode> failures, List<String> actions) {

        /**
         * Copies result collections.
         *
         * @param accepted whether integration may proceed
         * @param failures blocking failure codes
         * @param actions  actionable follow-up guidance
         */
        public CheckResult {
            failures = List.copyOf(failures);
            actions = List.copyOf(actions);
        }
    }
}
