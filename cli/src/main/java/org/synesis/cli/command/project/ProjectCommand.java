package org.synesis.cli.command.project;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for project operations.
 */
@Command(name = "project", description = "Manage the current Synesis project.")
public final class ProjectCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the project parent command.
     */
    public ProjectCommand() {
    }

    /**
     * Prints project help.
     */
    @Override
    public void run() {
        spec.commandLine()
                .usage(spec.commandLine()
                        .getOut());
    }
}
