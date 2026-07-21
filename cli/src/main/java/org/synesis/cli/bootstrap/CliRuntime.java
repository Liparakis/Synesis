package org.synesis.cli.bootstrap;

import java.nio.file.Path;

import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.cli.terminal.Terminal;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.transport.Onboarding;

/**
 * Manual composition point for one CLI invocation.
 *
 * @since 1.0
 */
public final class CliRuntime {
    private final Onboarding onboarding;
    private final Terminal terminal;
    private final ReadinessInspector readinessInspector;

    /**
     * Creates an injectable runtime for command tests and process execution.
     *
     * @param onboarding         Link façade
     * @param terminal           terminal boundary
     * @param readinessInspector local readiness inspector
     */
    public CliRuntime(Onboarding onboarding, Terminal terminal, ReadinessInspector readinessInspector) {
        this.onboarding = onboarding;
        this.terminal = terminal;
        this.readinessInspector = readinessInspector;
    }

    /**
     * Creates the production runtime using the default profile.
     *
     * @return manually composed runtime
     */
    public static CliRuntime defaults() {
        return defaults(new ConsoleTerminal());
    }

    /**
     * Creates a runtime with a supplied terminal and default Link profile.
     *
     * @param terminal terminal boundary
     * @return manually composed runtime
     */
    public static CliRuntime defaults(Terminal terminal) {
        StatusRenderer renderer = new StatusRenderer(terminal);
        Path profile = IdentityBootstrap.defaultDirectory();
        return new CliRuntime(new Onboarding(profile, renderer), terminal, new ReadinessInspector(profile));
    }

    /**
     * Returns the Link-owned onboarding façade.
     *
     * @return onboarding façade
     */
    public Onboarding onboarding() {
        return onboarding;
    }

    /**
     * Returns the terminal boundary.
     *
     * @return terminal
     */
    public Terminal terminal() {
        return terminal;
    }

    /**
     * Returns the read-only readiness inspector.
     *
     * @return readiness inspector
     */
    public ReadinessInspector readinessInspector() {
        return readinessInspector;
    }
}
