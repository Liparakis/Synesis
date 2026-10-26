package org.synesis.workspace.application.project.execution.sandbox;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses this computer's sandbox. The one place that maps an operating system to its platform class.
 *
 * @since 1.0
 */
public final class Sandboxes {

    /**
     * System property that only the build's test tasks set, so that the many tests that run ordinary commands in
     * temporary folders do not need a sandbox on every developer computer. It is a JVM option, which a command
     * running inside a sandbox cannot set for the server that started it; it is not an environment variable, a
     * flag or a file the project can carry.
     */
    public static final String UNCONFINED_FOR_TESTS_PROPERTY = "synesis.sandbox.unconfinedForTests";

    private static Sandbox platform;

    private Sandboxes() {
    }

    /**
     * Returns the sandbox of this computer, created once per process so its probe result is cached.
     *
     * @return the platform sandbox, which may report itself unavailable
     */
    public static synchronized Sandbox platform() {
        if (platform == null) {
            platform = create(System.getProperty("os.name", "").toLowerCase(Locale.ROOT), Boolean.getBoolean(UNCONFINED_FOR_TESTS_PROPERTY));
        }
        return platform;
    }

    static Sandbox create(String operatingSystem, boolean unconfinedForTests) {
        if (unconfinedForTests) {
            return new UnconfinedForTests();
        }
        if (operatingSystem.contains("linux")) {
            return new LinuxBubblewrapSandbox();
        }
        return new Missing(operatingSystem);
    }

    /**
     * The sandbox of a platform whose sandbox is not built yet: it reports itself unavailable, so a command is
     * refused rather than run unconfined.
     */
    private record Missing(String operatingSystem) implements Sandbox {

        @Override
        public String name() {
            return "none";
        }

        @Override
        public SandboxAvailability probe() {
            return SandboxAvailability.unavailable("no sandbox is available for " + operatingSystem + " yet", "run 'synesis doctor'");
        }

        @Override
        public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
            throw new SandboxSetupException(probe().reason());
        }
    }

    /**
     * Runs the command as it is. Only {@link #UNCONFINED_FOR_TESTS_PROPERTY} selects it.
     */
    private static final class UnconfinedForTests implements Sandbox {

        @Override
        public String name() {
            return "unconfined-for-tests";
        }

        @Override
        public SandboxAvailability probe() {
            return SandboxAvailability.ready();
        }

        @Override
        public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) {
            return new WrappedCommand(argv, environment);
        }
    }
}
