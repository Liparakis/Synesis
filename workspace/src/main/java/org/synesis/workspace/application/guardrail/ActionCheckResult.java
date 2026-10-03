package org.synesis.workspace.application.guardrail;

import java.util.Objects;
import org.synesis.projectrecord.domain.ProjectConstraint;
import org.synesis.workspace.project.ActionGuardrail;

/**
 * Structured action evaluation result.
 *
 * @param outcome           evaluation outcome
 * @param matchedConstraint matched constraint, if any
 * @param message           safe result message
 */
public record ActionCheckResult(ActionGuardrail.Outcome outcome, ProjectConstraint matchedConstraint, String message) {

    /**
     * Validates the result.
     */
    public ActionCheckResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(message, "message");
    }
}
