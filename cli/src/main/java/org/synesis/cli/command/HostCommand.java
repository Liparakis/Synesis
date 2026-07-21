package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.transport.OnboardingFailure;

/**
 * Adapts {@code synesis host} to the Link onboarding façade.
 */
@Command(name = "host", description = "Host one signed onboarding invitation.", mixinStandardHelpOptions = true)
public final class HostCommand implements Callable<Integer> {
    @Option(names = "--expect-peer", description = "Require this authenticated peer node ID.")
    private String expectedPeer;
    private final CliRuntime runtime;

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
            runtime.onboarding().host(expectedPeer);
            return ExitCodes.OK;
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(failure, runtime.terminal());
        }
    }
}
