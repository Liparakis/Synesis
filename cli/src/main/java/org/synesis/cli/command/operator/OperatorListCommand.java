package org.synesis.cli.command.operator;

import java.util.List;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.operator.OperatorRequest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lists the operator requests of a project with what each is waiting for.
 */
@Command(name = "list", description = "List what the project is waiting on an operator for.", mixinStandardHelpOptions = true)
public final class OperatorListCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Option(names = "--all", description = "Include requests that are closed.")
    private boolean all;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public OperatorListCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the requests. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var projection = ProjectOptions.locate(runtime, project).openCoordinationStore().operatorRequestProjection();
            print(runtime, all ? projection.all() : projection.open());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("OPERATOR_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    /** Prints each request as stable lines: the request, what it says, and its evidence unchanged. */
    static void print(CliRuntime runtime, List<OperatorRequest> requests) {
        runtime.terminal().stdout("OPERATOR_REQUESTS=" + requests.size());
        for (OperatorRequest request : requests) {
            runtime.terminal().stdout("OPERATOR_REQUEST=" + request.id() + " STATE=" + request.state() + " CONDITION=" + request.condition());
            if (!request.raisedBySynesis()) {
                runtime.terminal().stdout("RAISED_BY_LANE=" + request.raisedBy());
            }
            runtime.terminal().stdout("SUMMARY=" + request.summary());
            request.evidence().lines().forEach(line -> runtime.terminal().stdout("EVIDENCE=" + line));
            if (!request.closedBecause().isEmpty()) {
                runtime.terminal().stdout("CLOSED_BECAUSE=" + request.closedBecause());
            }
        }
    }
}
