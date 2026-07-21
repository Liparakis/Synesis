package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/** Initializes or inspects a local Synesis project state directory. */
@Command(name = "init", description = "Initialize a Synesis project.", mixinStandardHelpOptions = true)
public final class InitCommand implements Callable<Integer> {
    @Option(names = "--project", description = "Project directory.")
    private String project;
    private final CliRuntime runtime;

    /**
     * Creates an init command.
     * @param runtime composed CLI runtime
     */
    public InitCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /** Runs initialization. @return stable exit code */
    @Override
    public Integer call() {
        try {
            Path root = project == null ? Path.of(".") : Path.of(project);
            ProjectApplicationService.InitResult result = runtime.projectService().init(root);
            var location = result.location();
            runtime.terminal().stdout("INIT_RESULT=" + result.status());
            runtime.terminal().stdout("PROJECT_ROOT=" + location.root());
            runtime.terminal().stdout("SYNESIS_DIRECTORY=" + location.synesisDirectory());
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal().stdout("LOCAL_PROFILE=" + location.profile());
            runtime.terminal().stdout("NODE_ID=" + result.identity().nodeId());
            if (result.status() == ProjectApplicationService.InitStatus.SUCCESS) {
                runtime.terminal().stdout("GITIGNORE_RECOMMENDATION=.synesis/local/");
                runtime.terminal().stdout("NEXT_COMMAND=synesis constraint create --help");
            }
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            runtime.terminal().stdout("INIT_RESULT=CONFLICT");
            runtime.terminal().stderr("REASON=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stdout("INIT_RESULT=CONFLICT");
            runtime.terminal().stderr("REASON=Invalid project path.");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
