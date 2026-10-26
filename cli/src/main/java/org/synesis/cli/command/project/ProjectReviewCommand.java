package org.synesis.cli.command.project;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for whether every work group of the project needs an independent review before its work is integrated. The
 * policy is an owner decision signed with the operator key and kept in the project's log (ADR-0104); it used to be
 * a field of {@code .synesis/project.json} that only a hand edit set.
 */
@Command(name = "review", description = "Set or show whether every work group needs an independent review.", mixinStandardHelpOptions = true)
public final class ProjectReviewCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the review parent command.
     */
    public ProjectReviewCommand() {
    }

    /**
     * Prints review help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
