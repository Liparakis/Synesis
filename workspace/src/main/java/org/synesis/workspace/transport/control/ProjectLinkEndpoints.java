package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.IncompatibleVersionException;
import org.synesis.link.protocol.ProjectInvitation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The commands that make and open a project link, and decide who joins (ADR-0098, "Joining, revised").
 *
 * <p>{@code invite} with {@code project: true} hands out a signed project link and writes nothing; accepting a joiner makes this computer the leader.
 * {@code open-project-link} takes a link and a folder, if the person has chosen one, finds the folder of the project
 * on this computer, and has the runtime of that folder follow the inviter and connect: this runtime when the folder is
 * the one it serves, otherwise the folder's own runtime, reached with its runtime command token, because that runtime
 * owns the project's Link sessions. {@code accept-join} and {@code decline-join} decide a waiting joiner.
 *
 * <p>The refusals of {@link ProjectSharing} become 409 responses with their code, and nothing was written.
 */
final class ProjectLinkEndpoints {

    private static final int MAX_LINK_LENGTH = 4_096;
    private static final int MAX_PATH_LENGTH = 4_096;
    private static final int MAX_NODE_LENGTH = 256;
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLOCK_SKEW = CompactBootstrapInvitation.DEFAULT_CLOCK_SKEW;

    private final ControlPlaneReadModel readModel;
    private final KnownProjectRegistry registry;
    private final ControlPlaneLinkOperations links;
    private final ControlPlaneOnboardingEndpoints onboarding;
    private final ControlPlaneHttpHandler.ProjectLinkGateway gateway;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    ProjectLinkEndpoints(ControlPlaneReadModel readModel, KnownProjectRegistry registry, ControlPlaneLinkOperations links, ControlPlaneOnboardingEndpoints onboarding,
            ControlPlaneHttpHandler.ProjectLinkGateway gateway) {
        this.readModel = readModel;
        this.registry = registry;
        this.links = links;
        this.onboarding = onboarding;
        this.gateway = gateway;
    }

    /**
     * Creates the invitation this computer hands out, and changes nothing else: no role file is written, so a computer
     * that has none keeps working exactly as before until it accepts a joiner (ADR-0098, "Joining, revised").
     *
     * <p>It is a signed project link naming the code remote when the project has an explicit {@code code.remote} or
     * exactly one Git remote; the response says which remote and URL, or why there is none. Since ADR-0098 step 6 a
     * member's invitation is a project link too: the member holds the joiner's session and records its request.
     *
     * @param exchange HTTP exchange
     * @param body     the {@code invite} body
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the role file cannot be read or the invitation cannot be made
     */
    void invite(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        ProjectLocation location = readModel.location();
        Path coordination = coordination();
        ProjectSharing.Leader leader;
        try {
            // Any member invites to the project (ADR-0098 step 6): it holds the joiner's session and records the request.
            leader = ProjectSharing.invitationRemote(location.root(), coordination);
        } catch (ProjectSharing.Refusal refusal) {
            throw refused(refusal);
        } catch (IOException unreadable) {
            throw failure(500, "PROJECT_STORAGE_FAILED", "the code remote of this project could not be read");
        }
        Map<String, Object> response = onboarding.startInvitation(body,
                link -> links.projectInvitationLink(link, location.projectId(), location.createdAt(), leader.url()));
        response.put("kind", "PROJECT");
        response.put("projectId", location.projectId().toString());
        response.put("role", "leader");
        response.put("codeRemoteUrl", leader.url());
        response.put("codeRemoteName", leader.remoteName());
        response.put("codeRemoteReason", leader.reason());
        sendJson(exchange, 201, response);
    }

    /**
     * Describes a project link before anything is done with it, for the person to read before joining.
     *
     * @param exchange HTTP exchange
     * @param body     request body with {@code link}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the link is invalid or has expired
     */
    void describe(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        ProjectInvitation invitation = verified(requiredText(body, "link", MAX_LINK_LENGTH));
        Map<String, Object> response = newResponse();
        response.put("hostNodeId", invitation.hostNodeId());
        response.put("projectId", invitation.projectId().toString());
        response.put("projectName", knownName(invitation));
        response.put("codeRemoteUrl", invitation.codeRemoteUrl().orElse(null));
        response.put("expiresAt", invitation.invitation().expiresAt().toString());
        sendJson(exchange, 200, response);
    }

    private String knownName(ProjectInvitation invitation) {
        ProjectLocation here = readModel.location();
        if (invitation.projectId().equals(here.projectId()) && invitation.createdAt().equals(here.createdAt())) {
            return here.root().getFileName() == null ? null : here.root().getFileName().toString();
        }
        try {
            for (KnownProjectRegistry.ProjectView view : registry().projects()) {
                if (view.projectId().equals(invitation.projectId()) && view.createdAt().equals(invitation.createdAt()) && view.path().getFileName() != null) {
                    return view.path().getFileName().toString();
                }
            }
        } catch (ApiFailure | KnownProjectRegistry.RegistryException unknown) {
            // The name is only a convenience; the project id is shown when it is not known.
        }
        return null;
    }

    /** The folder the role file lives in: the one this runtime serves, which a data override moves. */
    private Path coordination() {
        Path served = gateway.coordinationDirectory();
        return served != null ? served : readModel.location().coordinationDirectory();
    }

    /**
     * Opens a project link on this computer.
     *
     * @param exchange HTTP exchange
     * @param body     request body with {@code link} and an optional {@code folder}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the link is invalid, the folder is not the project, or the join is refused
     */
    void open(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        ProjectInvitation invitation = verified(requiredText(body, "link", MAX_LINK_LENGTH));
        Path chosen = null;
        if (body.get("folder") != null) {
            try {
                chosen = Path.of(requiredText(body, "folder", MAX_PATH_LENGTH));
            } catch (InvalidPathException malformed) {
                throw failure(400, "PROJECT_FOLDER_INVALID", "the folder is not a valid path");
            }
        }
        Optional<Path> folder;
        try {
            folder = ProjectSharing.locate(registry(), new ProjectApplicationService(), invitation, chosen, readModel.location().root());
        } catch (ProjectSharing.Refusal refusal) {
            throw refused(refusal);
        }
        if (folder.isEmpty()) {
            Map<String, Object> response = newResponse();
            response.put("state", "PROJECT_NOT_FOUND");
            response.put("projectId", invitation.projectId().toString());
            response.put("hostNodeId", invitation.hostNodeId());
            response.put("codeRemoteUrl", invitation.codeRemoteUrl().orElse(null));
            sendJson(exchange, 200, response);
            return;
        }
        Map<String, Object> response = sameFolder(folder.get()) ? joinHere(invitation) : joinThere(folder.get(), invitation);
        response.put("folder", folder.get().toString());
        checkCodeRemote(folder.get(), response);
        sendJson(exchange, 200, response);
    }

    /**
     * Follows the inviter of a project link in the project this runtime serves, and connects.
     *
     * @param exchange HTTP exchange
     * @param body     request body with {@code link}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the link is invalid, is another project's, or the join is refused
     */
    void join(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        sendJson(exchange, 200, joinHere(verified(requiredText(body, "link", MAX_LINK_LENGTH))));
    }

    /**
     * Accepts or declines a waiting joiner.
     *
     * @param exchange HTTP exchange
     * @param body     request body with {@code nodeId}
     * @param accept   whether to accept
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  404 when no such request waits, 503 when the member list could not be saved
     */
    void decide(HttpExchange exchange, Map<String, Object> body, boolean accept) throws IOException, ApiFailure {
        String nodeId = requiredText(body, "nodeId", MAX_NODE_LENGTH);
        try {
            if (accept) {
                links.acceptJoin(nodeId);
            } else {
                links.declineJoin(nodeId);
            }
        } catch (ControlPlaneLinkOperations.JoinRequestException undecided) {
            throw switch (undecided.code()) {
                case "MEMBER_NOT_SAVED" -> failure(503, undecided.code(), "the member list could not be saved");
                default -> failure(404, undecided.code(), "no such join request is waiting");
            };
        }
        Map<String, Object> response = newResponse();
        response.put("nodeId", nodeId);
        response.put("state", accept ? "ACCEPTED" : "DECLINED");
        sendJson(exchange, 200, response);
    }

    /**
     * Removes a member from the project, a person's action on the owner's computer (ADR-0103 stage 3). Removing ends
     * the member's open work on every computer, so it also requires {@code confirmed: true}: the page asks first, and
     * no other caller removes a computer by mistake. The owner rule is the log's, as for {@code project remove-member}.
     *
     * @param exchange HTTP exchange
     * @param body     request body with {@code nodeId} and {@code confirmed}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  400 without the confirmation, 409 when the member cannot be removed, 500 when the log failed
     */
    void removeMember(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String nodeId = requiredText(body, "nodeId", MAX_NODE_LENGTH);
        if (!Boolean.TRUE.equals(body.get("confirmed"))) {
            throw failure(400, "CONFIRMATION_REQUIRED", "removing a member ends its open work on every computer");
        }
        try {
            ProjectSharing.removeMember(readModel.location(), coordination(), nodeId);
        } catch (ProjectSharing.Refusal refusal) {
            throw failure(refusal.code().equals("INVALID_NODE_ID") ? 400 : 409, refusal.code(), "the member cannot be removed: " + refusal.code());
        } catch (CoordinationException refused) {
            throw failure(409, refused.code(), "the project's log refused the removal: " + refused.code());
        } catch (IOException | GeneralSecurityException failed) {
            throw failure(500, "PROJECT_STORAGE_FAILED", "the member could not be removed");
        }
        Map<String, Object> response = newResponse();
        response.put("nodeId", nodeId);
        response.put("state", "REMOVED");
        sendJson(exchange, 200, response);
    }

    private Map<String, Object> joinHere(ProjectInvitation invitation) throws ApiFailure {
        ProjectLocation location = readModel.location();
        if (!invitation.projectId().equals(location.projectId()) || !invitation.createdAt().equals(location.createdAt())) {
            throw failure(409, "PROJECT_IDENTITY_MISMATCH", "this folder is not the project of the invitation");
        }
        String[] remote = new String[1];
        // The connection is prepared first, so that capacity or an unusable transport refuse before the role file or
        // the Git remote is written; the role is written inside the lock that also switches the running side.
        Map<String, Object> response = onboarding.startJoin(invitation.invitation().shareLink(), () -> {
            try {
                remote[0] = ProjectSharing.follow(location.root(), coordination(), invitation, links.localNodeId(), () -> gateway.becomeMember(invitation.hostNodeId()));
            } catch (ProjectSharing.Refusal refusal) {
                throw refused(refusal);
            } catch (IOException failed) {
                throw "FOLLOWING_ANOTHER_LEADER".equals(failed.getMessage())
                        ? failure(409, "FOLLOWING_ANOTHER_LEADER", "this computer already follows another leader")
                        : failure(500, "PROJECT_STORAGE_FAILED", "the role of this computer could not be saved");
            }
        });
        response.put("projectId", location.projectId().toString());
        response.put("role", "member");
        response.put("leaderNodeId", invitation.hostNodeId());
        response.put("codeRemote", remote[0]);
        return response;
    }

    private Map<String, Object> joinThere(Path folder, ProjectInvitation invitation) throws ApiFailure {
        try {
            Map<?, ?> runtime = gateway.projectRuntime(folder);
            if (!(runtime.get("endpoint") instanceof String endpoint) || !(runtime.get("commandToken") instanceof String token)) {
                throw new IOException("project_runtime_unavailable");
            }
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(endpoint).resolve(ControlPlaneHttpHandler.API_PREFIX + "/commands/join-project-link"))
                    .timeout(REMOTE_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(Map.of("link", invitation.shareLink())))).build(), HttpResponse.BodyHandlers.ofString());
            if (!(ProviderJson.parse(answer.body()) instanceof Map<?, ?> raw)) {
                throw new IOException("runtime response is malformed");
            }
            Map<String, Object> parsed = ProviderJson.stringKeyedObject(raw);
            if (answer.statusCode() < 200 || answer.statusCode() >= 300) {
                String code = parsed.get("error") instanceof Map<?, ?> error && error.get("code") instanceof String text ? text : "PROJECT_RUNTIME_REJECTED";
                throw failure(answer.statusCode() == 409 ? 409 : 400, code, "the project's runtime refused the join");
            }
            return new LinkedHashMap<>(parsed);
        } catch (IOException | RuntimeException unavailable) {
            throw failure(503, "PROJECT_RUNTIME_UNAVAILABLE", "the runtime of the project could not be reached");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(503, "PROJECT_RUNTIME_UNAVAILABLE", "the runtime of the project could not be reached");
        }
    }

    /**
     * Says whether this computer can fetch from the code remote without a prompt. A member fetches the leader's head
     * before every lane, so a sign-in Git has not stored stops every agent here. It is reported and the join is kept:
     * the person can sign in afterwards, and the invitation is single use. It runs after the join, outside the
     * folder runtime's request, so a slow remote cannot time that request out.
     */
    private static void checkCodeRemote(Path folder, Map<String, Object> response) {
        if (!(response.get("codeRemote") instanceof String remote)) {
            return;
        }
        try {
            CodeRemote.check(folder, remote);
            response.put("codeRemoteCheck", "REACHABLE");
        } catch (CodeRemote.Refused refused) {
            response.put("codeRemoteCheck", refused.code());
            response.put("codeRemoteDetail", refused.detail());
        }
    }

    private boolean sameFolder(Path folder) {
        try {
            return Files.isSameFile(folder, readModel.location().root());
        } catch (IOException unreadable) {
            return false;
        }
    }

    private KnownProjectRegistry registry() throws ApiFailure {
        if (registry == null) {
            throw failure(503, "PROJECT_REGISTRY_UNAVAILABLE", "project registry is unavailable");
        }
        return registry;
    }

    private static ProjectInvitation verified(String link) throws ApiFailure {
        try {
            ProjectInvitation invitation = ProjectInvitation.fromShareLink(link.strip());
            if (!invitation.verifyAt(Instant.now(), CLOCK_SKEW)) {
                throw failure(400, "INVITE_INVALID", "the project link is invalid or has expired");
            }
            return invitation;
        } catch (IncompatibleVersionException differentVersion) {
            throw failure(400, "INCOMPATIBLE_VERSION", differentVersion.mismatch().message());
        } catch (IOException | IllegalArgumentException | GeneralSecurityException invalid) {
            throw failure(400, "INVITE_INVALID", "the project link is invalid or has expired");
        }
    }

    private static ApiFailure refused(ProjectSharing.Refusal refusal) {
        int status = switch (refusal.code()) {
            case "PROJECT_FOLDER_INVALID", "CODE_REMOTE_NOT_ADDED", "NOT_FOUND", "PROJECT_INVALID" -> 400;
            case "REPLICATION_ROLE_INVALID" -> 500;
            default -> 409;
        };
        return failure(status, refusal.code(), "the project link cannot be used here: " + refusal.code());
    }
}
