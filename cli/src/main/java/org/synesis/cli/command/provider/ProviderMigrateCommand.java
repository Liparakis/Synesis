package org.synesis.cli.command.provider;

import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.migration.ProviderConfigMigrationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the prepared provider MCP configuration migration workflow.
 */
@Command(name = "migrate", description = "Migrate supported provider configuration safely.", mixinStandardHelpOptions = true)
public final class ProviderMigrateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--dry-run", description = "Inspect provider migration state without changing files.")
    @SuppressWarnings("unused")
    private boolean dryRun;
    @Option(names = "--prepare", description = "Create an immutable provider migration plan.")
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
    public ProviderMigrateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes the selected safe workflow. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            ProviderConfigMigrationService service = new ProviderConfigMigrationService();
            if (prepare) {
                var plan = service.prepare();
                runtime.terminal()
                        .stdout("PROVIDER_MIGRATION_RESULT=PLAN_PREPARED");
                runtime.terminal()
                        .stdout("PLAN_ID=" + plan.planId());
                return 0;
            }
            if (showPlan != null) {
                var plan = service.load(showPlan);
                runtime.terminal()
                        .stdout("PROVIDER_MIGRATION_RESULT=PLAN");
                runtime.terminal()
                        .stdout("PLAN_ID=" + plan.planId());
                runtime.terminal()
                        .stdout("PROVIDERS_INSPECTED=" + plan.entries()
                                .size());
                return 0;
            }
            if (execute != null) {
                var result = service.execute(service.load(execute));
                runtime.terminal()
                        .stdout("PROVIDER_MIGRATION_RESULT=" + result.outcome());
                runtime.terminal()
                        .stdout("BACKUPS_CREATED=" + result.backupsCreated());
                return result.outcome() == ProviderConfigMigrationService.Outcome.STALE ? 10 : 0;
            }
            var entries = service.inspect();
            runtime.terminal()
                    .stdout("PROVIDER_MIGRATION_RESULT=DRY_RUN");
            runtime.terminal()
                    .stdout("PROVIDERS_INSPECTED=" + entries.size());
            entries.forEach(entry -> runtime.terminal()
                    .stdout("PROVIDER_" + entry.provider()
                            .toUpperCase() + "=" + entry.outcome()));
            return 0;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("PROVIDER_MIGRATION_RESULT=REQUIRES_HUMAN_REVIEW");
            return 10;
        }
    }
}
