package org.synesis.cli.command.link;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Lists the computers that opened this project's invitation and wait for the owner to accept or decline them
 * (ADR-0098, "Joining, revised").
 */
@Command(name = "requests", description = "List the computers waiting to join this project.", mixinStandardHelpOptions = true)
public final class LinkRequestsCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final RuntimeControl.Source source;
    @Option(names = "--project", description = "Initialized project directory (default: the current directory).")
    private String project;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkRequestsCommand(CliRuntime runtime) {
        this(runtime, HttpRuntimeControl.daemon());
    }

    LinkRequestsCommand(CliRuntime runtime, RuntimeControl.Source source) {
        this.runtime = runtime;
        this.source = source;
    }

    @Override
    public Integer call() {
        try {
            RuntimeControl control = source.open(ProjectOptions.locate(runtime, project).root());
            List<?> requests = control.read("snapshot").get("onboarding") instanceof Map<?, ?> onboarding && onboarding.get("joinRequests") instanceof List<?> found ? found : List.of();
            runtime.terminal().stdout("JOIN_REQUESTS=" + requests.size());
            for (Object request : requests) {
                if (request instanceof Map<?, ?> fields) {
                    runtime.terminal().stdout("JOIN_REQUEST=" + fields.get("nodeId") + " REQUESTED_AT=" + fields.get("requestedAt") + " EXPIRES_AT=" + fields.get("expiresAt"));
                }
            }
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
