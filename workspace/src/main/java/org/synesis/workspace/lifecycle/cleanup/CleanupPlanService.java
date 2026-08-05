package org.synesis.workspace.lifecycle.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * Application service that coordinates lifecycle resource inventory discovery, path safety verification,
 * and eligibility evaluation to generate immutable {@link CleanupPlan} instances.
 *
 * <p>This service is strictly read-only and performs zero filesystem, Git, or process mutations.
 *
 * @since 1.0
 */
public final class CleanupPlanService {

    private final ProjectApplicationService projectService;
    private final LifecycleInventoryService inventoryService;
    private final CleanupEligibilityService eligibilityService;
    private final RetentionPolicy retentionPolicy;

    /**
     * Creates a cleanup plan service with default application services and retention policy.
     */
    public CleanupPlanService() {
        this(new ProjectApplicationService(), new LifecycleInventoryService(), new CleanupEligibilityService(), new RetentionPolicy());
    }

    /**
     * Creates a cleanup plan service with explicit services and retention policy.
     *
     * @param projectService     project application service
     * @param inventoryService   lifecycle inventory discovery service
     * @param eligibilityService cleanup eligibility evaluation service
     * @param retentionPolicy    retention policy configuration
     */
    public CleanupPlanService(
            ProjectApplicationService projectService,
            LifecycleInventoryService inventoryService,
            CleanupEligibilityService eligibilityService,
            RetentionPolicy retentionPolicy
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService");
        this.retentionPolicy = Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    }

    /**
     * Generates a read-only cleanup plan for the specified control project directory.
     *
     * @param controlRoot control project root path
     * @return generated immutable cleanup plan
     * @throws ProjectApplicationService.ProjectApplicationException if project location fails
     * @throws IOException if inventory discovery fails
     */
    public CleanupPlan generatePlan(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectApplicationService.ProjectLocation location = projectService.locate(root);
        String projectId = location.projectId().toString();

        List<LifecycleInventoryService.DiscoveredResource> discovered = inventoryService.discoverResources(root);

        List<CleanupPlanEntry> entries = new ArrayList<>();
        int protectedCount = 0;
        int activeCount = 0;
        int recoverableCount = 0;
        int diagnosticRetainedCount = 0;
        int cleanupEligibleCount = 0;
        int orphanedCount = 0;
        long reclaimableBytes = 0L;
        long totalWorkspaceBytes = 0L;

        for (LifecycleInventoryService.DiscoveredResource resource : discovered) {
            CleanupPlanEntry entry = eligibilityService.evaluateResource(root, resource);
            entries.add(entry);

            totalWorkspaceBytes += entry.estimatedBytes();

            switch (entry.classification()) {
                case PROTECTED -> protectedCount++;
                case ACTIVE -> activeCount++;
                case RECOVERABLE -> recoverableCount++;
                case DIAGNOSTIC_RETAINED -> diagnosticRetainedCount++;
                case CLEANUP_ELIGIBLE -> {
                    cleanupEligibleCount++;
                    reclaimableBytes += entry.estimatedBytes();
                }
                case ORPHANED -> orphanedCount++;
            }
        }

        boolean diskBudgetWarning = totalWorkspaceBytes > retentionPolicy.storageWarningThresholdBytes();

        return new CleanupPlan(
                projectId,
                retentionPolicy.now().toEpochMilli(),
                discovered.size(),
                protectedCount,
                activeCount,
                recoverableCount,
                diagnosticRetainedCount,
                cleanupEligibleCount,
                orphanedCount,
                reclaimableBytes,
                diskBudgetWarning,
                Collections.unmodifiableList(entries)
        );
    }

    /** Returns the bounded command-retention projection alongside a cleanup review.
     * @return read-only durable command diagnostic report
     */
    public ProjectCommandDiagnostics.Report commandNamespaceDiagnostics() {
        return ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
    }
}
