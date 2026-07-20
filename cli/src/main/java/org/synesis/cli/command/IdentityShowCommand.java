package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.transport.OnboardingFailure;

/** Adapts {@code synesis identity show} to the Link façade. */
@Command(name = "show", description = "Load or create and display the local node ID.", mixinStandardHelpOptions = true)
public final class IdentityShowCommand implements Callable<Integer> {
    private final CliRuntime runtime;

    /**
     * Creates an identity command with one manually composed runtime.
     * @param runtime manually composed CLI runtime
     */
    public IdentityShowCommand(CliRuntime runtime) { this.runtime = runtime; }

    /** Runs identity bootstrap. @return stable exit code */
    @Override
    public Integer call() {
        try {
            runtime.onboarding().showIdentity();
            return ExitCodes.OK;
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(failure, runtime.terminal());
        }
    }
}
