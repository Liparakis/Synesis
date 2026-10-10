package org.synesis.workspace.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Names and locates the per-project administrative directory that cleanup, reconciliation, repair,
 * and session leases keep outside the control checkout under
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin}.
 *
 * <p>The directory and file names below are persisted layout: renaming one orphans the state
 * already written by earlier runs.</p>
 *
 * @since 1.0
 */
public final class ProjectAdministrativeLayout {

    private ProjectAdministrativeLayout() {
    }

    /**
     * Resolves the expected external Synesis workspace root for a control project.
     *
     * @param controlRoot control project root path
     * @return normalized absolute external workspace root path
     */
    public static Path workspaceRoot(Path controlRoot) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        String projectId = resolveProjectId(controlRoot);
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", projectId).toAbsolutePath().normalize();
    }

    private static String resolveProjectId(Path controlRoot) {
        Path projFile = controlRoot.resolve(".synesis/project.json");
        if (Files.exists(projFile)) {
            try {
                String content = Files.readString(projFile);
                int idx = content.indexOf("\"projectId\"");
                if (idx != -1) {
                    int colon = content.indexOf(':', idx);
                    int q1 = content.indexOf('"', colon + 1);
                    int q2 = content.indexOf('"', q1 + 1);
                    if (colon != -1 && q1 != -1 && q2 != -1) {
                        return content.substring(q1 + 1, q2);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "default-project";
    }

    /**
     * Resolves the administrative directory of a control project.
     *
     * @param controlRoot control project root path
     * @return administrative directory
     */
    public static Path adminDirectory(Path controlRoot) {
        return workspaceRoot(controlRoot).resolve("admin");
    }

    /**
     * Resolves the directory holding persisted cleanup plans.
     *
     * @param controlRoot control project root path
     * @return cleanup plans directory
     */
    public static Path cleanupPlans(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("cleanup-plans");
    }

    /**
     * Resolves the directory holding cleanup execution journals.
     *
     * @param controlRoot control project root path
     * @return cleanup executions directory
     */
    public static Path cleanupExecutions(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("cleanup-executions");
    }

    /**
     * Resolves the lock file held while a cleanup plan executes.
     *
     * @param controlRoot control project root path
     * @return cleanup execution lock file
     */
    public static Path cleanupExecutionLock(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("cleanup-execution.lock");
    }

    /**
     * Resolves the directory holding the resources a cleanup moved aside.
     *
     * @param controlRoot control project root path
     * @return quarantine directory
     */
    public static Path quarantine(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("quarantine");
    }

    /**
     * Resolves the directory holding persisted reconciliation plans.
     *
     * @param controlRoot control project root path
     * @return reconciliation plans directory
     */
    public static Path reconciliationPlans(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("reconciliation-plans");
    }

    /**
     * Resolves the directory holding reconciliation execution journals.
     *
     * @param controlRoot control project root path
     * @return reconciliation executions directory
     */
    public static Path reconciliationExecutions(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("reconciliation-executions");
    }

    /**
     * Resolves the lock file held while a reconciliation plan executes.
     *
     * @param controlRoot control project root path
     * @return reconciliation execution lock file
     */
    public static Path reconciliationExecutionLock(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("reconciliation-execution.lock");
    }

    /**
     * Resolves the directory holding persisted repair plans.
     *
     * @param controlRoot control project root path
     * @return repair plans directory
     */
    public static Path repairPlans(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("repair-plans");
    }

    /**
     * Resolves the directory holding repair execution journals.
     *
     * @param controlRoot control project root path
     * @return repair executions directory
     */
    public static Path repairExecutions(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("repair-executions");
    }

    /**
     * Resolves the lock file held while a repair plan executes.
     *
     * @param controlRoot control project root path
     * @return repair execution lock file
     */
    public static Path repairExecutionLock(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("repair-execution.lock");
    }

    /**
     * Resolves the directory holding the backups taken by one repair execution.
     *
     * @param controlRoot control project root path
     * @param executionId repair execution identifier
     * @return backup directory of that execution
     */
    public static Path repairBackups(Path controlRoot, String executionId) {
        return adminDirectory(controlRoot).resolve("repair-backups").resolve(executionId);
    }

    /**
     * Resolves the directory holding session leases.
     *
     * @param controlRoot control project root path
     * @return session leases directory
     */
    public static Path sessionLeases(Path controlRoot) {
        return adminDirectory(controlRoot).resolve("session-leases");
    }
}
