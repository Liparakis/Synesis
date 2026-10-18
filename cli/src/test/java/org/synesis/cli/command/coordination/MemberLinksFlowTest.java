package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.SharedSettingsMigration;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationCard;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import org.synesis.coordination.transport.http.CoordinationHttpServer;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.transport.control.ControlPlaneEventHub;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler;
import org.synesis.workspace.transport.control.ControlPlaneReadModel;
import org.synesis.workspace.transport.control.LinkRuntimeOwner;
import org.synesis.workspace.transport.control.ProjectSharing;
import org.synesis.workspace.transport.control.network.LinkNetworkProjection;

/**
 * Members link each other directly through their cards (ADR-0098 step 6, "Links"; designer items 13, 15 and 19): three
 * computers composed as the launcher composes them, over loopback sessions. The owner A accepts B and C by project
 * links; {@link MemberLinks#tick()} is called here instead of waiting 15 s.
 */
@Timeout(400)
final class MemberLinksFlowTest {

    private static final long WAIT_SECONDS = 90;

    @TempDir
    Path temp;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ProjectApplicationService service = new ProjectApplicationService();
    private final List<Computer> started = new ArrayList<>();
    private final Map<Path, Computer> runtimes = new HashMap<>();
    private KnownProjectRegistry registry;
    private ProjectLocation ownerProject;

    @BeforeEach
    void projects() throws Exception {
        Path ownerRoot = Files.createDirectories(temp.resolve("owner"));
        ownerProject = service.initForRegistration(ownerRoot, false).location();
        GitProcessRunner.run(ownerRoot, "remote", "add", "origin", "https://example.test/org/project.git");
        registry = new KnownProjectRegistry(temp.resolve("registry/known.json"));
    }

    @AfterEach
    void stop() {
        for (int index = started.size() - 1; index >= 0; index--) {
            started.get(index).stop();
        }
    }

    /** One computer as the launcher composes it, with the member links the launcher starts after its server. */
    private final class Computer {
        final ProjectLocation location;
        final NodeIdentity node;
        final URI endpoint;
        final String commandToken;
        final LinkRuntimeOwner owner;
        final MemberLinks links;
        final Path coordination;
        private final List<AutoCloseable> mine = new ArrayList<>();
        private volatile boolean stopped;

        Computer(ProjectLocation location) throws Exception {
            this.location = location;
            this.node = location.loadOrCreateIdentity();
            this.coordination = location.coordinationDirectory();
            UUID projectId = location.projectId();
            PredictionEventStore store = new PredictionEventStore(coordination, projectId);
            SharedSettingsMigration.migrate(coordination, store, node);
            MembershipService membership = new MembershipService(store, node);
            MergeDriver merge = MergeDriver.forProject(location.root(), coordination, projectId, node, 3_000L);
            LeaderReplication leader = LeaderReplication.shared(coordination, projectId, node.nodeId(), merge::onMemberEntry);
            ReplicationSide side = new ReplicationSide(coordination, projectId, location.root(), node.nodeId(), leader, merge);
            ControlPlaneEventHub hub = new ControlPlaneEventHub();
            this.owner = CoordinationServerLauncher.linkOwner(new Onboarding(location.linkDirectory(), hub::publish), node, hub, location.identity(), side,
                    new ReplicationJournal(coordination), coordination, new LinkRuntimeOwner.MemberAdmission() {
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
                    });
            this.links = new MemberLinks(owner, leader.membership().orElseThrow(), membership, node.nodeId());
            CoordinationService coordinationService = new CoordinationService(store, node);
            ControlPlaneReadModel readModel = new ControlPlaneReadModel(location, coordinationService, new ProviderApplicationService(), new DoctorService(),
                    new LinkNetworkProjection(owner), registry);
            AtomicReference<CoordinationHttpServer> serverRef = new AtomicReference<>();
            ControlPlaneHttpHandler control = new ControlPlaneHttpHandler(readModel, coordinationService, owner, hub, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE, registry,
                    new ControlPlaneHttpHandler.ProjectLinkGateway() {
                        @Override
                        public void becomeMember(String leaderNodeId) throws IOException {
                            side.becomeMember(leaderNodeId, serverRef.get());
                        }

                        @Override
                        public Map<String, Object> projectRuntime(Path project) throws IOException {
                            Computer runtime = runtimes.get(project);
                            if (runtime == null) {
                                throw new IOException("no runtime for " + project);
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
            mine.addAll(List.of(control, links, owner, server, side));
            started.add(this);
            runtimes.put(location.root(), this);
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

        @SuppressWarnings("unchecked")
        Map<String, Object> post(String command, Map<String, Object> body) throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/commands/" + command)).header("Content-Type", "application/json; charset=utf-8")
                    .header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).POST(HttpRequest.BodyPublishers.ofString(ProviderJson.write(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() / 100 == 2, command + " " + response.statusCode() + " " + response.body());
            return (Map<String, Object>) ProviderJson.parse(response.body());
        }

        @SuppressWarnings("unchecked")
        int joinRequests() {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint.resolve("/api/v1/snapshot")).header(ControlPlaneHttpHandler.RUNTIME_COMMAND_HEADER, commandToken).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                Map<String, Object> body = (Map<String, Object>) ProviderJson.parse(response.body());
                return ((List<?>) ((Map<String, Object>) body.get("onboarding")).get("joinRequests")).size();
            } catch (Exception failure) {
                return -1;
            }
        }

        PredictionEventStore store() throws Exception {
            return new PredictionEventStore(coordination, location.projectId());
        }

        boolean namesMember(String nodeId) {
            try {
                return store().membershipProjection().members().contains(nodeId);
            } catch (Exception unreadable) {
                return false;
            }
        }

        Map<String, String> cards() {
            try {
                return store().membershipProjection().cards();
            } catch (Exception unreadable) {
                return Map.of();
            }
        }

        Set<UUID> entryIds() {
            try {
                Set<UUID> ids = new HashSet<>();
                store().events().forEach(event -> ids.add(event.eventId()));
                return ids;
            } catch (Exception unreadable) {
                return Set.of();
            }
        }

        List<String> journal() {
            return ReplicationJournal.read(coordination);
        }

        long cardEntries() throws Exception {
            return store().events().stream().filter(event -> event.type() == PredictionEventType.MEMBER_CARD && event.actorNodeId().equals(node.nodeId())).count();
        }

        boolean linkedTo(Computer other) {
            return !stopped && owner.connectedTo(other.node.nodeId());
        }
    }

    private Computer start(ProjectLocation location) throws Exception {
        return new Computer(location);
    }

    private ProjectLocation clone(String name) throws Exception {
        Path root = temp.resolve(name);
        GitProcessRunner.run(temp, "clone", ownerProject.root().toString(), root.toString());
        ProjectLocation location = service.initReplica(root, ownerProject.identity(), false).location();
        registry.register(location.root());
        return location;
    }

    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> detail) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition not reached: " + detail.get());
    }

    private static void tickAll(Computer... computers) {
        for (Computer computer : computers) {
            computer.links.tick();
        }
    }

    /** Waits for the condition, looking at the members' links (as the 15 s timer would) between checks. */
    private static void awaitTicking(BooleanSupplier condition, java.util.function.Supplier<String> detail, Computer... computers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        long nextTick = 0;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (System.nanoTime() >= nextTick) {
                tickAll(computers);
                nextTick = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition not reached: " + detail.get());
    }

    private WorkIntent lane(String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, ownerProject.projectId(), participant, "codex", UUID.randomUUID(), "Build a board", "CRUD and persistence", "base-commit",
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private static boolean announces(Computer computer, WorkIntent intent) {
        try {
            return new WorkIntentService(computer.store(), computer.node).announce(intent).acquired();
        } catch (Exception refused) {
            return false;
        }
    }

    private static long entriesOf(Computer holder, Computer author) {
        try {
            return holder.store().events().stream().filter(event -> event.actorNodeId().equals(author.node.nodeId())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    private String journals(Computer... computers) {
        StringBuilder text = new StringBuilder();
        for (Computer computer : computers) {
            text.append("\n--- ").append(computer.node.nodeId(), 0, Math.min(12, computer.node.nodeId().length())).append(" cards=").append(computer.cards().size()).append('\n');
            computer.journal().forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }

    /** B and C each join the owner A by a project link of A's, and A accepts them. */
    private void join(Computer owner, Computer joiner) throws Exception {
        Map<String, Object> invited = owner.post("invite", Map.of("project", true));
        joiner.post("open-project-link", Map.of("link", invited.get("inviteUri")));
        await(() -> owner.joinRequests() == 1, () -> "the owner holds the request of the joiner" + journals(owner, joiner));
        owner.post("accept-join", Map.of("nodeId", joiner.node.nodeId()));
        await(() -> joiner.namesMember(joiner.node.nodeId()), () -> "the joiner learned it belongs" + journals(owner, joiner));
    }

    private Computer[] threeJoined() throws Exception {
        Computer a = start(ownerProject);
        Computer b = start(clone("b"));
        join(a, b);
        Computer c = start(clone("c"));
        join(a, c);
        await(() -> b.namesMember(c.node.nodeId()) && c.namesMember(b.node.nodeId()), () -> "B and C learned of each other from A's log" + journals(a, b, c));
        return new Computer[] {a, b, c};
    }

    /** With A down, an entry of one reaches the other over their direct session, both ways; then A returns and all three converge. */
    private void survivePartnerDownAndConverge(Computer a, Computer lo, Computer hi) throws Exception {
        a.stop();
        assertTrue(lo.linkedTo(hi) && hi.linkedTo(lo));
        assertTrue(announces(lo, lane("agt-lo", "src/lo.py")));
        long fromLo = entriesOf(lo, lo);
        await(() -> entriesOf(hi, lo) == fromLo, () -> "an entry of " + "lo reached hi with A down" + journals(lo, hi));
        assertTrue(announces(hi, lane("agt-hi", "src/hi.py")));
        long fromHi = entriesOf(hi, hi);
        await(() -> entriesOf(lo, hi) == fromHi, () -> "an entry of hi reached lo with A down" + journals(lo, hi));
        assertFalse(a.owner.connectedTo(lo.node.nodeId()) && !a.stopped, "A is down");

        Computer again = start(ownerProject);
        awaitTicking(() -> again.entryIds().equals(lo.entryIds()) && lo.entryIds().equals(hi.entryIds()) && again.entryIds().size() > 3,
                () -> "A, B and C hold the same entries: a=" + again.entryIds().size() + " lo=" + lo.entryIds().size() + " hi=" + hi.entryIds().size() + journals(again, lo, hi),
                again, lo, hi);
        awaitTicking(() -> again.linkedTo(lo) && again.linkedTo(hi) && lo.linkedTo(again) && hi.linkedTo(again), () -> "A is linked to both again" + journals(again, lo, hi), again, lo, hi);
    }

    @Test
    void theMembersPublishTheirCardsAndTwoOfThemLinkDirectlyAndGoOnExchangingWhileTheOwnerIsDown() throws Exception {
        Computer[] three = threeJoined();
        Computer a = three[0];
        Computer b = three[1];
        Computer c = three[2];

        awaitTicking(() -> a.cards().size() == 3 && b.cards().size() == 3 && c.cards().size() == 3, () -> "each publishes a MEMBER_CARD, and every log holds all three" + journals(a, b, c), a, b, c);
        assertEquals(Set.of(a.node.nodeId(), b.node.nodeId(), c.node.nodeId()), a.cards().keySet());
        Computer lo = b.node.nodeId().compareTo(c.node.nodeId()) < 0 ? b : c;
        Computer hi = lo == b ? c : b;

        awaitTicking(() -> b.linkedTo(c) && c.linkedTo(b), () -> "B and C hold a direct session with each other (lower ID dials the card of the higher)" + journals(a, b, c), a, b, c);

        survivePartnerDownAndConverge(a, lo, hi);
    }

    @Test
    void aMemberThatRestartsListensAgainOnItsCardAndTheOtherReconnectsWithNoNewInvitation() throws Exception {
        Computer[] three = threeJoined();
        Computer a = three[0];
        Computer b = three[1];
        Computer c = three[2];
        awaitTicking(() -> a.cards().size() == 3 && b.cards().size() == 3 && c.cards().size() == 3, () -> "all cards published" + journals(a, b, c), a, b, c);
        Computer lo = b.node.nodeId().compareTo(c.node.nodeId()) < 0 ? b : c;
        Computer hi = lo == b ? c : b;
        // The lower ID dials the card of the higher, so the restarted member is the higher: its card is the one dialed.
        ReplicationCard before = ReplicationCard.read(hi.coordination).orElseThrow();
        assertEquals(before.link(), hi.cards().get(hi.node.nodeId()), "the card in the file is the card in the log");
        long entriesBefore = hi.cardEntries();
        ProjectLocation hiLocation = hi.location;

        hi.stop();
        Computer restarted = start(hiLocation);

        assertTrue(restarted.journal().stream().anyMatch(line -> line.endsWith("CARD_RESUMED port=" + before.port())), journals(restarted));
        assertEquals(Optional.of(before), ReplicationCard.read(restarted.coordination), "the same port and invitation");
        awaitTicking(() -> lo.linkedTo(restarted) && restarted.linkedTo(lo), () -> "the other reconnects to the resumed card" + journals(a, lo, restarted), a, lo, restarted);
        assertEquals(entriesBefore, restarted.cardEntries(), "no new MEMBER_CARD: the card in the log is still the card");
        assertEquals(before.link(), lo.cards().get(restarted.node.nodeId()));

        survivePartnerDownAndConverge(a, lo, restarted);
    }
}
