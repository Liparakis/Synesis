package org.synesis.cli.command.sync;

import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.onboarding.OnboardingFailure;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Adapts {@code synesis host} to the Link onboarding façade.
 */
@Command(name = "host", description = "Create SLO1 and wait for one SLA2 on standard input.", mixinStandardHelpOptions = true)
public final class HostCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--expect-peer", description = "Require this authenticated peer node ID.")
    private String expectedPeer;

    /**
     * Creates a host command with one manually composed runtime.
     *
     * @param runtime manually composed CLI runtime
     */
    public HostCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Runs the bounded host operation. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            try (var host = runtime.onboarding().createInvitation(expectedPeer)) {
                runtime.terminal().stdout("AWAITING_ANSWER=true");
                String answer = runtime.terminal().readLine();
                if (answer == null || answer.isBlank()) {
                    throw new org.synesis.link.onboarding.OnboardingFailure(
                            org.synesis.link.onboarding.OnboardingFailureCode.ANSWER_INVALID,
                            new IllegalArgumentException("answer input is empty"));
                }
                host.importAnswer(answer);
            }
            return ExitCodes.OK;
        } catch (java.io.IOException failure) {
            return FailureMapper.internal(runtime.terminal());
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }
}
