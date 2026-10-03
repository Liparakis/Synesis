package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.IntentVersion;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.ownership.OwnershipDecision;
import org.synesis.coordination.domain.ownership.OwnershipOwner;
import org.synesis.coordination.domain.ownership.OwnershipRegistry;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionState;
import org.synesis.coordination.domain.speculation.SpeculationWorkspace;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.task.TaskClaim;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.transport.http.CoordinationHttpClient;
import org.synesis.coordination.transport.http.CoordinationHttpServer;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies the first coordination domain and durable event-store slice.
 */
class PredictionCoordinationTest {

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static PredictionContract contract(UUID project, UUID prediction, String node) {
        return new PredictionContract(prediction,
                project,
                node,
                "supervisor-a",
                "worker-a",
                UUID.randomUUID(),
                "workspace.prediction-status",
                node,
                "supervisor-a",
                List.of("workspace/**"),
                0,
                "HEAD",
                List.of("workspace/**=absent"),
                0,
                "purpose",
                "inputs",
                "outputs",
                "behavior",
                "errors",
                "none",
                "invariants",
                "compatible",
                "normal",
                "single-threaded",
                List.of("test"),
                80,
                20,
                1_900_000_000_000L);
    }

    /**
     * Builds a prediction contract addressed from one foreground actor to another.
     *
     * @param project    project identity
     * @param prediction prediction identity
     * @param requester  authenticated requester
     * @param owner      designated owner
     * @return contract bound to both foreground actors
     */
    private static PredictionContract supervisorContract(UUID project,
            UUID prediction,
            NodeIdentity requester,
            NodeIdentity owner) {
        return new PredictionContract(prediction,
                project,
                requester.nodeId(),
                "sup-a",
                "worker-a",
                UUID.randomUUID(),
                "workspace.prediction-status",
                owner.nodeId(),
                "sup-b",
                List.of("workspace/**"),
                0,
                "HEAD",
                List.of("workspace/**=absent"),
                0,
                "purpose",
                "inputs",
                "outputs",
                "behavior",
                "errors",
                "none",
                "invariants",
                "compatible",
                "normal",
                "single-threaded",
                List.of("test"),
                80,
                20,
                1_900_000_000_000L);
    }

    /**
     * Signs and submits one command through a foreground coordinator client.
     *
     * @param client     foreground HTTP client
     * @param project    project identity
     * @param prediction prediction identity
     * @param type       requested transition
     * @param supervisor actor supervisor identity
     * @param worker     actor worker identity
     * @param payload    event payload
     * @param actor      node identity signing the command
     * @throws Exception if signing or HTTP submission fails
     */
    private static void submitAs(CoordinationHttpClient client,
            UUID project,
            UUID prediction,
            PredictionEventType type,
            SupervisorId supervisor,
            WorkerId worker,
            byte[] payload,
            NodeIdentity actor) throws Exception {
        CoordinationCommand command = CoordinationCommand.createAs(UUID.randomUUID(),
                project,
                prediction,
                type,
                actor.nodeId(),
                supervisor.value(),
                worker.value(),
                payload,
                actor);
        client.submit(command);
    }

    @Test
    void validationContextSupportsTypedHandlesAndPreservesInvalidStringLookup() throws Exception {
        Path root = Files.createTempDirectory("synesis-validation-context-");
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, UUID.randomUUID()),
                NodeIdentity.generate());
        CapabilityRequestHandle handle = new CapabilityRequestHandle("req_abcdefghijkl");

        assertTrue(service.validationContext(handle).isEmpty());
        assertTrue(service.validationContext("invalid").isEmpty());
    }

    @Test
    void signedEventsReplayAndRejectInvalidTransitions() throws Exception {
        Path root = Files.createTempDirectory("synesis-coordination-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
        PredictionContract contract = contract(project, prediction, identity.nodeId());
        PredictionEventStore store = new PredictionEventStore(root, project, clock);

        PredictionEvent created = store.append(prediction,
                PredictionEventType.PREDICTION_CREATED,
                identity.nodeId(),
                contract.encoded(),
                identity);
        assertEquals(1, created.sequence());
        assertTrue(created.verify());
        store.append(prediction, PredictionEventType.PREDICTION_ROUTED, identity.nodeId(), new byte[0], identity);
        store.append(prediction, PredictionEventType.REQUEST_RECEIVED, identity.nodeId(), new byte[0], identity);
        store.append(prediction, PredictionEventType.ACCEPTED_EXACT, identity.nodeId(), new byte[0], identity);
        assertEquals(PredictionState.ACCEPTED_EXACT, store.projection().state(prediction).orElseThrow());

        assertThrows(IllegalStateException.class,
                () -> store.append(prediction,
                        PredictionEventType.SPECULATION_RETIRED,
                        identity.nodeId(),
                        new byte[0],
                        identity));

        PredictionEventStore reloaded = new PredictionEventStore(root, project, clock);
        assertEquals(4, reloaded.headSequence());
        assertEquals(PredictionState.ACCEPTED_EXACT, reloaded.projection().state(prediction).orElseThrow());
    }

    @Test
    void contractRoundTripsForCoordinatorAuthorization() throws Exception {
        PredictionContract original = contract(UUID.randomUUID(), UUID.randomUUID(), NodeIdentity.generate().nodeId());
        assertEquals(original, PredictionContract.decode(original.encoded()));
    }

    @Test
    void coordinatorRejectsLifecycleCommandFromForeignNode() throws Exception {
        Path root = Files.createTempDirectory("synesis-authorization-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        NodeIdentity foreign = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project),
                NodeIdentity.generate());
        PredictionContract predictionContract = new PredictionContract(prediction,
                project,
                requester.nodeId(),
                "sup-a",
                "worker-a",
                UUID.randomUUID(),
                "workspace.prediction-status",
                owner.nodeId(),
                "sup-b",
                List.of("workspace/**"),
                0,
                "HEAD",
                List.of("workspace/**=absent"),
                0,
                "purpose",
                "inputs",
                "outputs",
                "behavior",
                "errors",
                "none",
                "invariants",
                "compatible",
                "normal",
                "single-threaded",
                List.of("test"),
                80,
                20,
                1_900_000_000_000L);
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.PREDICTION_CREATED,
                requester.nodeId(),
                predictionContract.encoded(),
                requester));
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.PREDICTION_ROUTED,
                requester.nodeId(),
                new byte[0],
                requester));
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.REQUEST_RECEIVED,
                owner.nodeId(),
                new byte[0],
                owner));
        GeneralSecurityException failure = assertThrows(GeneralSecurityException.class,
                () -> service.submit(CoordinationCommand.create(UUID.randomUUID(),
                        project,
                        prediction,
                        PredictionEventType.ACCEPTED_EXACT,
                        foreign.nodeId(),
                        new byte[0],
                        foreign)));
        assertEquals("ACTOR_NOT_AUTHORIZED", failure.getMessage());
    }

    @Test
    void logicalRequesterProfileCannotActAsOwnerOnSharedNode() throws Exception {
        Path root = Files.createTempDirectory("synesis-logical-authorization-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity shared = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project),
                NodeIdentity.generate());
        PredictionContract contract = new PredictionContract(prediction,
                project,
                shared.nodeId(),
                "requester-sup",
                "requester-worker",
                UUID.randomUUID(),
                "workspace.prediction-status",
                shared.nodeId(),
                "owner-sup",
                List.of("workspace/**"),
                0,
                "HEAD",
                List.of("workspace/**=absent"),
                0,
                "purpose",
                "inputs",
                "outputs",
                "behavior",
                "errors",
                "none",
                "invariants",
                "compatible",
                "normal",
                "single-threaded",
                List.of("test"),
                80,
                20,
                1_900_000_000_000L);
        CoordinationCommand created = CoordinationCommand.createAs(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.PREDICTION_CREATED,
                shared.nodeId(),
                "requester-sup",
                "requester-worker",
                contract.encoded(),
                shared);
        CoordinationCommand decoded = CoordinationCommand.decode(created.encoded());
        assertEquals("requester-sup", decoded.actorSupervisorId());
        assertEquals("requester-worker", decoded.actorWorkerId());
        service.submit(created);
        service.submit(CoordinationCommand.createAs(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.PREDICTION_ROUTED,
                shared.nodeId(),
                "requester-sup",
                "requester-worker",
                new byte[0],
                shared));
        assertThrows(GeneralSecurityException.class,
                () -> service.submit(CoordinationCommand.createAs(UUID.randomUUID(),
                        project,
                        prediction,
                        PredictionEventType.REQUEST_RECEIVED,
                        shared.nodeId(),
                        "requester-sup",
                        "requester-worker",
                        new byte[0],
                        shared)));
        service.submit(CoordinationCommand.createAs(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.REQUEST_RECEIVED,
                shared.nodeId(),
                "owner-sup",
                "owner-worker",
                new byte[0],
                shared));
    }

    @Test
    void taskAndOwnershipClaimsAreDurableAndConflictSafe() throws Exception {
        Path root = Files.createTempDirectory("synesis-task-ownership-");
        UUID project = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        NodeIdentity foreign = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project),
                NodeIdentity.generate());
        CoordinationTask task = new CoordinationTask(taskId,
                project,
                "prediction status",
                "workspace.prediction-status",
                requester.nodeId(),
                "sup-a",
                "worker-a");
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                taskId,
                PredictionEventType.TASK_CREATED,
                requester.nodeId(),
                task.encoded(),
                requester));
        TaskClaim claim = new TaskClaim(taskId, owner.nodeId(), "sup-b", "worker-b");
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                taskId,
                PredictionEventType.TASK_CLAIMED,
                owner.nodeId(),
                claim.encoded(),
                owner));
        OwnershipClaim ownership = new OwnershipClaim(new TaskId(taskId),
                new CapabilityId("workspace.prediction-status"),
                new NodeId(owner.nodeId()),
                new SupervisorId("sup-b"),
                List.of("workspace/**"),
                new IntentVersion(1));
        service.submit(CoordinationCommand.create(UUID.randomUUID(),
                project,
                taskId,
                PredictionEventType.OWNERSHIP_CLAIMED,
                owner.nodeId(),
                ownership.encoded(),
                owner));
        assertEquals(owner.nodeId(),
                service.coordinationProjection().task(new TaskId(taskId)).orElseThrow().ownerNodeId());
        assertEquals(owner.nodeId(),
                service.coordinationProjection().ownership(new CapabilityId("workspace.prediction-status"))
                        .orElseThrow().ownerNodeId());
        assertEquals(owner.nodeId(),
                service.coordinationProjection().ownershipsByCapability()
                        .get(new CapabilityId("workspace.prediction-status")).ownerNodeId());
        assertThrows(GeneralSecurityException.class,
                () -> service.submit(CoordinationCommand.create(UUID.randomUUID(),
                        project,
                        taskId,
                        PredictionEventType.OWNERSHIP_CLAIMED,
                        foreign.nodeId(),
                        ownership.encoded(),
                        foreign)));
        CoordinationService reloaded = new CoordinationService(new PredictionEventStore(root, project),
                NodeIdentity.generate());
        assertEquals(owner.nodeId(), reloaded.coordinationProjection().task(taskId).orElseThrow().ownerNodeId());
    }

    @Test
    void signedCommandsAreIdempotentAndReplayToSubscribers() throws Exception {
        Path root = Files.createTempDirectory("synesis-command-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity coordinator = NodeIdentity.generate();
        PredictionEventStore store = new PredictionEventStore(root, project);
        CoordinationService service = new CoordinationService(store, coordinator);
        CoordinationCommand created = CoordinationCommand.create(UUID.randomUUID(),
                project,
                prediction,
                PredictionEventType.PREDICTION_CREATED,
                requester.nodeId(),
                contract(project, prediction, requester.nodeId()).encoded(),
                requester);
        PredictionEvent first = service.submit(created);
        assertEquals(first.eventId(), service.submit(CoordinationCommand.decode(created.encoded())).eventId());
        try (CoordinationService.Subscription subscription = service.subscribe(0)) {
            assertEquals(first.eventId(), subscription.take().eventId());
        }
        assertEquals(1, service.replayAfter(0).size());
    }

    @Test
    @SuppressWarnings({"HttpUrlsUsage", "resource"})
    void loopbackHttpAcceptsCommandsAndReplaysSse() throws Exception {
        Path root = Files.createTempDirectory("synesis-http-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity coordinator = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project), coordinator);
        try (CoordinationHttpServer server = new CoordinationHttpServer(service,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(),
                    project,
                    prediction,
                    PredictionEventType.PREDICTION_CREATED,
                    requester.nodeId(),
                    contract(project, prediction, requester.nodeId()).encoded(),
                    requester);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://" + server.address().getHostString() + ":" + server.address().getPort());
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(base.resolve("/command"))
                            .header("Content-Type", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(command.encoded())).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            HttpResponse<String> stream = client.send(HttpRequest.newBuilder(base.resolve("/events?after=0&once=true"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stream.statusCode());
            assertTrue(stream.body().contains("PREDICTION_CREATED"));
            assertTrue(stream.body().contains("id: 1"));
        }
    }

    @Test
    void ownershipNeverTransfersWhenRequesterIsForeign() {
        OwnershipRegistry registry = new OwnershipRegistry();
        CapabilityId capability = new CapabilityId("workspace.prediction-status");
        registry.claim(capability,
                new OwnershipOwner(new NodeId("sl1-owner"), new SupervisorId("sup-b"), new IntentVersion(4)));
        OwnershipDecision decision = registry.evaluate(capability, new NodeId("sl1-requester"));
        assertEquals(OwnershipRegistry.Result.REQUEST_OWNER, decision.result());
        assertEquals("sl1-owner", decision.owner().nodeId());
        assertEquals(OwnershipRegistry.Result.ALLOW, registry.evaluate(capability, new NodeId("sl1-owner")).result());
        assertEquals(decision.result(), registry.evaluate("workspace.prediction-status", "sl1-requester").result());
    }

    @Test
    void twoForegroundSupervisorsCoordinateOverHttpWithoutTransferringOwnership() throws Exception {
        Path root = Files.createTempDirectory("synesis-supervisors-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root.resolve("coordinator"),
                project), NodeIdentity.generate());
        try (CoordinationHttpServer server = new CoordinationHttpServer(service,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.address().getPort());
            CoordinationHttpClient requesterClient = new CoordinationHttpClient(endpoint);
            CoordinationHttpClient ownerClient = new CoordinationHttpClient(endpoint);
            PredictionContract contract = supervisorContract(project, prediction, requester, owner);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.PREDICTION_CREATED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    contract.encoded(),
                    requester);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.PREDICTION_ROUTED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    new byte[0],
                    requester);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.REQUEST_RECEIVED,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.ACCEPTED_EXACT,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            assertEquals(PredictionState.ACCEPTED_EXACT, service.projection().state(prediction).orElseThrow());
            assertEquals(4, service.headSequence());
            assertEquals(4, requesterClient.replayAfter(0).size());
            assertEquals(4, ownerClient.replayAfter(0).size());
        }
    }

    @Test
    void speculationWorktreeIsolatedAndGateFailsOnWhitespace() throws Exception {
        Path repository = Files.createTempDirectory("synesis-git-");
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@synesis.local");
        run(repository, "git", "config", "user.name", "Synesis Test");
        Files.writeString(repository.resolve("README.md"), "base\n");
        run(repository, "git", "add", "README.md");
        run(repository, "git", "commit", "-q", "-m", "base");
        String commit = run(repository, "git", "rev-parse", "HEAD").trim();
        SpeculationWorkspace workspace = new SpeculationWorkspace(repository,
                repository.resolve(".synesis"),
                UUID.randomUUID(),
                commit);
        workspace.create();
        assertTrue(workspace.gate().accepted());
        Files.writeString(workspace.worktree().resolve("README.md"), "bad whitespace \t\n");
        assertFalse(workspace.gate().accepted());
        workspace.close();
    }

    @Test
    void acceptedPredictionRetiresAfterAvailabilityAndValidation() throws Exception {
        Path root = Files.createTempDirectory("synesis-lifecycle-");
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        NodeIdentity requester = NodeIdentity.generate();
        NodeIdentity owner = NodeIdentity.generate();
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project),
                NodeIdentity.generate());
        try (CoordinationHttpServer server = new CoordinationHttpServer(service,
                new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.address().getPort());
            CoordinationHttpClient requesterClient = new CoordinationHttpClient(endpoint);
            CoordinationHttpClient ownerClient = new CoordinationHttpClient(endpoint);
            PredictionContract contract = supervisorContract(project, prediction, requester, owner);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.PREDICTION_CREATED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    contract.encoded(),
                    requester);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.PREDICTION_ROUTED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    new byte[0],
                    requester);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.REQUEST_RECEIVED,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.ACCEPTED_EXACT,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.IMPLEMENTATION_STARTED,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.PATCH_READY,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(ownerClient,
                    project,
                    prediction,
                    PredictionEventType.CAPABILITY_AVAILABLE,
                    new SupervisorId("sup-b"),
                    new WorkerId("worker-b"),
                    new byte[0],
                    owner);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.VALIDATION_STARTED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    new byte[0],
                    requester);
            submitAs(requesterClient,
                    project,
                    prediction,
                    PredictionEventType.SPECULATION_RETIRED,
                    new SupervisorId("sup-a"),
                    new WorkerId("worker-a"),
                    new byte[0],
                    requester);
            assertEquals(PredictionState.RETIRED, service.projection().state(prediction).orElseThrow());
            assertEquals(9, service.headSequence());
            assertEquals(9, requesterClient.replayAfter(0).size());
            assertEquals(9, ownerClient.replayAfter(0).size());
        }
    }
}
