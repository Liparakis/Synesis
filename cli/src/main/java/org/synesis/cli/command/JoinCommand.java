package org.synesis.cli.command;

import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.transport.OnboardingFailure;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Adapts {@code synesis join <link>} to the Link façade.
 */
@Command(name = "join", description = "Join one signed onboarding invitation.", mixinStandardHelpOptions = true)
public final class JoinCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Parameters(index = "0", description = "Exact signed invitation link.")
    private String link;

    /**
     * Creates a join command with one manually composed runtime.
     *
     * @param runtime manually composed CLI runtime
     */
    public JoinCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Runs the bounded join operation. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            runtime.onboarding()
                    .join(link);
            return ExitCodes.OK;
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }
}
