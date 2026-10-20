package org.synesis.cli.command.link;

import java.io.IOException;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for connecting this project to another computer through the project's running runtime. It also owns how the
 * link commands refuse, so every one of them prints the same shape.
 */
@Command(name = "link", description = "Connect this project to another computer; the connection stays with the project's runtime.", mixinStandardHelpOptions = true)
public final class LinkCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the link parent command.
     */
    public LinkCommand() {
    }

    /**
     * Prints {@code ERROR}, {@code MESSAGE} and {@code FIX} for the failure of a link command. The code is the one the
     * runtime or the project lookup gave (its own wording is kept when it came with one); a runtime that cannot be
     * reached is {@code PROJECT_RUNTIME_UNAVAILABLE} and an interrupted wait is {@code INTERRUPTED}.
     *
     * @param runtime composed runtime
     * @param failure what the command caught
     */
    static void refuse(CliRuntime runtime, Exception failure) {
        switch (failure) {
            case RuntimeControl.Rejected rejected when rejected.detail() != null -> ErrorShape.print(runtime.terminal()::stderr, rejected.code(), rejected.detail());
            case RuntimeControl.Rejected rejected -> ErrorShape.print(runtime.terminal()::stderr, rejected.code());
            case ProjectApplicationException project -> ErrorShape.print(runtime.terminal()::stderr, project.code());
            case IOException _ -> ErrorShape.print(runtime.terminal()::stderr, "PROJECT_RUNTIME_UNAVAILABLE");
            default -> ErrorShape.print(runtime.terminal()::stderr, "INTERRUPTED");
        }
    }

    /**
     * Prints help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
