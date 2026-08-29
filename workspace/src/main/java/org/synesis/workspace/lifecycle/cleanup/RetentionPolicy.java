package org.synesis.workspace.lifecycle.cleanup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Configurable retention policy thresholds and clock reference.
 *
 * @since 1.0
 */
@SuppressWarnings("ClassCanBeRecord")
public final class RetentionPolicy {

    private static final Duration DEFAULT_WORKER_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_VALIDATION_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_INTEGRATION_RETENTION = Duration.ofHours(24);
    private static final Duration DEFAULT_EVIDENCE_RETENTION = Duration.ofDays(7);
    private static final Duration DEFAULT_TEMP_FILE_RETENTION = Duration.ofHours(1);
    private static final int DEFAULT_MAX_DIAGNOSTIC_ATTEMPTS = 3;
    private static final long DEFAULT_STORAGE_WARNING_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB

    private final Clock clock;
    private final Duration workerWorktreeRetention;
    private final Duration validationWorktreeRetention;
    private final Duration integrationWorktreeRetention;
    private final Duration diagnosticEvidenceRetention;
    private final Duration temporaryFileRetention;
    private final int maxDiagnosticAttempts;
    private final long storageWarningThresholdBytes;

    /**
     * Creates a retention policy with default thresholds and system UTC clock.
     */
    public RetentionPolicy() {
        this(Clock.systemUTC(), DEFAULT_WORKER_RETENTION, DEFAULT_VALIDATION_RETENTION,
                DEFAULT_INTEGRATION_RETENTION, DEFAULT_EVIDENCE_RETENTION, DEFAULT_TEMP_FILE_RETENTION,
                DEFAULT_MAX_DIAGNOSTIC_ATTEMPTS, DEFAULT_STORAGE_WARNING_THRESHOLD_BYTES);
    }

    /**
     * Creates a custom retention policy with explicit clock and thresholds.
     *
     * @param clock                        clock source
     * @param workerWorktreeRetention      retention duration for finalized worker worktrees
     * @param validationWorktreeRetention  retention duration for completed validation worktrees
     * @param integrationWorktreeRetention retention duration for completed integration worktrees
     * @param diagnosticEvidenceRetention  retention duration for diagnostic evidence files
     * @param temporaryFileRetention       retention duration for temporary files
     * @param maxDiagnosticAttempts        maximum number of retained diagnostic integration worktrees
     * @param storageWarningThresholdBytes disk space warning threshold in bytes
     */
    public RetentionPolicy(
            Clock clock,
            Duration workerWorktreeRetention,
            Duration validationWorktreeRetention,
            Duration integrationWorktreeRetention,
            Duration diagnosticEvidenceRetention,
            Duration temporaryFileRetention,
            int maxDiagnosticAttempts,
            long storageWarningThresholdBytes
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workerWorktreeRetention = Objects.requireNonNull(workerWorktreeRetention, "workerWorktreeRetention");
        this.validationWorktreeRetention = Objects.requireNonNull(validationWorktreeRetention,
                "validationWorktreeRetention");
        this.integrationWorktreeRetention = Objects.requireNonNull(integrationWorktreeRetention,
                "integrationWorktreeRetention");
        this.diagnosticEvidenceRetention = Objects.requireNonNull(diagnosticEvidenceRetention,
                "diagnosticEvidenceRetention");
        this.temporaryFileRetention = Objects.requireNonNull(temporaryFileRetention, "temporaryFileRetention");
        this.maxDiagnosticAttempts = maxDiagnosticAttempts;
        this.storageWarningThresholdBytes = storageWarningThresholdBytes;
    }

    /**
     * Returns the clock source.
     *
     * @return clock
     */
    @SuppressWarnings("unused")
    public Clock clock() {
        return clock;
    }

    /**
     * Returns current instant from clock.
     *
     * @return instant
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * Returns worker worktree retention duration.
     *
     * @return duration
     */
    public Duration workerWorktreeRetention() {
        return workerWorktreeRetention;
    }

    /**
     * Returns validation worktree retention duration.
     *
     * @return duration
     */
    public Duration validationWorktreeRetention() {
        return validationWorktreeRetention;
    }

    /**
     * Returns integration worktree retention duration.
     *
     * @return duration
     */
    public Duration integrationWorktreeRetention() {
        return integrationWorktreeRetention;
    }

    /**
     * Returns diagnostic evidence retention duration.
     *
     * @return duration
     */
    @SuppressWarnings("unused")
    public Duration diagnosticEvidenceRetention() {
        return diagnosticEvidenceRetention;
    }

    /**
     * Returns temporary file retention duration.
     *
     * @return duration
     */
    public Duration temporaryFileRetention() {
        return temporaryFileRetention;
    }

    /**
     * Returns maximum retained diagnostic attempts count.
     *
     * @return count
     */
    @SuppressWarnings("unused")
    public int maxDiagnosticAttempts() {
        return maxDiagnosticAttempts;
    }

    /**
     * Returns storage warning threshold in bytes.
     *
     * @return threshold bytes
     */
    public long storageWarningThresholdBytes() {
        return storageWarningThresholdBytes;
    }
}
