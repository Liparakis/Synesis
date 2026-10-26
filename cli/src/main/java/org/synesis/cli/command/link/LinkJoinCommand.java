package org.synesis.cli.command.link;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.IncompatibleVersionException;
import org.synesis.link.protocol.ProjectInvitation;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.transport.control.ProjectSharing;
import org.synesis.workspace.discovery.KnownProjectRegistry;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Joins an invitation from the project's runtime, so that the connection stays with it.
 *
 * <p>A project link ({@code synesis://project/SLP1-...}) names the project by identity, so {@code --project} is
 * optional: the project's folder is found in the known-project registry. With {@code --project} the folder is the one
 * the person has, which must hold the same project. With {@code --into} it is an empty or new folder, which gets the
 * project over Link once the owner accepts this computer (ADR-0098 step 3). The runtime of that folder follows the
 * inviter and connects.</p>
 */
@Command(name = "join", description = "Join an invitation from the other computer.", mixinStandardHelpOptions = true)
public final class LinkJoinCommand extends LinkOperationCommand {

    @Parameters(index = "0", arity = "1", description = "The synesis://project/... link of 'synesis link invite', or a synesis://join/... link.")
    private String invitation;
    @Option(names = "--into", description = "An empty or new folder to fetch the project into over Link, when it is not on this computer yet.")
    private String into;
    private final KnownProjectRegistry registry;
    private Path folder;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkJoinCommand(CliRuntime runtime) {
        super(runtime);
        this.registry = new KnownProjectRegistry();
    }

    LinkJoinCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis) {
        this(runtime, source, pollMillis, new KnownProjectRegistry());
    }

    LinkJoinCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis, KnownProjectRegistry registry) {
        super(runtime, source, pollMillis);
        this.registry = registry;
    }

    @Override
    Path projectRoot() throws ProjectApplicationException, IOException, RuntimeControl.Rejected {
        if (!ProjectInvitation.isShareLink(invitation)) {
            return super.projectRoot();
        }
        ProjectInvitation parsed = verified();
        Path chosen = into != null ? Path.of(into) : projectOption() == null ? null : Path.of(projectOption());
        if (into != null && !emptyOrNew(chosen)) {
            throw new RuntimeControl.Rejected("PROJECT_FOLDER_NOT_EMPTY");
        }
        try {
            Optional<Path> found = ProjectSharing.locate(registry, runtime.projectService(), parsed, chosen, null);
            if (found.isEmpty()) {
                runtime.terminal().stdout("PROJECT_ID=" + parsed.projectId());
                runtime.terminal().stdout("CODE_REMOTE_URL=" + parsed.codeRemoteUrl().orElse(""));
                runtime.terminal().stdout("NEXT=join again with --into <an empty folder> to fetch the project over Link");
                throw new RuntimeControl.Rejected("PROJECT_NOT_ON_THIS_COMPUTER");
            }
            folder = found.get();
            return folder;
        } catch (ProjectSharing.Refusal refusal) {
            throw new RuntimeControl.Rejected(refusal.code());
        }
    }

    private static boolean emptyOrNew(Path folder) throws IOException {
        if (!java.nio.file.Files.exists(folder)) {
            return true;
        }
        if (!java.nio.file.Files.isDirectory(folder)) {
            return false;
        }
        try (var children = java.nio.file.Files.list(folder)) {
            return children.findAny().isEmpty();
        }
    }

    private ProjectInvitation verified() throws RuntimeControl.Rejected {
        try {
            ProjectInvitation parsed = ProjectInvitation.fromShareLink(invitation.strip());
            if (!parsed.verifyAt(Instant.now(), CompactBootstrapInvitation.DEFAULT_CLOCK_SKEW)) {
                throw new RuntimeControl.Rejected("INVITE_INVALID");
            }
            return parsed;
        } catch (IncompatibleVersionException differentVersion) {
            throw new RuntimeControl.Rejected("INCOMPATIBLE_VERSION", differentVersion.mismatch().message());
        } catch (IOException | IllegalArgumentException | GeneralSecurityException invalid) {
            throw new RuntimeControl.Rejected("INVITE_INVALID");
        }
    }

    @Override
    String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        boolean project = ProjectInvitation.isShareLink(invitation);
        Map<String, Object> started = project ? control.command("join-project-link", Map.of("link", invitation.strip()))
                : control.command("join", Map.of("inviteUri", invitation));
        runtime.terminal().stdout("OPERATION_ID=" + started.get("operationId"));
        runtime.terminal().stdout("HOST_NODE_ID=" + started.get("peerIdentity"));
        runtime.terminal().stdout("ANSWER_LINK=" + started.get("answerUri"));
        runtime.terminal().stdout("EXPIRES_AT=" + started.get("expiresAt"));
        if (project) {
            runtime.terminal().stdout("PROJECT_FOLDER=" + folder);
            runtime.terminal().stdout("NEXT=the owner of the project accepts this computer with 'synesis link accept'");
            if (into != null) {
                runtime.terminal().stdout("NEXT=the project's code then arrives in the folder over Link");
            }
            // Reported, not refused: the person can sign in afterwards, and the invitation is single use.
            if (started.get("codeRemote") instanceof String remote) {
                try {
                    CodeRemote.check(folder, remote);
                    runtime.terminal().stdout("CODE_REMOTE_CHECK=REACHABLE");
                } catch (CodeRemote.Refused refused) {
                    runtime.terminal().stdout("CODE_REMOTE_CHECK=" + refused.code());
                    runtime.terminal().stdout("CODE_REMOTE_DETAIL=" + refused.detail());
                }
            }
        }
        return String.valueOf(started.get("operationId"));
    }
}
