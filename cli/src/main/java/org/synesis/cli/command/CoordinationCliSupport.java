package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.coordination.CoordinationCommand;
import org.synesis.coordination.CoordinationHttpClient;
import org.synesis.coordination.PredictionEvent;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Shared project/profile resolution for public coordination commands.
 */
final class CoordinationCliSupport {

    private CoordinationCliSupport() {
    }

    static ProjectApplicationService.ProjectLocation project(CliRuntime runtime, Path project)
            throws ProjectApplicationService.ProjectApplicationException {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.projectService()
                .require(project == null ? Path.of(".") : project);
    }

    static Path data(ProjectApplicationService.ProjectLocation location, Path override) {
        return (override == null ? location.synesisDirectory()
                                   .resolve("shared")
                                   .resolve("coordination") : override)
                .toAbsolutePath()
                .normalize();
    }

    static Path identity(ProjectApplicationService.ProjectLocation location, Path override) {
        return (override == null ? location.profile() : override).toAbsolutePath()
                .normalize();
    }

    static NodeIdentity loadIdentity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.toAbsolutePath()
                .normalize()
                .resolve("link")).loadOrCreate()
                .identity();
    }

    static PredictionEvent submit(URI endpoint, CoordinationCommand command) throws Exception {
        return new CoordinationHttpClient(endpoint).submit(command);
    }

    static List<PredictionEvent> replay(URI endpoint, long after) throws Exception {
        return new CoordinationHttpClient(endpoint).replayAfter(after);
    }

    static URI endpoint(URI value) {
        return Objects.requireNonNull(value, "endpoint");
    }
}
