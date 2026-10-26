package org.synesis.cli.daemon;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The project's runtime, the coordination server that holds its Link sessions, as the installation daemon owns it.
 */
@FunctionalInterface
public interface ProjectRuntime {

    /**
     * The installation daemon's runtime of a project.
     */
    ProjectRuntime DAEMON = project -> DaemonClient.request("PROJECT_RUNTIME", project, null, false);

    /**
     * Returns once the runtime runs, starting the daemon and the runtime when they do not.
     *
     * @param project project directory
     * @throws IOException when the daemon or the runtime cannot be started
     */
    void ensure(Path project) throws IOException;
}
