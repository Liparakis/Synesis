package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.application.ConstraintApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;

/** Creates a signed typed project constraint. */
@Command(name = "create", description = "Create a project constraint.", mixinStandardHelpOptions = true)
public final class ConstraintCreateCommand implements Callable<Integer> {
    @Option(names = "--title", required = true) private String title;
    @Option(names = "--rationale", required = true) private String rationale;
    @Option(names = "--scope", required = true) private String scope;
    @Option(names = "--effect", defaultValue = "block") private String effect;
    @Option(names = "--project") private String project;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public ConstraintCreateCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Creates a constraint. @return stable exit code */
    @Override public Integer call() {
        try {
            var location = runtime.projectService().require(project == null ? Path.of(".") : Path.of(project));
            if (profile != null) location = new ProjectApplicationService.ProjectLocation(location.root(),
                    location.synesisDirectory(), location.metadataFile(), Path.of(profile), location.projectId(), location.createdAt());
            ProjectConstraint.Effect parsed = "warn".equalsIgnoreCase(effect)
                    ? ProjectConstraint.Effect.WARN : ProjectConstraint.Effect.BLOCK;
            var result = runtime.constraintService().create(location, title, rationale, scope, parsed);
            runtime.terminal().stdout("PROJECT_ID=" + result.projectId());
            runtime.terminal().stdout("RECORD_ID=" + result.recordId());
            runtime.terminal().stdout("DIGEST=" + result.digest());
            runtime.terminal().stdout("STATUS=" + result.status());
            runtime.terminal().stdout("CONSTRAINT_EFFECT=" + result.effect());
            runtime.terminal().stdout("CONSTRAINT_SCOPE=" + result.scope());
            runtime.terminal().stdout("SIGNATURE_VALID=true");
            return ExitCodes.OK;
        } catch (ConstraintApplicationService.ApplicationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stderr("ERROR=RECORD_INVALID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
