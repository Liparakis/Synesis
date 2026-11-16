package org.synesis.cli.command.project;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for the project's signed policy as a whole (ADR-0104): what is in force, and adopting the policy a project
 * kept in {@code .synesis/project.json} before decisions were signed.
 */
@Command(name = "policy", description = "Show the project's signed policy, sign the one kept in project.json, or restore the operator key.", mixinStandardHelpOptions = true)
public final class ProjectPolicyCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    /**
     * Creates the policy parent command.
     */
    public ProjectPolicyCommand() {
    }

    /**
     * Prints policy help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
