package org.synesis.cli.command.coordination;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.coordination.domain.CoordinationCommand;
import org.synesis.coordination.transport.http.CoordinationHttpClient;
import org.synesis.coordination.domain.PredictionEvent;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Shared project/profile resolution for public coordination commands.
 */
public final class CoordinationCliSupport {

    private CoordinationCliSupport() {
    }

    /** Resolves the selected project for a coordination command.
     * @param runtime CLI runtime
     * @param project requested project path, or {@code null}
     * @return resolved project location
     * @throws ProjectApplicationService.ProjectApplicationException if the project is invalid
     */
    public static ProjectApplicationService.ProjectLocation project(CliRuntime runtime, Path project)
            throws ProjectApplicationService.ProjectApplicationException {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.projectService()
                .require(project == null ? Path.of(".") : project);
    }

    /** Resolves the coordination data directory.
     * @param location resolved project location
     * @param override optional data-directory override
     * @return normalized coordination data directory
     */
    public static Path data(ProjectApplicationService.ProjectLocation location, Path override) {
        return (override == null ? location.synesisDirectory()
                                   .resolve("shared")
                                   .resolve("coordination") : override)
                .toAbsolutePath()
                .normalize();
    }

    /** Resolves the coordination identity directory.
     * @param location resolved project location
     * @param override optional identity-directory override
     * @return normalized identity directory
     */
    public static Path identity(ProjectApplicationService.ProjectLocation location, Path override) {
        return (override == null ? location.profile() : override).toAbsolutePath()
                .normalize();
    }

    /** Loads or creates the node identity for a coordination command.
     * @param profile identity profile directory
     * @return loaded node identity
     * @throws Exception if identity loading fails
     */
    public static NodeIdentity loadIdentity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.toAbsolutePath()
                .normalize()
                .resolve("link")).loadOrCreate()
                .identity();
    }

    /** Submits one coordination command.
     * @param endpoint coordination HTTP endpoint
     * @param command command to submit
     * @return appended prediction event
     * @throws Exception if submission fails
     */
    public static PredictionEvent submit(URI endpoint, CoordinationCommand command) throws Exception {
        return new CoordinationHttpClient(endpoint).submit(command);
    }

    /** Replays coordination events after a sequence.
     * @param endpoint coordination HTTP endpoint
     * @param after exclusive sequence cursor
     * @return events after the cursor
     * @throws Exception if replay fails
     */
    @SuppressWarnings("SameParameterValue")
    public static List<PredictionEvent> replay(URI endpoint, long after) throws Exception {
        return new CoordinationHttpClient(endpoint).replayAfter(after);
    }

    /** Validates a coordination endpoint.
     * @param value endpoint to validate
     * @return the non-null endpoint
     */
    public static URI endpoint(URI value) {
        return Objects.requireNonNull(value, "endpoint");
    }
}
