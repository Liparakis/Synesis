package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.transport.OnboardingFailure;
import org.synesis.link.identity.IdentityBootstrap;
import java.nio.file.Path;

/**
 * Adapts {@code synesis identity show} to the Link façade.
 */
@Command(name = "show", description = "Load or create and display the local node ID.", mixinStandardHelpOptions = true)
public final class IdentityShowCommand implements Callable<Integer> {
    @Option(names = "--project", description = "Initialized project directory.") private String project;
    @Option(names = "--profile", description = "Advanced local profile override.") private String profile;
    private final CliRuntime runtime;

    /**
     * Creates an identity command with one manually composed runtime.
     *
     * @param runtime manually composed CLI runtime
     */
    public IdentityShowCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Runs identity bootstrap. @return stable exit code
     */
    @Override
    public Integer call() {
        if (project != null || profile != null) {
            try {
                Path selected = profile == null
                        ? runtime.projectService().require(Path.of(project)).profile()
                        : Path.of(profile).toAbsolutePath().normalize();
                var identity = new IdentityBootstrap(selected.resolve("link")).loadOrCreate().identity();
                runtime.terminal().stdout("NODE_ID=" + identity.nodeId());
                return ExitCodes.OK;
            } catch (Exception failure) {
                runtime.terminal().stderr("ERROR=IDENTITY_FAILED");
                return ExitCodes.LOCAL_CONFIGURATION;
            }
        }
        try {
            runtime.onboarding().showIdentity();
            return ExitCodes.OK;
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }
}
