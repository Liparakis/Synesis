package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.guardrail.ActionGuardrail;

/** Evaluates a proposed action against the current project constraints. */
@Command(name = "check-action", description = "Check an action against project constraints.", mixinStandardHelpOptions = true)
public final class CheckActionCommand implements Callable<Integer> {
    @Option(names = "--scope", required = true) private String scope;
    @Option(names = "--action", required = true) private String action;
    @Option(names = "--project") private String project;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public CheckActionCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Checks an action. @return stable exit code */
    @Override public Integer call() {
        try {
            var location = runtime.projectService().require(Path.of(project == null ? "." : project));
            Path resolved = profile == null ? location.profile() : Path.of(profile);
            var result = runtime.guardrailService().check(resolved, location.root(), scope, "check-action", action);
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            if (result.outcome() == ActionGuardrail.Outcome.BLOCKED || result.outcome() == ActionGuardrail.Outcome.WARNING) {
                ProjectConstraint c = result.matchedConstraint();
                runtime.terminal().stdout("ACTION_RESULT=" + (result.outcome() == ActionGuardrail.Outcome.BLOCKED ? "BLOCKED" : "WARNING"));
                runtime.terminal().stdout("MATCHED_CONSTRAINT_ID=" + (c == null ? "" : c.recordId()));
                runtime.terminal().stdout("CONSTRAINT_EFFECT=" + (c == null ? "" : c.effect()));
                runtime.terminal().stdout("CONSTRAINT_TITLE=" + (c == null ? "" : c.title()));
                runtime.terminal().stdout("CONSTRAINT_RATIONALE=" + (c == null ? "" : c.rationale()));
                runtime.terminal().stdout("MATCHED_SCOPE=" + scope);
                runtime.terminal().stdout("REASON=" + result.message().replace('\n', ' '));
                if (result.outcome() == ActionGuardrail.Outcome.BLOCKED) {
                    runtime.terminal().stderr("HINT=Action blocked by project constraint. Re-plan required.");
                    return ExitCodes.LOCAL_CONFIGURATION;
                }
            } else {
                runtime.terminal().stdout("ACTION_RESULT=ALLOWED");
                runtime.terminal().stdout("MATCHED_CONSTRAINT_COUNT=0");
                runtime.terminal().stdout("REASON=No active project constraint collides with action.");
            }
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException | RuntimeException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_NOT_CONFIGURED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
