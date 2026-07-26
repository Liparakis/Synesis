package org.synesis.workspace.lifecycle.cleanup;

import org.synesis.workspace.infrastructure.process.ProcessEvidenceState;
import org.synesis.workspace.infrastructure.process.ProcessInspector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates raw discovered lifecycle resources against path verifiers, process inspectors,
 * retention policies, and durable event state to produce evaluated {@link CleanupPlanEntry} items.
 *
 * <p>This service operates strictly read-only and never mutates durable state, Git repositories,
 * or filesystem paths.
 *
 * @since 1.0
 */
public final class CleanupEligibilityService {

    private final LifecyclePathVerifier pathVerifier;
    private final RetentionPolicy retentionPolicy;
    private final ProcessInspector processInspector;

    /**
     * Creates an eligibility service with default verifier, retention policy, and system process inspector.
     */
    public CleanupEligibilityService() {
        this(new LifecyclePathVerifier(), new RetentionPolicy(), ProcessInspector.system());
    }

    /**
     * Creates an eligibility service with explicit verifier, retention policy, and process inspector.
     *
     * @param pathVerifier     lifecycle path safety verifier
     * @param retentionPolicy  configurable retention policy
     * @param processInspector conservative process liveness inspector
     */
    public CleanupEligibilityService(
            LifecyclePathVerifier pathVerifier,
            RetentionPolicy retentionPolicy,
            ProcessInspector processInspector
    ) {
        this.pathVerifier = Objects.requireNonNull(pathVerifier, "pathVerifier");
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        this.processInspector = Objects.requireNonNull(processInspector, "processInspector");
    }

    /**
     * Evaluates a discovered candidate resource and creates a cleanup plan entry.
     *
     * @param controlRoot control project root directory
     * @param resource    discovered resource candidate
     * @return evaluated cleanup plan entry
     */
    public CleanupPlanEntry evaluateResource(Path controlRoot, LifecycleInventoryService.DiscoveredResource resource) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(resource, "resource");

        Path path = resource.path();
        List<String> reasons = new ArrayList<>();
        boolean isDirty = false;
        String gitRegState = "NOT_APPLICABLE";
        String gitHead = null;
        String gitCommonDir = null;
        String statusDigest = "";

        // 1. Path Safety Verification (if filesystem path present)
        LifecyclePathVerifier.PathVerificationResult pathResult;
        if (path != null) {
            pathResult = pathVerifier.verifyPath(controlRoot, path);
            if (!pathResult.safe()) {
                reasons.add(pathResult.reasonCode());
            }
        } else {
            pathResult = new LifecyclePathVerifier.PathVerificationResult(true, "virtual_resource", Path.of("virtual"), null);
        }

        // 2. Read-only Git Worktree Inspection (if filesystem directory exists)
        if (path != null && Files.isDirectory(path) && Files.exists(path.resolve(".git"))) {
            gitRegState = "REGISTERED";
            try {
                gitHead = runGit(path, "rev-parse", "HEAD");
                String rawCommon = runGit(path, "rev-parse", "--git-common-dir");
                gitCommonDir = path.resolve(rawCommon).toAbsolutePath().normalize().toString();
                String porcelain = runGit(path, "status", "--porcelain");
                if (!porcelain.isBlank()) {
                    isDirty = true;
                    statusDigest = "dirty_changes_present";
                    reasons.add(CleanupReason.DIRTY_WORKTREE.code());
                } else {
                    statusDigest = "clean";
                }
            } catch (Exception ex) {
                gitRegState = "UNVERIFIED";
            }
        }

        // 3. Process Evidence Inspection (if PID available)
        ProcessEvidenceState processState = processInspector.evaluateEvidence(
                resource.pid(), "java", "SynesisMcpServer"
        );
        if (processState == ProcessEvidenceState.LIVE_VERIFIED || processState == ProcessEvidenceState.LIVE_UNVERIFIED) {
            reasons.add(CleanupReason.ACTIVE_SESSION.code());
        } else if (processState == ProcessEvidenceState.NOT_OBSERVED && resource.type() == LifecycleResourceType.PROVIDER_SESSION) {
            reasons.add(CleanupReason.SUSPECTED_STALE_PROCESS.code());
        }

        // 4. Calculate Age and Retention Expiry
        Instant now = retentionPolicy.now();
        Instant lastMod = Instant.ofEpochMilli(resource.lastModifiedTime() > 0 ? resource.lastModifiedTime() : now.toEpochMilli());
        Duration age = Duration.between(lastMod, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }

        // 5. Classification Logic according to Strict Rules
        CleanupClassification classification;
        boolean eligible = false;
        String retentionDesc = "Age: " + age.toHours() + "h";
        String proposedAction = "NONE";

        if (resource.type() == LifecycleResourceType.IMPLEMENTATION_SNAPSHOT || resource.type() == LifecycleResourceType.TASK_SNAPSHOT) {
            classification = CleanupClassification.PROTECTED;
            if (!pathResult.safe()) {
                reasons.add(pathResult.reasonCode());
            }
            reasons.add(CleanupReason.SNAPSHOT_STILL_REFERENCED.code());
            reasons.add(CleanupReason.SNAPSHOT_CLEANUP_NOT_SUPPORTED.code());
            proposedAction = "PRESERVE_IMMUTABLE_SNAPSHOT";
        } else if (!pathResult.safe()) {
            classification = CleanupClassification.PROTECTED;
            reasons.add(pathResult.reasonCode());
            proposedAction = "RETAIN_PROTECTED_PATH";
        } else if (resource.type() == LifecycleResourceType.WORKER_WORKTREE) {
            if (isDirty) {
                classification = CleanupClassification.RECOVERABLE;
                reasons.add(CleanupReason.RECOVERABLE_CHANGES.code());
                proposedAction = "RETAIN_UNCOMMITTED_CHANGES";
            } else if (age.compareTo(retentionPolicy.workerWorktreeRetention()) < 0) {
                classification = CleanupClassification.DIAGNOSTIC_RETAINED;
                reasons.add(CleanupReason.RETENTION_WINDOW_ACTIVE.code());
                proposedAction = "RETAIN_WITHIN_WINDOW";
            } else {
                classification = CleanupClassification.CLEANUP_ELIGIBLE;
                eligible = true;
                reasons.add(CleanupReason.FINALIZED_AND_CLEAN.code());
                proposedAction = "DELETE_WORKTREE_DIRECTORY";
            }
        } else if (resource.type() == LifecycleResourceType.VALIDATION_WORKTREE) {
            if (age.compareTo(retentionPolicy.validationWorktreeRetention()) < 0) {
                classification = CleanupClassification.DIAGNOSTIC_RETAINED;
                reasons.add(CleanupReason.DIAGNOSTIC_RETENTION.code());
                proposedAction = "RETAIN_VALIDATION_DIAGNOSTICS";
            } else {
                classification = CleanupClassification.CLEANUP_ELIGIBLE;
                eligible = true;
                reasons.add(CleanupReason.FINALIZED_AND_CLEAN.code());
                proposedAction = "DELETE_VALIDATION_WORKTREE";
            }
        } else if (resource.type() == LifecycleResourceType.INTEGRATION_WORKTREE) {
            if (age.compareTo(retentionPolicy.integrationWorktreeRetention()) < 0) {
                classification = CleanupClassification.DIAGNOSTIC_RETAINED;
                reasons.add(CleanupReason.DIAGNOSTIC_RETENTION.code());
                proposedAction = "RETAIN_INTEGRATION_DIAGNOSTICS";
            } else {
                classification = CleanupClassification.CLEANUP_ELIGIBLE;
                eligible = true;
                reasons.add(CleanupReason.INTEGRATED_AND_CLEAN.code());
                proposedAction = "DELETE_INTEGRATION_WORKTREE";
            }
        } else if (resource.type() == LifecycleResourceType.TEMPORARY_FILE) {
            if (age.compareTo(retentionPolicy.temporaryFileRetention()) >= 0) {
                classification = CleanupClassification.CLEANUP_ELIGIBLE;
                eligible = true;
                reasons.add(CleanupReason.TEMPORARY_FILE_EXPIRED.code());
                proposedAction = "DELETE_TEMPORARY_FILE";
            } else {
                classification = CleanupClassification.DIAGNOSTIC_RETAINED;
                reasons.add(CleanupReason.RETENTION_WINDOW_ACTIVE.code());
                proposedAction = "RETAIN_TEMPORARY_FILE";
            }
        } else if (resource.type() == LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE || resource.type() == LifecycleResourceType.DANGLING_GIT_WORKTREE) {
            classification = CleanupClassification.ORPHANED;
            reasons.add(CleanupReason.DURABLE_RECORD_MISSING.code());
            proposedAction = "REQUIRES_DOCTOR_RECONCILIATION";
        } else if (resource.type() == LifecycleResourceType.IMPLEMENTATION_SNAPSHOT || resource.type() == LifecycleResourceType.TASK_SNAPSHOT) {
            classification = CleanupClassification.PROTECTED;
            reasons.add(CleanupReason.SNAPSHOT_STILL_REFERENCED.code());
            reasons.add(CleanupReason.SNAPSHOT_CLEANUP_NOT_SUPPORTED.code());
            proposedAction = "PRESERVE_IMMUTABLE_SNAPSHOT";
        } else {
            classification = CleanupClassification.PROTECTED;
            reasons.add(CleanupReason.CONTROL_CHECKOUT_PROTECTED.code());
            proposedAction = "PRESERVE_PROTECTED_RESOURCE";
        }

        LifecycleResourceFingerprint fingerprint = new LifecycleResourceFingerprint(
                resource.id(),
                resource.lastModifiedTime(),
                gitHead != null ? gitHead : "NONE",
                gitCommonDir != null ? gitCommonDir : "NONE",
                statusDigest,
                String.valueOf(resource.estimatedBytes())
        );

        return new CleanupPlanEntry(
                resource.type(),
                resource.id(),
                path,
                classification,
                eligible,
                Collections.unmodifiableList(reasons),
                resource.estimatedBytes(),
                retentionDesc,
                resource.durableReferences(),
                gitRegState,
                isDirty,
                pathResult.reasonCode(),
                processState,
                fingerprint,
                proposedAction
        );
    }

    private static String runGit(Path workdir, String... args) throws IOException {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = workdir.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git interrupted", e);
        }
        return output;
    }
}
