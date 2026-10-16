package org.synesis.cli.command.link;

import java.util.Map;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
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
            runtime.terminal().stderr("ERROR=INVALID_NODE_ID");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        try {
            RuntimeControl control = source.open(ProjectOptions.locate(runtime, project).root());
            Map<String, Object> decided = control.command(commandName, Map.of("nodeId", node));
            runtime.terminal().stdout("JOIN_REQUEST=" + node + " STATE=" + decided.get("state"));
            return ExitCodes.OK;
        } catch (RuntimeControl.Rejected rejected) {
            runtime.terminal().stderr("ERROR=" + rejected.code());
        } catch (ProjectApplicationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
        } catch (java.io.IOException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_RUNTIME_UNAVAILABLE");
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }
}
