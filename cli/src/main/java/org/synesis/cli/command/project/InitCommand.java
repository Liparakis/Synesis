package org.synesis.cli.command.project;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectInitStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Initializes or inspects a local Synesis project state directory.
 */
@Command(name = "init", description = "Initialize a Synesis project.", mixinStandardHelpOptions = true)
public final class InitCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Project directory.")
    private String project;

    /**
     * Creates an init command.
     *
     * @param runtime composed CLI runtime
     */
    public InitCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * What a newcomer does after the project exists: connect an agent, start it, look at what happens. The
     * agent finds its instructions in the installed {@code synesis-provider-manual} skill, so none is written
     * into the project (ADR-0103 decision 9). Printed on a re-run too, because running {@code init} again is
     * how one learns the way back to connecting an agent.
     */
    private void printNextSteps(ProjectInitResult result) {
        if (result.retiredInstructions().contains("AGENTS.md")) {
            runtime.terminal().stdout("NEXT_STEP=Commit the change to AGENTS.md if Git tracks it: Synesis removed its old section from your file and kept your text");
        }
        runtime.terminal().stdout("NEXT_COMMAND=synesis provider install claude");
        runtime.terminal().stdout("NEXT_STEP=Connect an agent to this project: synesis provider install claude (or: synesis provider install codex)");
        runtime.terminal().stdout("NEXT_STEP=Start the agent in this folder (claude or codex) and tell it what to do");
        runtime.terminal().stdout("NEXT_STEP=See what is happening: synesis ui (browser) or synesis coordination status; what needs you: synesis operator list");
    }

    /**
     * Runs initialization. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            Path root = project == null ? Path.of(".") : Path.of(project);
            runtime.projectService().requireGitRepository(root);
            ProjectInitResult result = runtime.projectService().init(root);
            var location = result.location();
            try {
                new KnownProjectRegistry().observe(location);
                runtime.terminal().stdout("PROJECT_DISCOVERY=RECORDED");
            } catch (KnownProjectRegistry.RegistryException discoveryFailure) {
                runtime.terminal().stderr("PROJECT_DISCOVERY_ERROR=" + discoveryFailure.code());
            }
            runtime.terminal().stdout("INIT_RESULT=" + result.status());
            runtime.terminal().stdout("PROJECT_ROOT=" + location.root());
            runtime.terminal().stdout("SYNESIS_DIRECTORY=" + location.synesisDirectory());
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal().stdout("LOCAL_PROFILE=" + location.profile());
            runtime.terminal().stdout("NODE_ID=" + result.identity().nodeId());
            runtime.terminal().stdout("GIT_HEAD_STATUS=" + result.gitHeadStatus());
            result.retiredInstructions().forEach(path -> runtime.terminal().stdout("INSTRUCTIONS_REMOVED=" + path));
            if (result.status() == ProjectInitStatus.SUCCESS) {
                runtime.terminal().stdout("GITIGNORE_RECOMMENDATION=.synesis/local/");
            }
            printNextSteps(result);
            return ExitCodes.OK;
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            runtime.terminal().stdout("INIT_RESULT=CONFLICT");
            runtime.terminal().stderr("ERROR_CODE=" + failure.code());
            runtime.terminal().stderr("REASON=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        } catch (RuntimeException failure) {
            runtime.terminal().stdout("INIT_RESULT=CONFLICT");
            runtime.terminal().stderr("REASON=Invalid project path.");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
