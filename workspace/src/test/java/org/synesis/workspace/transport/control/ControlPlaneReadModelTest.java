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
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
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
        assertEquals(List.of("apiVersion", "runtime", "project", "knownProjects", "providers", "agents", "workgroups", "claims", "capabilities", "conversations", "operatorRequests", "tasks", "disputes", "trash", "ownerships", "network", "diagnostics"), List.copyOf(parsed.keySet()));
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

    private static Map<String, Object> dispute(Map<String, Object> snapshot, String snapshotId) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> disputes = (List<Map<String, Object>>) snapshot.get("disputes");
        return disputes.stream().filter(entry -> snapshotId.equals(entry.get("snapshotId"))).findFirst().orElseThrow();
    }

    /** ADR-0098 step 7, designer items 12 to 16: the snapshot lists the trash, oldest first, with who trashed it and whether it was emptied. */
    @Test
    void listsTheTrashInTheSnapshot() throws Exception {
        Path root = temp.resolve("trash");
        Path synesis = root.resolve(".synesis");
        Path profile = synesis.resolve("local/profile");
        Files.createDirectories(profile);
        UUID projectId = UUID.randomUUID();
        ProjectLocation location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
        NodeIdentity signer = NodeIdentity.generate();
        PredictionEventStore writer = new PredictionEventStore(location.coordinationDirectory(), projectId);
        UUID groupId = UUID.randomUUID();
        new WorkGroupService(writer, signer).create(new WorkGroup(groupId, projectId, "goal", "acceptance", 1, WorkGroup.Status.ACTIVE));
        UUID laneId = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(laneId, projectId, "agt-trash", "codex", UUID.randomUUID(), "implement", "acceptance", "HEAD",
                List.of(ResourceSelector.pathExact("src/T.java")), 1, groupId, WorkIntent.defaultAuthorityLineage(laneId), WorkIntent.Status.ANNOUNCED);
        new WorkIntentService(writer, signer).announce(lane);
        ControlPlaneReadModel before = model(location, signer, projectId, root);
        assertEquals(List.of(), before.snapshot().get("trash"));

        org.synesis.coordination.application.TrashService trash = new org.synesis.coordination.application.TrashService(new PredictionEventStore(location.coordinationDirectory(), projectId), signer);
        trash.trash(new org.synesis.coordination.domain.trash.TrashEntries.WorkTrashed(laneId, 1, lane.taskId(), groupId, "a".repeat(40), "TAKEN_OVER"));
        UUID restored = trash.restore(laneId, 1);
        Map<String, Object> snapshot = model(location, signer, projectId, root).snapshot();

        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("trash");
        assertEquals(1, items.size());
        Map<String, Object> item = items.getFirst();
        assertEquals(laneId.toString(), item.get("laneId"));
        assertEquals(1L, ((Number) item.get("epoch")).longValue());
        assertEquals("TAKEN_OVER", item.get("reason"));
        assertEquals("a".repeat(40), item.get("commit"));
        assertEquals(signer.nodeId(), item.get("trashedBy"));
        assertEquals(List.of(restored.toString()), item.get("restoredInto"));
        assertEquals(false, item.get("emptied"));

        trash.empty(laneId, 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> after = (List<Map<String, Object>>) model(location, signer, projectId, root).snapshot().get("trash");
        assertEquals(true, after.getFirst().get("emptied"));
    }

    private ControlPlaneReadModel model(ProjectLocation location, NodeIdentity signer, UUID projectId, Path root) throws Exception {
        CoordinationService coordination = new CoordinationService(new PredictionEventStore(location.coordinationDirectory(), projectId), signer);
        return new ControlPlaneReadModel(location, coordination, new ProviderApplicationService(), new DoctorService(), NetworkSnapshot::empty, null);
    }

    /** ADR-0098 step 7, designer item 8: a dispute stays on record with its verdicts and says whether it is open or ended. */
    @Test
    void listsReviewDisputesWithTheirVerdictsAndStateOpenOrEnded() throws Exception {
        Path root = temp.resolve("disputes");
        Path synesis = root.resolve(".synesis");
        Path profile = synesis.resolve("local/profile");
        Files.createDirectories(profile);
        UUID projectId = UUID.randomUUID();
        ProjectLocation location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
        PredictionEventStore writer = new PredictionEventStore(location.coordinationDirectory(), projectId);
        NodeIdentity signer = NodeIdentity.generate();
        UUID groupId = UUID.randomUUID();
        List<String> names = List.of("snap_open", "snap_ended");
        UUID[] lanes = {UUID.randomUUID(), UUID.randomUUID()};
        UUID[] tasks = {UUID.randomUUID(), UUID.randomUUID()};
        for (int i = 0; i < names.size(); i++) {
            WorkIntent intent = new WorkIntent(lanes[i], projectId, "agt-author", "codex", tasks[i], "implement", "acceptance", "HEAD",
                    List.of(ResourceSelector.pathExact("src/" + names.get(i) + ".txt")), 1, groupId, WorkIntent.defaultAuthorityLineage(lanes[i]), WorkIntent.Status.ANNOUNCED);
            assertTrue(new WorkIntentService(writer, signer).announce(intent).acquired());
            SnapshotProvenance provenance = new SnapshotProvenance(groupId, lanes[i], intent.authorityLineageId(), "agt-author", "session-author", 1,
                    List.of(), List.of(), List.of("PATH:src/" + names.get(i) + ".txt"), "refs/synesis/snapshots/" + names.get(i), "integrity", "artifacts");
            writer.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, signer.nodeId(),
                    new TaskSnapshotPayload(tasks[i], names.get(i), "node", "supervisor", "worker", "session-author", "HEAD", "commit-" + names.get(i),
                            List.of("src/" + names.get(i) + ".txt"), List.of(), "work", provenance, true).encode(), signer);
        }
        // Two verdicts that differ meet on one log by arrival: agt-b writes on its own log, copied here.
        PredictionEventStore elsewhere = new PredictionEventStore(temp.resolve("elsewhere"), projectId);
        NodeIdentity other = NodeIdentity.generate();
        LaneGrant[] grants = new LaneGrant[4];
        String[] reviewers = {"agt-b", "agt-a", "agt-b", "agt-a"};
        String[] snapshots = {"snap_open", "snap_open", "snap_ended", "snap_ended"};
        String[] results = {"REJECTED", "ACCEPTED", "REJECTED", "ACCEPTED"};
        for (int i = 0; i < grants.length; i++) {
            grants[i] = new LaneGrant(UUID.randomUUID(), groupId, lanes[i / 2], reviewers[i], 1, true);
            for (PredictionEventType type : List.of(PredictionEventType.LANE_GRANT_ISSUED, PredictionEventType.LANE_GRANT_CONSUMED)) {
                writer.append(grants[i].grantId(), type, signer.nodeId(), CollaborationCodec.encodeLaneGrant(grants[i]), signer);
            }
        }
        elsewhere.placeCopies(signer.nodeId(), writer.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        UUID introducedLane = UUID.randomUUID();
        WorkIntent introduced = new WorkIntent(introducedLane, projectId, "agt-b", "codex", UUID.randomUUID(), "review", "acceptance", "HEAD",
                List.of(ResourceSelector.pathExact("src/other.txt")), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(introducedLane), WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(elsewhere, other).announce(introduced).acquired());
        for (int i : new int[] {0, 2}) {
            elsewhere.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, other.nodeId(),
                    new ReviewValidationPayload(grants[i].grantId(), groupId, lanes[i / 2], reviewers[i], 1, tasks[i / 2], snapshots[i], results[i], "not good enough",
                            "agt-author").encode(), other);
        }
        for (int i : new int[] {1, 3}) {
            writer.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, signer.nodeId(),
                    new ReviewValidationPayload(grants[i].grantId(), groupId, lanes[i / 2], reviewers[i], 1, tasks[i / 2], snapshots[i], results[i], null,
                            "agt-author").encode(), signer);
        }
        writer.placeCopies(other.nodeId(), elsewhere.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        // agt-b changes its verdict on snap_ended, so that dispute ends.
        elsewhere.placeCopies(signer.nodeId(), writer.entriesAfter(0, 10_000, Integer.MAX_VALUE));
        elsewhere.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, other.nodeId(),
                new ReviewValidationPayload(grants[2].grantId(), groupId, lanes[1], "agt-b", 1, tasks[1], "snap_ended", "ACCEPTED", null,
                        "agt-author").encode(), other);
        writer.placeCopies(other.nodeId(), elsewhere.entriesAfter(0, 10_000, Integer.MAX_VALUE));

        PredictionEventStore current = new PredictionEventStore(location.coordinationDirectory(), projectId);
        ControlPlaneReadModel model = new ControlPlaneReadModel(location, new CoordinationService(current, signer), new ProviderApplicationService(),
                new DoctorService(), NetworkSnapshot::empty, null);
        Map<String, Object> snapshot = model.snapshot();

        @SuppressWarnings("unchecked") List<Map<String, Object>> disputes = (List<Map<String, Object>>) snapshot.get("disputes");
        assertEquals(2, disputes.size(), disputes.toString());
        Map<String, Object> open = dispute(snapshot, "snap_open");
        assertEquals("review_disputed", open.get("state"));
        assertEquals("agt-author", open.get("author"));
        assertEquals(tasks[0].toString(), open.get("taskId"));
        assertEquals(Map.of("agt-a", "ACCEPTED", "agt-b", "REJECTED"), open.get("verdicts"));
        assertEquals(List.of("agt-a", "agt-b"), List.copyOf(object(open.get("verdicts")).keySet()), "verdicts are sorted by reviewer");
        Map<String, Object> ended = dispute(snapshot, "snap_ended");
        assertEquals("review_accepted", ended.get("state"));
        assertEquals(Map.of("agt-a", "ACCEPTED", "agt-b", "ACCEPTED"), ended.get("verdicts"));
        assertEquals(List.of("snapshotId", "taskId", "author", "verdicts", "state"), List.copyOf(open.keySet()));
        assertEquals(disputes, model.snapshot().get("disputes"), "the record is stable between reads");
    }

    @Test
    void aProjectWithoutReviewDisputesHasAnEmptyDisputeList() throws Exception {
        Path root = temp.resolve("none");
        Path synesis = root.resolve(".synesis");
        Path profile = synesis.resolve("local/profile");
        Files.createDirectories(profile);
        UUID projectId = UUID.randomUUID();
        ProjectLocation location = new ProjectLocation(root, synesis, root.resolve("project.json"), profile, projectId, Instant.now());
        CoordinationService coordination = new CoordinationService(new PredictionEventStore(location.coordinationDirectory(), projectId), NodeIdentity.generate());
        ControlPlaneReadModel model = new ControlPlaneReadModel(location, coordination, new ProviderApplicationService(), new DoctorService(), NetworkSnapshot::empty, null);

        assertEquals(List.of(), model.snapshot().get("disputes"));
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
