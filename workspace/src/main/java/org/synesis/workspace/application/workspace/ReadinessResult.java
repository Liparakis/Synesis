package org.synesis.workspace.application.workspace;

import java.nio.file.Path;
import java.util.Objects;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/**
 * Result of the shared workspace readiness predicate.
 *
 * @param ready          whether the exact connection workspace is ready
 * @param binding        verified binding when ready
 * @param worktree       verified worker worktree when ready
 * @param response       bounded response when not ready
 * @param internalReason bounded internal classification for diagnostics
 */
public record ReadinessResult(boolean ready, ProviderSessionBindingService.Binding binding, Path worktree, AgentResponse response, String internalReason) {

    /**
     * Validates readiness result consistency.
     */
    public ReadinessResult {
        Objects.requireNonNull(internalReason, "internalReason");
        if (ready && (binding == null || worktree == null || response != null)) {
            throw new IllegalArgumentException("ready result must contain binding and worktree only");
        }
        if (!ready && (binding != null || worktree != null || response == null)) {
            throw new IllegalArgumentException("unready result must contain response only");
        }
    }
}
