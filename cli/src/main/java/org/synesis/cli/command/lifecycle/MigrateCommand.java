package org.synesis.cli.command.lifecycle;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CommandModePolicy;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.migration.ProjectMigrationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the prepared current-project schema migration workflow.
 */
@Command(name = "migrate", description = "Inspect or execute the initialized project migration.", mixinStandardHelpOptions = true)
public final class MigrateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--dry-run", description = "Inspect migration state without changing files.")
    private boolean dryRun;
    @Option(names = "--prepare", description = "Create an immutable project migration plan.")
    private boolean prepare;
    @Option(names = "--show-plan", description = "Show a prepared plan by identifier.")
    private String showPlan;
    @Option(names = "--execute", description = "Execute a prepared plan by identifier.")
    private String execute;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public MigrateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes the selected workflow. @return process exit code
     */
    @Override
    public Integer call() {
        if (CommandModePolicy.hasConflictingModes(dryRun, prepare, showPlan != null, execute != null)) {
            runtime.terminal().stderr("USAGE_ERROR=CONFLICTING_MODES");
            return ExitCodes.USAGE;
        }
        try {
            ProjectMigrationService service = new ProjectMigrationService();
            Path currentProject = Path.of(".");
            if (prepare) {
                var plan = service.prepare(currentProject);
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=PLAN_PREPARED");
                runtime.terminal().stdout("PLAN_ID=" + plan.planId());
                return ExitCodes.OK;
            }
            if (showPlan != null) {
                var plan = service.load(showPlan);
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=PLAN");
                runtime.terminal().stdout("PLAN_ID=" + plan.planId());
                return ExitCodes.OK;
            }
            if (execute != null) {
                var result = service.execute(service.load(execute));
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=" + result.outcome());
                runtime.terminal().stdout("REASON=" + result.reason());
                return switch (result.outcome()) {
                    case FAILED, FAILED_RESTORED, REQUIRES_HUMAN_REVIEW, ROLLBACK_UNSAFE, STALE, UNSUPPORTED_SCHEMA -> ExitCodes.LOCAL_CONFIGURATION;
                    default -> ExitCodes.OK;
                };
            }
            var entry = service.inspect(currentProject);
            runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=DRY_RUN");
            runtime.terminal().stdout("PROJECT_SCHEMA=" + entry.sourceSchema());
            runtime.terminal().stdout("PROJECT_MIGRATION_STATE=" + entry.outcome());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("PROJECT_MIGRATION_RESULT=REQUIRES_HUMAN_REVIEW");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
