package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

/** Runs the Codex PreToolUse adapter with the provider's stdout contract. */
@Command(name = "codex", description = "Process a Codex apply_patch PreToolUse event.", mixinStandardHelpOptions = true)
public final class HookCodexCommand implements Callable<Integer> {
    private final CliRuntime runtime;

    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public HookCodexCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /** Processes one event. @return stable exit code */
    @Override
    public Integer call() {
        var result = runtime.hookService().codex(System.in);
        if (!result.responseJson().isEmpty()) runtime.terminal().stdoutRaw(result.responseJson());
        if ("UNSUPPORTED".equals(result.outcome()) && result.humanReason() != null) {
            runtime.terminal().stderr("HINT=" + result.humanReason());
        }
        return ExitCodes.OK;
    }
}
