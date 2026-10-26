package org.synesis.cli.command.coordination;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.ControlPlaneEventHub;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;
import org.synesis.workspace.transport.control.ControlPlaneReadModel;
import org.synesis.workspace.transport.control.LinkRuntimeOwner;
import org.synesis.workspace.transport.control.ProjectSharing;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;

/**
 * The computers of a multi-computer test, composed as {@link CoordinationServerLauncher} composes one: a replication
 * side that can change role, a link owner, a merge driver and a loopback server, driven through the control plane with
 * the runtime command token as {@code synesis link} drives it. {@link ProjectLinkFlowTest} and the scripted scenarios
 * (ADR-0102 item 1) start theirs here, so they share one composition.
 */
final class TestComputers implements AutoCloseable {

    private static final Set<PredictionEventType> MEMBERSHIP_ENTRIES = EnumSet.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED,
            PredictionEventType.JOIN_ANSWERED, PredictionEventType.JOIN_POLICY_SET, PredictionEventType.MEMBER_REMOVED, PredictionEventType.MEMBER_CARD,
            PredictionEventType.OWNER_CHANGED);

    private final HttpClient client = HttpClient.newHttpClient();
    private final ProjectApplicationService service = new ProjectApplicationService();
    private final List<Computer> started = new ArrayList<>();
    private final Map<Path, Computer> runtimes = new HashMap<>();
    private long mergeDelayMillis = 3_000L;
    private long mergeIntervalMillis;

    ProjectApplicationService service() {
        return service;
    }

    /**
     * Makes the computers started from now on merge sooner than the launcher's 3 s delay, and also at an interval, as the
     * launcher's merge driver does every 30 s.
     */
    TestComputers mergeEvery(long delayMillis, long intervalMillis) {
        this.mergeDelayMillis = delayMillis;
        this.mergeIntervalMillis = intervalMillis;
        return this;
    }

    Computer start(ProjectLocation location, KnownProjectRegistry registry) throws Exception {
        return new Computer(location, registry);
    }

    @Override
    public void close() {
        for (int index = started.size() - 1; index >= 0; index--) {
            started.get(index).stop();
        }
    }

    record Response(int status, Map<String, Object> body) {

        String code() {
            return body.get("error") instanceof Map<?, ?> error ? String.valueOf(error.get("code")) : "";
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    @SuppressWarnings("unchecked")
    private static Response parse(HttpResponse<String> response) {
        return new Response(response.statusCode(), (Map<String, Object>) ProviderJson.parse(response.body()));
    }

    /** One computer as the launcher composes it for the product. */
    final class Computer {
        final ProjectLocation location;
        final NodeIdentity node;
        final URI endpoint;
        final String commandToken;
        /** The member links the launcher starts after its server; scenarios of several computers call {@link MemberLinks#tick()} instead of waiting 15 s. */
        final MemberLinks links;
        /** The registry of known projects this computer was started with, so that a restart can start it the same way. */
        final KnownProjectRegistry registry;
        /** The owner of this computer's Link sessions; {@link #splitLink} closes it and {@link #healLink} replaces it. */
        volatile LinkRuntimeOwner owner;
        private final Path coordination;
        private final ReplicationSide side;
        private final ControlPlaneEventHub hub;
        private final LinkRuntimeOwner.MemberAdmission admission;
        private final List<AutoCloseable> mine = new ArrayList<>();
        private volatile boolean stopped;
        private volatile boolean silent;

        Computer(ProjectLocation location, KnownProjectRegistry registry) throws Exception {
            this.location = location;
            this.registry = registry;
            this.node = location.loadOrCreateIdentity();
            this.coordination = location.coordinationDirectory();
            UUID projectId = location.projectId();
            PredictionEventStore store = new PredictionEventStore(coordination, projectId);
            // As the launcher starts: a step-5 settings file moves into the log, and every computer plays the same side.
            SharedSettingsMigration.migrate(coordination, store, node);
            MembershipService membership = new MembershipService(store, node);
            MergeDriver merge = MergeDriver.forProject(location.root(), coordination, projectId, node, mergeDelayMillis);
            if (mergeIntervalMillis > 0) {
                merge.repeatEvery(mergeIntervalMillis);
            }
            LeaderReplication leader = LeaderReplication.shared(coordination, projectId, node.nodeId(), merge::onMemberEntry);
            this.side = new ReplicationSide(coordination, projectId, location.root(), node.nodeId(), leader, merge);
            this.hub = new ControlPlaneEventHub();
            this.admission = new LinkRuntimeOwner.MemberAdmission() {
                @Override
                public void admit(String nodeId, String codeRemoteUrl) throws IOException {
                    try {
                        ProjectSharing.admit(location.root(), coordination, membership, nodeId, codeRemoteUrl);
                    } catch (ProjectSharing.Refusal refused) {
                        throw new IOException(refused.code());
                    }
                }

                @Override
                public void requested(String nodeId) throws IOException {
                    try {
                        membership.share(Optional.empty());
                        membership.requestJoin(nodeId);
                    } catch (GeneralSecurityException unsigned) {
                        throw new IOException(unsigned);
                    }
                }

                @Override
                public void declined(String nodeId) throws IOException {
                    try {
                        membership.answer(nodeId, false);
                    } catch (GeneralSecurityException unsigned) {
                        throw new IOException(unsigned);
                    }
                }
            };
            this.owner = newOwner();
            this.links = new MemberLinks(owner, leader.membership().orElseThrow(), membership, node.nodeId());
            CoordinationService coordinationService = new CoordinationService(store, node);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordinationService, new ProviderApplicationService(), new DoctorService(), new LinkNetworkProjection(owner),
                    registry);
            AtomicReference<CoordinationHttpServer> serverRef = new AtomicReference<>();
            ControlPlaneHttpHandler control = new ControlPlaneHttpHandler(readModel, coordinationService, owner, hub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, registry,
                    new ControlPlaneHttpHandler.ProjectLinkGateway() {
                        @Override
                        public void becomeMember(String leaderNodeId) throws IOException {
                            side.becomeMember(leaderNodeId, serverRef.get());
                        }

                        @Override
                        public Map<String, Object> projectRuntime(Path project) throws IOException {
                            // The installation daemon starts the runtime of a folder when it is not running.
                            Computer runtime = runtimes.get(project);
                            if (runtime == null) {
                                try {
                                    runtime = new Computer(service.require(project), registry);
                                } catch (Exception unavailable) {
                                    throw new IOException(unavailable);
                                }
                            }
                            return Map.of("endpoint", runtime.endpoint.toString(), "commandToken", runtime.commandToken);
                        }
                    });
            CoordinationHttpServer server = new CoordinationHttpServer(coordinationService, new InetSocketAddress("127.0.0.1", 0), null, control);
            server.start();
            serverRef.set(server);
            leader.serve(server);
            side.serveCode(server);
            this.endpoint = URI.create("http://127.0.0.1:" + server.address().getPort());
            this.commandToken = control.runtimeCommandToken();
            mine.addAll(List.of(control, server, side, links));
            started.add(this);
            runtimes.put(location.root(), this);
        }

        private LinkRuntimeOwner newOwner() {
            PeerSession.ApplicationStreamHandler answering = side.handler();
            LinkRuntimeOwner created = CoordinationServerLauncher.linkOwner(new Onboarding(location.linkDirectory(), hub::publish), node, hub, location.identity(), side,
                    (remote, payload) -> silent ? new CompletableFuture<>() : answering.handle(remote, payload), new ReplicationJournal(coordination), coordination, admission);
            mine.add(created);
            return created;
        }

        /**
         * Cuts every Link session of this computer and keeps it from making or admitting another until
         * {@link #healLink}. Its server, merge driver and agents go on, as a computer does when the Link between it and the
         * others fails while the internet stays up (ADR-0100, "The lab").
         */
        void splitLink() {
            owner.close();
        }

        /**
         * Lets this computer dial the hosts it dialed and listen on the ports it hosted again, as a restart of its Link
         * owner does (ADR-0098 step 2), with no new invitation.
         */
        void healLink() {
            owner = newOwner();
        }

        /**
         * Makes the path to the other computers silent until {@link #speak}: every frame that reaches this computer is heard and
         * none is answered, and its Link sessions stay open, as when a firewall drops every packet and Link has not yet given the
         * session up (2026-10-20, s7-run3). Nothing tells this computer's replication side that anything ended.
         */
        void goSilent() {
            silent = true;
        }

        /** Ends {@link #goSilent}. */
        void speak() {
            silent = false;
        }

        /** Whether {@link #stop} was called: the computer is down, as one whose Synesis was stopped. */
        boolean isStopped() {
            return stopped;
        }

        void stop() {
            stopped = true;
            runtimes.remove(location.root(), this);
            for (int index = mine.size() - 1; index >= 0; index--) {
                try {
                    mine.get(index).close();
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
            mine.clear();
        }

        Response post(String command, Map<String, Object> body) throws Exception {
            return parse(client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/commands/" + command)).header("Content-Type", "application/json; charset=utf-8")
                    .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build(), HttpResponse.BodyHandlers.ofString()));
        }

        Response read(String route) throws Exception {
            return parse(client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/" + route)).header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> joinRequests() throws Exception {
            return (List<Map<String, Object>>) ((Map<String, Object>) read("snapshot").body.get("onboarding")).get("joinRequests");
        }

        PredictionEventStore store() throws Exception {
            return new PredictionEventStore(location.coordinationDirectory(), location.projectId());
        }

        ReplicationSettings role() throws Exception {
            return ReplicationSettings.read(location.coordinationDirectory()).orElseThrow();
        }

        /** The membership entries this computer's log holds, own and copied, in local order. */
        List<PredictionEventType> membershipEntries() throws Exception {
            return store().events().stream().map(PredictionEvent::type).filter(MEMBERSHIP_ENTRIES::contains).toList();
        }

        MembershipProjection membership() throws Exception {
            return store().membershipProjection();
        }

        /** Whether this computer's log, with the copies it holds, names the node a member. */
        boolean namesMember(String nodeId) {
            try {
                return membership().members().contains(nodeId);
            } catch (Exception unreadable) {
                return false;
            }
        }
    }
}
