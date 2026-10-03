package org.synesis.workspace.application.guardrail;

import java.nio.file.Path;

import org.synesis.projectrecord.domain.ProjectConstraint;
import org.synesis.workspace.project.ActionGuardrail;

/**
 * Evaluates proposed actions against verified project constraints.
 */
public final class GuardrailApplicationService {

    /**
     * Creates the service.
     */
    public GuardrailApplicationService() {
    }

    /**
     * Evaluates one normalized action request.
     *
     * @param profile      local profile directory
     * @param projectRoot  project root used for request context
     * @param relativePath normalized project-relative target
     * @param toolName     action/tool name
     * @param description  action description
     * @return structured action result
     */
    public ActionCheckResult check(Path profile, Path projectRoot, String relativePath, String toolName, String description) {
        ActionGuardrail.Response response = ActionGuardrail.evaluate(profile, new ActionGuardrail.Request(projectRoot, relativePath, toolName, description));
        ProjectConstraint constraint = response.blockingConstraint() != null ? response.blockingConstraint() : response.warningConstraint();
        return new ActionCheckResult(response.outcome(), constraint, response.message());
    }

}
