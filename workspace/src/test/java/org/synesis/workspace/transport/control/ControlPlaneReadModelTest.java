package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.transport.control.network.NetworkPeer;
import org.synesis.workspace.transport.control.network.NetworkRoute;
import org.synesis.workspace.transport.control.network.NetworkSnapshot;
import org.synesis.workspace.transport.control.network.OverlayEdge;
import org.synesis.workspace.transport.control.network.OverlayMember;
import org.synesis.workspace.transport.control.network.OverlaySummary;
import org.synesis.workspace.transport.control.network.RelayState;

class ControlPlaneReadModelTest {

    @TempDir
    Path temp;

    @Test
    void mapsDurableWorkGroupClaimsAndDistinctNetworkViews() throws Exception {
        Path root = temp.resolve("project");
        Path synesis = root.resolve(".synesis");
        Path profile = synesis.resolve("local/profile");
        Files.createDirectories(profile);
        UUID projectId = UUID.randomUUID();
        ProjectLocation location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
        Path coordinationRoot = location.coordinationDirectory();
        NodeIdentity signer = NodeIdentity.generate();
        PredictionEventStore writer = new PredictionEventStore(coordinationRoot, projectId);
        UUID groupId = UUID.randomUUID();
        new WorkGroupService(writer, signer).create(new WorkGroup(groupId, projectId, "shared goal", "shared acceptance", 1, WorkGroup.Status.ACTIVE));
        UUID intentId = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(intentId,
                projectId,
                "agt_demo",
                "codex",
                UUID.randomUUID(),
                "implement the slice",
                "tests pass",
                "HEAD",
                List.of(ResourceSelector.pathExact("src/App.java")),
                1,
                groupId,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER);
        new WorkIntentService(new PredictionEventStore(coordinationRoot, projectId), signer).announce(intent);

        PredictionEventStore current = new PredictionEventStore(coordinationRoot, projectId);
        CoordinationService coordination = new CoordinationService(current, signer);
        NetworkSnapshot network = new NetworkSnapshot("HEALTHY",
                List.of(new NetworkPeer("node-b", "session-b", "ALIVE", true, "2026-09-08T00:00:00Z", true, "HEALTHY")),
                List.of(new NetworkRoute("PEER_TRANSIT", "node-c", List.of("node-a", "node-b", "node-c"), "node-b", "")),
                new OverlaySummary("CURRENT",
                        "node-a",
                        4,
                        3,
                        "2026-09-08T01:00:00Z",
                        List.of(new OverlayMember("node-a", "CURRENT"), new OverlayMember("node-b", "CURRENT")),
                        List.of(new OverlayEdge("node-a", "node-b", "DIRECT")),
                        List.of(new OverlayEdge("node-a", "node-c", "DESIRED"))),
                new RelayState("CONNECTED", "127.0.0.1:48124", 2, true, true, "relay-node", true, 1));
        ControlPlaneReadModel model = new ControlPlaneReadModel(location, coordination, new ProviderApplicationService(), new DoctorService(), () -> network, null);
        Map<String, Object> snapshot = model.snapshot();

        @SuppressWarnings("unchecked") List<Map<String, Object>> groups = (List<Map<String, Object>>) snapshot.get("workgroups");
        assertEquals(1, groups.size());
        assertEquals(groupId.toString(), groups.getFirst().get("id"));
        assertEquals(List.of("agt_demo"), groups.getFirst().get("participants"));

        @SuppressWarnings("unchecked") List<Map<String, Object>> claims = (List<Map<String, Object>>) snapshot.get("claims");
        assertEquals(1, claims.size());
        assertEquals(intentId.toString(), claims.getFirst().get("intentId"));

        @SuppressWarnings("unchecked") Map<String, Object> networkMap = (Map<String, Object>) snapshot.get("network");
        @SuppressWarnings("unchecked") List<Map<String, Object>> peers = (List<Map<String, Object>>) networkMap.get("peers");
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) networkMap.get("routes");
        assertEquals(1, peers.size());
        assertEquals("node-b", peers.getFirst().get("nodeId"));
        assertEquals("PEER_TRANSIT", routes.getFirst().get("kind"));
        assertTrue(networkMap.get("overlay").toString().contains("memberCount=3"));
        assertTrue(networkMap.get("overlay").toString().contains("directEdges"));
        assertTrue(networkMap.get("relay").toString().contains("CONNECTED"));

        assertSerializedKeyOrder(model, groupId);
        assertEquals(snapshot.get("network"), model.network());
        assertEquals(snapshot.get("agents"), model.agents());
        assertEquals(snapshot.get("workgroups"), model.workgroups());
        assertEquals(snapshot.get("claims"), model.claims());
        assertEquals(snapshot.get("capabilities"), model.capabilities());
    }

    private static void assertSerializedKeyOrder(ControlPlaneReadModel model, UUID groupId) {
        String first = ProviderJson.write(model.snapshot());
        // The diagnostics section carries a fresh report id and timestamp, so only the other sections repeat byte for byte.
        assertEquals(ProviderJson.write(model.network()), ProviderJson.write(model.network()));
        assertEquals(ProviderJson.write(model.claims()), ProviderJson.write(model.claims()));
        assertEquals(ProviderJson.write(model.capabilities()), ProviderJson.write(model.capabilities()));

        Map<String, Object> parsed = object(ProviderJson.parse(first));
        assertEquals(List.of("apiVersion", "runtime", "project", "knownProjects", "providers", "agents", "workgroups", "claims", "capabilities", "conversations", "operatorRequests", "tasks", "ownerships", "network", "diagnostics"), List.copyOf(parsed.keySet()));
        Map<String, Object> network = object(parsed.get("network"));
        assertEquals(List.of("status", "peers", "routes", "overlay", "relay"), List.copyOf(network.keySet()));
        assertEquals(List.of("nodeId", "sessionId", "liveness", "usable", "establishedAt", "authenticated", "health"), List.copyOf(object(((List<?>) network.get("peers")).getFirst()).keySet()));
        assertEquals(List.of("kind", "destinationNodeId", "path", "nextHop", "relayNodeId"), List.copyOf(object(((List<?>) network.get("routes")).getFirst()).keySet()));
        Map<String, Object> overlay = object(network.get("overlay"));
        assertEquals(List.of("status", "authorityNodeId", "revision", "memberCount", "expiresAt", "members", "directEdges", "desiredEdges"), List.copyOf(overlay.keySet()));
        assertEquals(List.of("nodeId", "status"), List.copyOf(object(((List<?>) overlay.get("members")).getFirst()).keySet()));
        assertEquals(List.of("from", "to", "status"), List.copyOf(object(((List<?>) overlay.get("directEdges")).getFirst()).keySet()));
        assertEquals(List.of("status", "localAddress", "activeConnections", "configured", "connected", "relayIdentity", "authorized", "activeRouteUsage"), List.copyOf(object(network.get("relay")).keySet()));
        Map<String, Object> claim = object(((List<?>) parsed.get("claims")).getFirst());
        assertEquals(List.of("kind", "value"), List.copyOf(object(((List<?>) claim.get("selectors")).getFirst()).keySet()));
        assertEquals(groupId.toString(), object(((List<?>) parsed.get("workgroups")).getFirst()).get("id"));
    }

    private static Map<String, Object> object(Object parsed) {
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) parsed;
        return result;
    }

    @Test
    void registersAnUninitializedFolderAfterPreparingGitAndSynesisState() throws Exception {
        Path currentRoot = temp.resolve("current");
        Files.createDirectories(currentRoot);
        org.synesis.workspace.test.TestGit.output(currentRoot, "init");
        ProjectInitResult current = new ProjectApplicationService().init(currentRoot, false);
        Path selected = temp.resolve("selected");
        Files.createDirectories(selected);
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry.json"));
        CoordinationService coordination = new CoordinationService(new PredictionEventStore(current.location().root().resolve(".synesis/coordination"), current.location().projectId()), current.identity());
        ControlPlaneReadModel model = new ControlPlaneReadModel(current.location(), coordination, new ProviderApplicationService(), new DoctorService(), NetworkSnapshot::empty, registry);

        new ControlPlaneProjectEndpoints(model, registry, ControlPlaneHttpHandler.SelectionGateway.UNAVAILABLE).registerKnownProject(selected);

        assertTrue(Files.isDirectory(selected.resolve(".git")));
        assertTrue(Files.exists(selected.resolve(".synesis/project.json")));
        assertEquals(1, registry.projects().size());
        assertEquals(1, model.knownProjects().size());
    }
}
