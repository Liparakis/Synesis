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

    /**
     * Prints text that an agent or a tool wrote, one {@code KEY=} line for each of its lines and
     * without control or formatting characters. Printed as it came, a lane's message could add
     * lines that read as records of this output, or drive the operator's terminal.
     *
     * @param runtime CLI runtime
     * @param key     key each line is printed under
     * @param text    the text; nothing is printed for empty text
     */
    public static void printText(CliRuntime runtime, String key, String text) {
        text.lines().forEach(line -> runtime.terminal().stdout(key + "=" + line.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", " ")));
    }

    /** Prints each request as stable lines: the request, what it says, and its evidence line by line. */
    static void print(CliRuntime runtime, List<OperatorRequest> requests) {
        runtime.terminal().stdout("OPERATOR_REQUESTS=" + requests.size());
        for (OperatorRequest request : requests) {
            runtime.terminal().stdout("OPERATOR_REQUEST=" + request.id() + " STATE=" + request.state() + " CONDITION=" + request.condition());
            if (!request.raisedBySynesis()) {
                runtime.terminal().stdout("RAISED_BY_LANE=" + request.raisedBy());
            }
            printText(runtime, "SUMMARY", request.summary());
            printText(runtime, "EVIDENCE", request.evidence());
            printText(runtime, "CLOSED_BECAUSE", request.closedBecause());
        }
    }
}
