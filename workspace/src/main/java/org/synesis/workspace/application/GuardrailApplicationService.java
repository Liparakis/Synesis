package org.synesis.workspace.application;

import java.nio.file.Path;
import java.util.Objects;

import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.guardrail.ActionGuardrail;

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
    public ActionCheckResult check(Path profile, Path projectRoot, String relativePath, String toolName,
            String description) {
        ActionGuardrail.Response response = ActionGuardrail.evaluate(profile,
                new ActionGuardrail.Request(projectRoot, relativePath, toolName, description));
        ProjectConstraint constraint = response.blockingConstraint() != null
                ? response.blockingConstraint() : response.warningConstraint();
        return new ActionCheckResult(response.outcome(), constraint, response.message());
    }

    /**
     * Structured action evaluation result.
     *
     * @param outcome           evaluation outcome
     * @param matchedConstraint matched constraint, if any
     * @param message           safe result message
     */
    public record ActionCheckResult(ActionGuardrail.Outcome outcome, ProjectConstraint matchedConstraint,
                                    String message) {

        /**
         * Validates the result.
         */
        public ActionCheckResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }
    }
}
