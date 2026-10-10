package org.synesis.cli.command.coordination;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.transport.http.CoordinationHttpClient;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Shared project/profile resolution for public coordination commands.
 */
public final class CoordinationCliSupport {

    private CoordinationCliSupport() {
    }

    /**
     * Resolves the selected project for a coordination command.
     *
     * @param runtime CLI runtime
     * @param project requested project path, or {@code null}
     * @return resolved project location
     * @throws ProjectApplicationService.ProjectApplicationException if the project is invalid
     * @throws IOException                                           if project metadata or private repository state cannot be read or updated
     */
    public static ProjectLocation project(CliRuntime runtime, Path project) throws ProjectApplicationException, IOException {
        Objects.requireNonNull(runtime, "runtime");
        ProjectLocation location = runtime.projectService().require(project == null ? Path.of(".") : project);
        try {
            new KnownProjectRegistry().observe(location);
        } catch (KnownProjectRegistry.RegistryException discoveryFailure) {
            runtime.terminal().stderr("PROJECT_DISCOVERY_ERROR=" + discoveryFailure.code());
        }
        return location;
    }

    /**
     * Resolves the coordination data directory.
     *
     * @param location resolved project location
     * @param override optional data-directory override
     * @return normalized coordination data directory
     */
    public static Path data(ProjectLocation location, Path override) {
        return (override == null ? location.synesisDirectory().resolve("shared").resolve("coordination") : override).toAbsolutePath().normalize();
    }

    /**
     * Resolves the coordination identity directory.
     *
     * @param location resolved project location
     * @param override optional identity-directory override
     * @return normalized identity directory
     */
    public static Path identity(ProjectLocation location, Path override) {
        return (override == null ? location.profile() : override).toAbsolutePath().normalize();
    }

    /**
     * Loads or creates the node identity for a coordination command.
     *
     * @param profile identity profile directory
     * @return loaded node identity
     * @throws Exception if identity loading fails
     */
    public static NodeIdentity loadIdentity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.toAbsolutePath().normalize().resolve("link")).loadOrCreate().identity();
    }

    /**
     * The resolved project and signing identity behind one signed coordination command.
     *
     * @param location resolved project location
     * @param identity signing node identity
     */
    public record Signer(ProjectLocation location, NodeIdentity identity) {

        /**
         * Resolves the project and loads the identity, in that order.
         *
         * @param runtime CLI runtime
         * @param project requested project path, or {@code null}
         * @param profile identity profile directory
         * @return signer for the project
         * @throws Exception if the project or identity cannot be resolved
         */
        public static Signer resolve(CliRuntime runtime, Path project, Path profile) throws Exception {
            var location = project(runtime, project);
            return new Signer(location, loadIdentity(profile));
        }

        /**
         * Signs and submits a command that names its logical supervisor and worker.
         *
         * @param endpoint   coordination HTTP endpoint
         * @param targetId   task or prediction the command targets
         * @param type       event type
         * @param supervisor logical supervisor ID
         * @param worker     logical worker ID
         * @param payload    command payload
         * @return appended prediction event
         * @throws Exception if signing or submission fails
         */
        public PredictionEvent submit(URI endpoint, UUID targetId, PredictionEventType type, String supervisor, String worker, byte[] payload) throws Exception {
            return new CoordinationHttpClient(endpoint).submit(CoordinationCommand.createAs(UUID.randomUUID(), location.projectId(), targetId, type, identity.nodeId(), supervisor, worker, payload, identity));
        }

        /**
         * Signs and submits a command that carries only the node identity.
         *
         * @param endpoint coordination HTTP endpoint
         * @param targetId task or prediction the command targets
         * @param type     event type
         * @param payload  command payload
         * @return appended prediction event
         * @throws Exception if signing or submission fails
         */
        public PredictionEvent submit(URI endpoint, UUID targetId, PredictionEventType type, byte[] payload) throws Exception {
            return new CoordinationHttpClient(endpoint).submit(CoordinationCommand.create(UUID.randomUUID(), location.projectId(), targetId, type, identity.nodeId(), payload, identity));
        }
    }

    /**
     * Reports a failed coordination command and returns the local-configuration exit code.
     *
     * @param runtime CLI runtime
     * @param label   error label printed before {@code =}
     * @param failure the failure to report
     * @return local-configuration exit code
     */
    public static int fail(CliRuntime runtime, String label, Exception failure) {
        runtime.terminal().stderr(label + "=" + failure.getMessage());
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    /**
     * Decodes the command payload carried by a replayed event.
     *
     * @param event replayed event
     * @return the signed command's payload
     * @throws IOException if the event payload is not a valid command
     */
    public static byte[] commandPayload(PredictionEvent event) throws IOException {
        return CoordinationCommand.decode(event.payload()).payload();
    }

    /**
     * Replays every coordination event from the start of the log.
     *
     * @param endpoint coordination HTTP endpoint
     * @return all events
     * @throws Exception if replay fails
     */
    public static List<PredictionEvent> replay(URI endpoint) throws Exception {
        return new CoordinationHttpClient(endpoint).replayAfter(0);
    }
}
