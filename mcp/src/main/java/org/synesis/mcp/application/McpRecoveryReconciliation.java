package org.synesis.mcp.application;

import java.nio.file.Path;

import org.synesis.workspace.lifecycle.reconciliation.ReconciliationService;

/**
 * Runs the durable recovery reconciliation for this connection: once after its first verified
 * admission, and again on every ordinary inbox poll and clean transport close.
 *
 * <p>Process absence and the configured grace period remain the only recovery trigger. The
 * once-per-connection admission latch is owned here because only the admission hook reads or sets
 * it; the inbox-poll and close paths deliberately do not consult it.</p>
 */
final class McpRecoveryReconciliation {

    /**
     * Reconciles eligible durable work after this connection is admitted.
     */
    private final ReconciliationService reconciliationService;
    /**
     * Whether the one-time verified-admission reconciliation hook has run for this connection.
     */
    private boolean admissionReconciliationAttempted;

    /**
     * Creates the reconciliation hooks for one connection.
     *
     * @param reconciliationService service that reconciles eligible durable work
     */
    McpRecoveryReconciliation(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * Runs the existing durable recovery plan once after this exact connection has been admitted and
     * its own lease has been renewed. Process absence and the configured grace period remain the only
     * recovery trigger; this hook does not transfer claims or issue continuation authority.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     */
    void reconcileAfterAdmission(Path activeProjectRoot) {
        if (admissionReconciliationAttempted) {
            return;
        }
        admissionReconciliationAttempted = true;
        try {
            reconciliationService.reconcileEligible(activeProjectRoot);
        } catch (Exception failure) {
            System.err.println("SYNESIS_MCP_RECONCILIATION_FAILURE=" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    /**
     * Re-evaluates recovery eligibility on each ordinary durable inbox poll.
     *
     * @param activeProjectRoot the connection's currently selected control project root
     */
    void reconcileRecoveryBeforeInboxPoll(Path activeProjectRoot) {
        if (activeProjectRoot == null) {
            return;
        }
        try {
            var execution = reconciliationService.reconcileEligible(activeProjectRoot);
            execution.ifPresent(result -> System.err.println("SYNESIS_MCP_RECOVERY_RECONCILIATION=completed:" + result.completedCount() + ",failed:" + result.failedCount()));
        } catch (Exception failure) {
            System.err.println("SYNESIS_MCP_RECOVERY_RECONCILIATION_FAILURE=" + failure.getClass().getSimpleName());
        }
    }
}
