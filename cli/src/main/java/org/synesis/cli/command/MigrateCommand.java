package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.migration.ProjectMigrationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Runs the prepared current-project schema migration workflow. */
@Command(name = "migrate", description = "Inspect or execute the initialized project migration.", mixinStandardHelpOptions = true)
public final class MigrateCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    @Option(names = "--dry-run", description = "Inspect migration state without changing files.") private boolean dryRun;
    @Option(names = "--prepare", description = "Create an immutable project migration plan.") private boolean prepare;
    @Option(names = "--show-plan", description = "Show a prepared plan by identifier.") private String showPlan;
    @Option(names = "--execute", description = "Execute a prepared plan by identifier.") private String execute;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public MigrateCommand(CliRuntime runtime) { this.runtime = runtime; }

    /** Executes the selected workflow. @return process exit code */
    @Override public Integer call() {
        try {
            ProjectMigrationService service = new ProjectMigrationService();
            if (prepare) {
                var plan = service.prepare(Path.of("."));
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=PLAN_PREPARED");
                runtime.terminal().stdout("PLAN_ID=" + plan.planId());
                return 0;
            }
            if (showPlan != null) {
                var plan = service.load(showPlan);
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=PLAN");
                runtime.terminal().stdout("PLAN_ID=" + plan.planId());
                return 0;
            }
            if (execute != null) {
                var result = service.execute(service.load(execute));
                runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=" + result.outcome());
                runtime.terminal().stdout("REASON=" + result.reason());
                return result.outcome() == ProjectMigrationService.Outcome.STALE ? 10 : 0;
            }
            var entry = service.inspect(Path.of("."));
            runtime.terminal().stdout("PROJECT_MIGRATION_RESULT=" + (dryRun || !prepare ? "DRY_RUN" : "DRY_RUN"));
            runtime.terminal().stdout("PROJECT_SCHEMA=" + entry.sourceSchema());
            runtime.terminal().stdout("PROJECT_MIGRATION_STATE=" + entry.outcome());
            return 0;
        } catch (Exception failure) {
            runtime.terminal().stderr("PROJECT_MIGRATION_RESULT=REQUIRES_HUMAN_REVIEW");
            return 10;
        }
    }
}
