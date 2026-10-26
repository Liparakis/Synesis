package org.synesis.cli.command.link;

import java.util.Map;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Base of {@code link accept} and {@code link decline}: the owner's decision about a computer that opened the
 * project's invitation (ADR-0098, "Joining, revised"), made on the project's runtime that holds its session.
 */
abstract class LinkDecideCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final RuntimeControl.Source source;
    private final String commandName;
    @Parameters(index = "0", arity = "1", description = "Node ID of the waiting computer, as 'synesis link requests' lists it.")
    private String node;
    @Option(names = "--project", description = "Initialized project directory (default: the current directory).")
    private String project;

    LinkDecideCommand(CliRuntime runtime, RuntimeControl.Source source, String commandName) {
        this.runtime = runtime;
        this.source = source;
        this.commandName = commandName;
    }

    @Override
    public final Integer call() {
        if (!NodeIdentity.isNodeId(node)) {
            ErrorShape.print(runtime.terminal()::stderr, "INVALID_NODE_ID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        try {
            RuntimeControl control = source.open(ProjectOptions.locate(runtime, project).root());
            decide(runtime, control, commandName, node);
            return ExitCodes.OK;
        } catch (RuntimeControl.Rejected | ProjectApplicationException | java.io.IOException failure) {
            LinkCommand.refuse(runtime, failure);
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    /**
     * Tells the runtime the owner's decision and prints its outcome. The one path both {@code link accept|decline} and
     * the question {@code link invite} asks at a terminal take, so that a decision has one authority.
     *
     * @param commandName {@code accept-join} or {@code decline-join}
     * @param node        the waiting computer's node ID
     * @throws java.io.IOException     when the runtime cannot be reached
     * @throws RuntimeControl.Rejected when the runtime refuses, for example because the request is gone
     */
    static void decide(CliRuntime runtime, RuntimeControl control, String commandName, String node) throws java.io.IOException, RuntimeControl.Rejected {
        Map<String, Object> decided = control.command(commandName, Map.of("nodeId", node));
        runtime.terminal().stdout("JOIN_REQUEST=" + node + " STATE=" + decided.get("state"));
    }
}
