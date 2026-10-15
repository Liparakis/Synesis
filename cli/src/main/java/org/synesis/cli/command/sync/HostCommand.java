package org.synesis.cli.command.sync;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.exit.FailureMapper;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingFailure;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Adapts {@code synesis host} to the Link onboarding façade.
 */
@Command(name = "host", description = "Create an invitation and wait for one session; paste the answer link the other side shows if it cannot connect.", mixinStandardHelpOptions = true)
public final class HostCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--expect-peer", description = "Require this authenticated peer node ID.")
    private String expectedPeer;
    @Option(names = "--open-router-port", description = "Ask your home router (UPnP) to forward a port to this computer while it waits, so a computer on another network can connect directly. Changes a router setting until the session ends.")
    private boolean openRouterPort;

    /**
     * Creates a host command with one manually composed runtime.
     *
     * @param runtime manually composed CLI runtime
     */
    public HostCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Reads answer links pasted while the host waits, until input ends. Each one makes the host
     * send toward the joiner; anything else is ignored.
     */
    private void acceptAnswers(Onboarding.PreparedHost host) {
        try {
            for (String line = runtime.terminal().readLine(); line != null; line = runtime.terminal().readLine()) {
                String answer = line.trim();
                if (answer.isEmpty()) {
                    continue;
                }
                try {
                    host.importAnswer(answer);
                } catch (OnboardingFailure failure) {
                    runtime.terminal().stdout("ANSWER_REJECTED=" + failure.code());
                }
            }
        } catch (IOException inputClosed) {
            // Without input the host still accepts a direct connection.
        }
    }

    /**
     * Runs the bounded host operation. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            try (var host = runtime.onboarding().createCompactInvitation(expectedPeer, openRouterPort, null, _ -> {
            })) {
                runtime.terminal().stdout("NEXT=If the other computer does not connect, paste the answer link it shows here and press Enter.");
                Thread answers = Thread.ofVirtual().start(() -> acceptAnswers(host));
                try {
                    host.awaitConnection();
                } finally {
                    answers.interrupt();
                }
            }
            return ExitCodes.OK;
        } catch (OnboardingFailure failure) {
            return FailureMapper.map(failure, runtime.terminal());
        } catch (RuntimeException failure) {
            return FailureMapper.internal(runtime.terminal());
        }
    }
}
