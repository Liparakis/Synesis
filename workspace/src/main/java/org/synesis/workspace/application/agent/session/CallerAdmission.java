package org.synesis.workspace.application.agent.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * The verified project, provider session binding, and node identity behind one agent tool call, or
 * the response that refuses the call because they cannot be established.
 *
 * <p>A binding without a worktree is a refusal: it carries no write authority for the caller.</p>
 *
 * @param location         verified project location
 * @param binding          exact provider session binding of the caller
 * @param identity         durable node identity of the project
 * @param completedBinding whether {@code binding} was resolved as a completed session rather than
 *                         an active one
 * @param refusal          response to return instead of acting, or {@code null} when admitted
 */
public record CallerAdmission(ProjectLocation location, ProviderSessionBindingService.Binding binding, NodeIdentity identity, boolean completedBinding, AgentResponse refusal) {

    /**
     * Work done once the caller is admitted and the project append lock is held.
     */
    @FunctionalInterface
    public interface LockedAction {

        /**
         * Performs the work.
         *
         * @param admission the admitted caller
         * @return the tool response
         * @throws Exception when the work fails; the caller reports an internal failure
         */
        AgentResponse run(CallerAdmission admission) throws Exception;
    }

    /**
     * Returns whether the call must be answered with {@link #refusal()} instead of being acted on.
     *
     * @return {@code true} when the caller was not admitted
     */
    public boolean refused() {
        return refusal != null;
    }

    /**
     * Admits a caller by locating the project, resolving its session binding, and loading the node
     * identity, in that order.
     *
     * @param projectRoot            control project root
     * @param projectService         project locator
     * @param resolver               session authority resolver
     * @param provider               provider identifier
     * @param connectionInstanceId   provider connection instance
     * @param fallBackToCompleted    whether a caller with no active binding is admitted on its
     *                               completed binding, so a finished session can replay its result
     * @return the admitted caller, or a refusal
     */
    public static CallerAdmission admit(Path projectRoot,
            ProjectApplicationService projectService,
            SessionAuthorityResolver resolver,
            String provider,
            String connectionInstanceId,
            boolean fallBackToCompleted) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!projectInitialized(root)) {
            return refused(workspaceNotReady());
        }
        try {
            ProjectLocation location = projectService.locate(root);
            ProviderSessionBindingService.Binding binding;
            boolean completed = false;
            try {
                binding = resolver.resolve(location, provider, connectionInstanceId);
            } catch (Exception activeResolutionFailure) {
                if (!fallBackToCompleted) {
                    throw activeResolutionFailure;
                }
                binding = resolver.resolveCompleted(location, provider, connectionInstanceId);
                completed = true;
            }
            if (binding.worktreePath() == null) {
                return refused(sessionNotReady());
            }
            return new CallerAdmission(location, binding, location.loadOrCreateIdentity(), completed, null);
        } catch (Exception ex) {
            return refused(workspaceNotReady());
        }
    }

    /**
     * Admits a caller while holding the project append lock, taking the lock before the binding is
     * resolved so that everything {@code action} decides from the event log is decided under it.
     *
     * @param projectRoot          control project root
     * @param projectService       project locator
     * @param resolver             session authority resolver
     * @param provider             provider identifier
     * @param connectionInstanceId provider connection instance
     * @param action               work to run once admitted and locked
     * @return the refusal, the lock-unavailable response, the action's response, or an internal failure
     */
    public static AgentResponse underAppendLock(Path projectRoot,
            ProjectApplicationService projectService,
            SessionAuthorityResolver resolver,
            String provider,
            String connectionInstanceId,
            LockedAction action) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!projectInitialized(root)) {
            return workspaceNotReady();
        }
        ProjectLocation location;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            identity = location.loadOrCreateIdentity();
        } catch (Exception ex) {
            return workspaceNotReady();
        }

        ProjectAppendLock appendLock;
        try {
            appendLock = ProjectAppendLock.acquire(location.coordinationDirectory());
        } catch (IOException unavailable) {
            return appendLockUnavailable();
        }
        try (appendLock) {
            if (!appendLock.isHeld()) {
                return appendLockUnavailable();
            }
            ProviderSessionBindingService.Binding binding;
            try {
                binding = resolver.resolve(location, provider, connectionInstanceId);
            } catch (Exception ex) {
                return workspaceNotReady();
            }
            if (binding.worktreePath() == null) {
                return sessionNotReady();
            }
            return action.run(new CallerAdmission(location, binding, identity, false, null));
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }

    private static boolean projectInitialized(Path root) {
        return Files.exists(root.resolve(".synesis/project.json"));
    }

    private static CallerAdmission refused(AgentResponse refusal) {
        return new CallerAdmission(null, null, null, false, refusal);
    }

    private static AgentResponse workspaceNotReady() {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
    }

    private static AgentResponse sessionNotReady() {
        return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
    }

    private static AgentResponse appendLockUnavailable() {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of("reason", "EVENT_APPEND_LOCK_UNAVAILABLE"));
    }
}
