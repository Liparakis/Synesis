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
    RUN_CLEANUP_DRY_RUN("run_cleanup_dry_run", "run 'synesis cleanup --dry-run' to see what can be cleaned up"),

    /**
     * Prepare a cleanup plan with synesis cleanup --prepare.
     */
    PREPARE_CLEANUP_PLAN("prepare_cleanup_plan", "run 'synesis cleanup --prepare' and read the plan it makes"),

    /**
     * Prepare a reconciliation plan with synesis reconcile --prepare.
     */
    PREPARE_RECONCILIATION_PLAN("prepare_reconciliation_plan", "run 'synesis reconcile --prepare' and read the plan it makes"),

    /**
     * Review local provider configuration files manually.
     */
    REVIEW_PROVIDER_CONFIGURATION("review_provider_configuration", "open the provider's configuration file named by 'synesis provider status <provider>' and fix it by hand"),

    /**
     * Prepare a provider configuration migration plan.
     */
    PREPARE_PROVIDER_MIGRATION("synesis_provider_migrate_prepare", "run 'synesis provider migrate --prepare'"),

    /**
     * Prepare a project schema migration plan.
     */
    PREPARE_PROJECT_MIGRATION("synesis_migrate_prepare", "run 'synesis migrate --prepare'"),

    /**
     * Review and resume the prepared update transaction.
     */
    REVIEW_UPDATE_TRANSACTION("review_update_transaction", "run 'synesis migrate --dry-run' and read what the unfinished update left"),

    /**
     * Reinstall Synesis CLI / platform distribution.
     */
    REINSTALL_SYNESIS("reinstall_synesis", "run the Synesis installer again"),

    /**
     * Review working directory status in control Git checkout.
     */
    REVIEW_CONTROL_CHECKOUT("review_control_checkout", "run 'git status' in the project and commit or discard what is not Synesis's"),

    /**
     * Restore missing or corrupted snapshot from backup repository.
     */
    RESTORE_MISSING_SNAPSHOT_FROM_BACKUP("restore_missing_snapshot_from_backup", "restore the snapshot from the backup repository"),

    /**
     * Prepare a safe repair plan with synesis repair --prepare.
     */
    PREPARE_REPAIR_PLAN("prepare_repair_plan", "run 'synesis doctor --fix', or 'synesis repair --prepare' to read the plan first"),

    /**
     * Manual human review required for ambiguous or critical safety state.
     */
    HUMAN_REVIEW_REQUIRED("human_review_required", "a person must look at it; run 'synesis doctor --verbose' for the evidence"),

    /**
     * No action required.
     */
    NO_ACTION("no_action", "nothing to do");

    private final String value;
    private final String fix;

    DoctorRecommendation(String value, String fix) {
        this.value = value;
        this.fix = fix;
    }

    /**
     * Returns stable machine-readable string value.
     *
     * @return lowercase string representation
     */
    public String value() {
        return value;
    }

    /**
     * Returns what a person does about the recommendation, as one command or step.
     *
     * @return sentence fragment starting with a verb
     */
    public String fix() {
        return fix;
    }
}
