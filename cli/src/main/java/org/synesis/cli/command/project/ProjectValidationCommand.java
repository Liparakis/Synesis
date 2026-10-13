package org.synesis.cli.command.project;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.ProjectValidationChange;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Parent for the command a project declares that must pass before its work is integrated.
 *
 * <p>The command is project policy. It is set by whoever has authority over the project, from
 * here, and never by an agent: an agent cannot write under {@code .synesis}.</p>
 */
@Command(name = "validation", description = "Set, show or clear the command that must pass before work is integrated.", mixinStandardHelpOptions = true)
public final class ProjectValidationCommand implements Runnable {

    private static final String METADATA_PATH = ".synesis/project.json";

    @Spec
    private CommandSpec spec;

    /**
     * Creates the validation parent command.
     */
    public ProjectValidationCommand() {
    }

    /**
     * Prints validation help.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * Prints the validation command a project declares as stable lines. The argument vector is
     * printed as one JSON array, and it and the directory without control or formatting
     * characters: the project file may have been written by someone else, and printed as it came
     * either could add lines that read as records of this output, or drive the operator's terminal.
     *
     * @param runtime  CLI runtime
     * @param location the project as its metadata reads
     */
    static void print(CliRuntime runtime, ProjectLocation location) {
        if (location.validation() == null) {
            runtime.terminal().stdout("VALIDATION=NONE");
            return;
        }
        runtime.terminal().stdout("VALIDATION=DECLARED");
        runtime.terminal().stdout("VALIDATION_ARGV=" + printable(ProviderJson.write(location.validation().argv())));
        runtime.terminal().stdout("VALIDATION_DIRECTORY=" + printable(location.validation().workingDirectory()));
        runtime.terminal().stdout("VALIDATION_TIMEOUT_SECONDS=" + location.validation().timeoutSeconds());
        runtime.terminal().stdout("VALIDATION_ALSO_IN_LANE=" + location.validationAlsoInLane());
    }

    /**
     * Says what was done to the project file: nothing when the project already declared what was
     * asked, a rewrite, and a commit where Git tracks the file.
     *
     * @param runtime CLI runtime
     * @param change  what setting or clearing the command did
     */
    static void printChanged(CliRuntime runtime, ProjectValidationChange change) {
        if (change.changed()) {
            runtime.terminal().stdout("PROJECT_FILE_CHANGED=" + METADATA_PATH);
        }
        if (!change.commit().isEmpty()) {
            runtime.terminal().stdout("PROJECT_FILE_COMMITTED=" + change.commit());
        }
    }

    private static String printable(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", " ");
    }

    /**
     * Prints why a validation command could not be set, shown or cleared.
     *
     * @param runtime CLI runtime
     * @param failure the application failure
     */
    static void printFailure(CliRuntime runtime, ProjectApplicationException failure) {
        runtime.terminal().stderr("ERROR=" + failure.code());
        runtime.terminal().stderr("REASON=" + failure.getMessage() + (failure.getCause() == null || failure.getCause().getMessage() == null ? "" : ": " + failure.getCause().getMessage()));
    }
}
