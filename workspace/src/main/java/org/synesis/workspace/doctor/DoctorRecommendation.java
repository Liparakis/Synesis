package org.synesis.workspace.doctor;

/**
 * Actionable recommendations produced by DoctorService.
 *
 * @since 1.0
 */
public enum DoctorRecommendation {
    /**
     * Run synesis cleanup --dry-run to inspect eligible resources.
     */
    RUN_CLEANUP_DRY_RUN("run_cleanup_dry_run"),

    /**
     * Prepare a cleanup plan with synesis cleanup --prepare.
     */
    PREPARE_CLEANUP_PLAN("prepare_cleanup_plan"),

    /**
     * Prepare a reconciliation plan with synesis reconcile --prepare.
     */
    PREPARE_RECONCILIATION_PLAN("prepare_reconciliation_plan"),

    /**
     * Review local provider configuration files manually.
     */
    REVIEW_PROVIDER_CONFIGURATION("review_provider_configuration"),

    /**
     * Reinstall Synesis CLI / platform distribution.
     */
    REINSTALL_SYNESIS("reinstall_synesis"),

    /**
     * Review working directory status in control Git checkout.
     */
    REVIEW_CONTROL_CHECKOUT("review_control_checkout"),

    /**
     * Restore missing or corrupted snapshot from backup repository.
     */
    RESTORE_MISSING_SNAPSHOT_FROM_BACKUP("restore_missing_snapshot_from_backup"),

    /**
     * Prepare a safe repair plan with synesis repair --prepare.
     */
    PREPARE_REPAIR_PLAN("prepare_repair_plan"),

    /**
     * Manual human review required for ambiguous or critical safety state.
     */
    HUMAN_REVIEW_REQUIRED("human_review_required"),

    /**
     * No action required.
     */
    NO_ACTION("no_action");

    private final String value;

    DoctorRecommendation(String value) {
        this.value = value;
    }

    /**
     * Returns stable machine-readable string value.
     *
     * @return lowercase string representation
     */
    public String value() {
        return value;
    }
}
