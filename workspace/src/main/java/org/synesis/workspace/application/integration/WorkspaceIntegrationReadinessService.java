package org.synesis.workspace.application.integration;

import java.util.Objects;

/** Shared application adapter for pre-merge readiness checks. */
public final class WorkspaceIntegrationReadinessService {
    private final IntegrationCompatibilityService compatibilityService = new IntegrationCompatibilityService();

    /** Creates the readiness adapter. */
    public WorkspaceIntegrationReadinessService() { }

    /** Checks an immutable integration candidate.
     * @param request explicit compatibility facts
     * @return deterministic actionable result
     */
    public IntegrationCompatibilityService.CheckResult check(IntegrationCompatibilityService.CheckRequest request) {
        return compatibilityService.check(Objects.requireNonNull(request, "request"));
    }
}
