package org.synesis.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.synesis.projectrecord.DecisionRecord;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;

/**
 * Harness-neutral action guardrail evaluator.
 *
 * <p>Evaluates target repository-relative paths against verified project constraints
 * stored within a workspace profile.
 *
 * @since 1.0
 */
public final class ActionGuardrail {

    /** Action evaluation outcome classification. */
    public enum Outcome {
        /** Operation is allowed with no matching constraints. */
        ALLOWED,
        /** Operation matches a WARN constraint. */
        WARNING,
        /** Operation matches a BLOCK constraint. */
        BLOCKED,
        /** Operation is unsupported for guardrail validation. */
        UNSUPPORTED,
        /** Input request or path is invalid. */
        INVALID_INPUT
    }

    /**
     * Action check evaluation request.
     *
     * @param projectRoot  project workspace root directory
     * @param relativePath normalized repository-relative file path
     * @param toolName     tool/action identifier
     * @param description  optional action description or context
     */
    public record Request(Path projectRoot, String relativePath, String toolName, String description) {
    }

    /**
     * Action check evaluation response.
     *
     * @param outcome            evaluation outcome classification
     * @param blockingConstraint matched BLOCK constraint, if any
     * @param warningConstraint  matched WARN constraint, if any
     * @param message            human readable status explanation or denial reason
     */
    public record Response(
            Outcome outcome,
            ProjectConstraint blockingConstraint,
            ProjectConstraint warningConstraint,
            String message
    ) {
    }

    private ActionGuardrail() {
    }

    /**
     * Evaluates an action check request against the active constraints in a workspace profile.
     *
     * @param profile workspace profile directory path containing project configuration and record store
     * @param request evaluation request containing normalized relative path
     * @return evaluation response containing outcome and matched constraints
     */
    public static Response evaluate(Path profile, Request request) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(request, "request");
        if (request.relativePath() == null || request.relativePath().isBlank()) {
            return new Response(Outcome.INVALID_INPUT, null, null, "No target relative path specified");
        }

        ProjectConfig config;
        DecisionStore store;
        List<DecisionRecord> heads;
        try {
            config = ProjectConfig.load(profile.resolve("project.conf"));
            store = new DecisionStore(profile.resolve("records"), config.projectId());
            heads = store.verifiedHeads(1_000);
        } catch (Exception e) {
            return new Response(Outcome.INVALID_INPUT, null, null, "Project is not configured for profile: " + e.getMessage());
        }

        List<ProjectConstraint> activeConstraints = new ArrayList<>();
        for (DecisionRecord r : heads) {
            ProjectConstraint c = ProjectConstraint.fromRecord(r);
            if (c != null && c.status() == ProjectConstraint.ConstraintStatus.ACTIVE) {
                activeConstraints.add(c);
            }
        }
        activeConstraints = ProjectConstraint.filterEffectiveActive(activeConstraints);

        ProjectConstraint blockingConstraint = null;
        ProjectConstraint warningConstraint = null;

        for (ProjectConstraint c : activeConstraints) {
            try {
                if (c.appliesTo(request.relativePath())) {
                    if (c.effect() == ProjectConstraint.Effect.BLOCK) {
                        blockingConstraint = c;
                        break;
                    } else if (c.effect() == ProjectConstraint.Effect.WARN && warningConstraint == null) {
                        warningConstraint = c;
                    }
                }
            } catch (IllegalArgumentException e) {
                return new Response(Outcome.INVALID_INPUT, null, null, "Invalid scope path format: " + e.getMessage());
            }
        }

        if (blockingConstraint != null) {
            String denialReason = "Synesis blocked this edit.\n\n"
                    + "Constraint: " + blockingConstraint.title() + "\n"
                    + "Effect: BLOCK\n"
                    + "Scope: " + String.join(", ", blockingConstraint.scopes()) + "\n"
                    + "Reason: " + blockingConstraint.rationale() + "\n\n"
                    + "Re-plan without modifying the protected scope.";
            return new Response(Outcome.BLOCKED, blockingConstraint, warningConstraint, denialReason);
        }

        if (warningConstraint != null) {
            String warnMsg = "Synesis Warning: Active project constraint '" + warningConstraint.title()
                    + "' applies to this scope. Rationale: " + warningConstraint.rationale();
            return new Response(Outcome.WARNING, null, warningConstraint, warnMsg);
        }

        return new Response(Outcome.ALLOWED, null, null, "Allowed");
    }
}
