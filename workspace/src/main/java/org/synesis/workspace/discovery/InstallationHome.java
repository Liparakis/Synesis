package org.synesis.workspace.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * The installation's own workspace: a private Synesis project that exists only so the browser UI
 * has a runtime to be served from when the installation knows no project.
 *
 * <p>Every UI is served by one project runtime. An installation whose registry is empty, because
 * nothing was initialized yet or every entry was removed, would otherwise have no way to show the
 * page on which a project is added. The home workspace is not a user project: it is never recorded
 * in the {@link KnownProjectRegistry}, the control plane does not report it as the current project,
 * and deep links are never routed to it.</p>
 */
public final class InstallationHome {

    private static final String DIRECTORY = "ui-home";

    private InstallationHome() {
    }

    /**
     * Returns where the home workspace lives, whether or not it exists yet.
     *
     * @return normalized directory below the Synesis application state root
     */
    public static Path root() {
        return AdministrativeStateLocator.applicationStateRoot().resolve(DIRECTORY).toAbsolutePath().normalize();
    }

    /**
     * Reports whether a path is the home workspace.
     *
     * @param path any project root
     * @return whether it is the home workspace of this installation
     */
    public static boolean is(Path path) {
        return path != null && path.toAbsolutePath().normalize().equals(root());
    }

    /**
     * Creates and initializes the home workspace when it is not there yet.
     *
     * @return the home workspace root
     * @throws IOException when the directory cannot be created or initialized
     */
    public static Path ensure() throws IOException {
        Path root = root();
        Files.createDirectories(root);
        ProjectApplicationService service = new ProjectApplicationService();
        try {
            service.require(root);
        } catch (ProjectApplicationService.ProjectApplicationException notInitialized) {
            try {
                // No provider is configured for it: no agent ever works in the home workspace.
                service.initForRegistration(root, false);
            } catch (ProjectApplicationService.ProjectApplicationException failure) {
                throw new IOException("installation home could not be initialized: " + failure.code(), failure);
            }
        }
        return root;
    }
}
