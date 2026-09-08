package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;

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
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService.ProjectLocation(
                root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
        Path coordinationRoot = synesis.resolve("coordination");
        NodeIdentity signer = NodeIdentity.generate();
        PredictionEventStore writer = new PredictionEventStore(coordinationRoot, projectId);
        UUID groupId = UUID.randomUUID();
        new WorkGroupService(writer, signer).create(new WorkGroup(groupId, projectId, "shared goal",
                "shared acceptance", 1, WorkGroup.Status.ACTIVE));
        UUID intentId = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(intentId, projectId, "agt_demo", "codex", UUID.randomUUID(),
                "implement the slice", "tests pass", "HEAD", List.of(ResourceSelector.pathExact("src/App.java")),
                1, groupId, WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER);
        new WorkIntentService(new PredictionEventStore(coordinationRoot, projectId), signer).announce(intent);

        PredictionEventStore current = new PredictionEventStore(coordinationRoot, projectId);
        CoordinationService coordination = new CoordinationService(current, signer);
        ControlPlaneReadModel.NetworkSnapshot network = new ControlPlaneReadModel.NetworkSnapshot(
                "HEALTHY",
                List.of(new ControlPlaneReadModel.NetworkPeer("node-b", "session-b", "ALIVE", true,
                        "2026-09-08T00:00:00Z", true, "HEALTHY")),
                List.of(new ControlPlaneReadModel.NetworkRoute("PEER_TRANSIT", "node-c",
                        List.of("node-a", "node-b", "node-c"), "node-b", "")),
                new ControlPlaneReadModel.OverlaySummary("CURRENT", "node-a", 4, 3,
                        "2026-09-08T01:00:00Z",
                        List.of(new ControlPlaneReadModel.OverlayMember("node-a", "CURRENT"),
                                new ControlPlaneReadModel.OverlayMember("node-b", "CURRENT")),
                        List.of(new ControlPlaneReadModel.OverlayEdge("node-a", "node-b", "DIRECT")),
                        List.of(new ControlPlaneReadModel.OverlayEdge("node-a", "node-c", "DESIRED"))),
                new ControlPlaneReadModel.RelaySummary("CONNECTED", "127.0.0.1:48124", 2,
                        true, true, "relay-node", true, 1));
        Map<String, Object> snapshot = new ControlPlaneReadModel(location, coordination,
                new ProviderApplicationService(), new DoctorService(), () -> network).snapshot();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) snapshot.get("workgroups");
        assertEquals(1, groups.size());
        assertEquals(groupId.toString(), groups.getFirst().get("id"));
        assertEquals(List.of("agt_demo"), groups.getFirst().get("participants"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claims = (List<Map<String, Object>>) snapshot.get("claims");
        assertEquals(1, claims.size());
        assertEquals(intentId.toString(), claims.getFirst().get("intentId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> networkMap = (Map<String, Object>) snapshot.get("network");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peers = (List<Map<String, Object>>) networkMap.get("peers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) networkMap.get("routes");
        assertEquals(1, peers.size());
        assertEquals("node-b", peers.getFirst().get("nodeId"));
        assertEquals("PEER_TRANSIT", routes.getFirst().get("kind"));
        assertTrue(networkMap.get("overlay").toString().contains("memberCount=3"));
        assertTrue(networkMap.get("overlay").toString().contains("directEdges"));
        assertTrue(networkMap.get("relay").toString().contains("CONNECTED"));
    }
}
