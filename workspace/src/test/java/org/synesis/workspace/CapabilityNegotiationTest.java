package org.synesis.workspace;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractChangeRequest;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.capability.ContractConsumerService;
import org.synesis.workspace.application.capability.ContractOwnerService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ImplementationPublicationService;
import org.synesis.workspace.application.integration.ImplementationValidationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * Exercises the one public contract per owned file: publishing it, binding to it, asking for a change
 * to it, and what a revision, a cancellation and a finish do to the bindings (ADR-0092).
 */
class CapabilityNegotiationTest {

    private static final String CAPABILITY = "catalog.product-query";
    private static final ResourceSelector PRODUCT_QUERY = ResourceSelector.pathExact("src/ProductQuery.java");
    private static final String REPORT_CAPABILITY = "catalog.product-report";
    private static final ResourceSelector PRODUCT_REPORT = ResourceSelector.pathExact("src/ProductReport.java");

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private String configuredGrace;
    private ProjectApplicationService projectService;
    private ProviderSessionBindingService bindingService;
    private ContractConsumerService requestService;
    private ContractOwnerService ownerService;
    private AgentNextActionService nextActionService;

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> call : calls) {
                pending.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : pending) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Replays the durable log from disk, which fails if conflicting events were ever appended. */
    private PredictionEventStore replayedStore() throws Exception {
        var location = projectService.locate(projectRoot);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private CapabilityRequestRecord replayedRequest(String handle) throws Exception {
        return replayedStore().capabilityRequestProjection().findByHandle(handle).orElseThrow();
    }

    private static CapabilityContract contract(String... behavior) {
        return new CapabilityContract("UUID productId", "Optional<Product>", List.of(behavior), List.of("existing product returned"));
    }

    private static Map<?, ?> result(AgentResponse response) {
        return (Map<?, ?>) response.result();
    }

    private void session(String provider, String instance) throws Exception {
        var location = projectService.locate(projectRoot);
        new AgentSessionService().ensureSession(new SessionResolutionRequest(projectRoot, provider, instance, null, false));
        var binding = bindingService.find(location, provider, instance).orElseThrow();
        if (binding.worktreePath() != null) {
            bindingService.verifyWorkspaceTrust(location, provider, binding.sessionId(), Path.of(binding.worktreePath()));
        }
    }

    /** The consumer (claude, inst-1) and the owner (codex, inst-2) each claim one file. */
    private WorkIntent announceBoth() throws Exception {
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var consumer = collaboration.announce(projectRoot, "claude", "inst-1", "Implement the product CLI", "Use the published product query API",
                List.of(ResourceSelector.pathExact("src/ProductCli.java")), null, WorkIntent.Role.PRODUCER, List.of(), List.of(CAPABILITY));
        collaboration.announce(projectRoot, "codex", "inst-2", "Implement the product query API", "Publish the capability implementation", List.of(PRODUCT_QUERY),
                consumer.intent().workGroupId());
        return consumer.intent();
    }

    /** A second consumer lane (codex, inst-3) in the same work group. */
    private void announceSecondConsumer(UUID workGroupId) throws Exception {
        session("codex", "inst-3");
        new WorkspaceCollaborationService().announce(projectRoot, "codex", "inst-3", "Implement the product report", "Use the published product query API",
                List.of(ResourceSelector.pathExact("src/ProductReport.java")), workGroupId, WorkIntent.Role.PRODUCER, List.of(), List.of(CAPABILITY));
    }

    private AgentResponse publish(CapabilityContract terms, UUID... answers) {
        return ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-2", List.of(PRODUCT_QUERY), terms, List.of(answers)));
    }

    private AgentResponse bind(String provider, String instance, long revision) {
        return requestService.bind(new ContractConsumerService.BindRequest(projectRoot, provider, instance, CAPABILITY, PRODUCT_QUERY, revision));
    }

    private String boundHandle(AgentResponse bound) {
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        return (String) result(bound).get("capabilityRequestHandle");
    }

    private AgentResponse requestChange(String provider, String instance, String need) {
        return requestService.requestChange(new ContractConsumerService.ChangeRequest(projectRoot, provider, instance, PRODUCT_QUERY, need));
    }

    private AgentResponse next(String provider, String instance) {
        return nextActionService.getNextAction(new NextActionRequest(projectRoot, provider, instance));
    }

    private AgentResponse publishImplementation() throws Exception {
        Path ownerWorktree = Path.of(bindingService.find(projectService.locate(projectRoot), "codex", "inst-2").orElseThrow().worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/ProductQuery.java"), "final class ProductQuery {}\n");
        AgentResponse readiness = nextActionService.getNextAction(new NextActionRequest(projectRoot, "codex", "inst-2", true));
        Map<?, ?> arguments = (Map<?, ?>) ((Map<?, ?>) result(readiness).get("workflow")).get("arguments");
        AgentResponse published = new ImplementationPublicationService().publishImplementation(new ImplementationPublicationService.PublishRequest(projectRoot, "codex", "inst-2",
                (String) arguments.get("capabilityRequestHandle"), "Publish the product query", ((Number) arguments.get("contractRevision")).longValue(),
                UUID.fromString((String) arguments.get("intentId")), ((Number) arguments.get("claimEpoch")).longValue(), (String) arguments.get("expectedTreeHash")));
        assertEquals(AgentStatus.WAITING, published.status(), published.toJson());
        return published;
    }

    private AgentResponse validate(String provider, String instance, String handle, String verdict, int implementationRevision) {
        return new ImplementationValidationService().validateImplementation(new ImplementationValidationService.ValidateRequest(projectRoot, provider, instance, handle, verdict, null,
                implementationRevision, List.of()));
    }

    private AgentResponse cancel(String provider, String instance) {
        return new AgentTaskCancellationService().cancelTask(new AgentTaskCancellationService.CancelTaskRequest(projectRoot, provider, instance, "No longer needed."));
    }

    @BeforeEach
    void rememberGrace() {
        configuredGrace = System.getProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
    }

    @AfterEach
    void restoreGrace() {
        if (configuredGrace == null) {
            System.clearProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
        } else {
            System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, configuredGrace);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("test-project");
        Files.createDirectories(projectRoot);

        git(projectRoot, "init");
        git(projectRoot, "config", "user.name", "Test User");
        git(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Test Project\n");
        git(projectRoot, "add", ".");
        git(projectRoot, "commit", "-m", "Initial commit");

        projectService = new ProjectApplicationService();
        projectService.init(projectRoot);
        ProviderTestSupport.install(projectService.locate(projectRoot), "claude");
        ProviderTestSupport.install(projectService.locate(projectRoot), "codex");

        bindingService = new ProviderSessionBindingService();
        requestService = new ContractConsumerService();
        ownerService = new ContractOwnerService();
        nextActionService = new AgentNextActionService();

        session("claude", "inst-1");
        session("codex", "inst-2");
    }

    @Test
    void completeContractFlowFromPublicationToAcceptedBinding() throws Exception {
        announceBoth();

        AgentResponse before = next("claude", "inst-1");
        assertEquals(AgentStatus.NEEDS_CAPABILITY, before.status(), before.toJson());
        assertEquals("contract_bind", result(before).get("coordinationKind"));
        assertTrue(((List<?>) result(before).get("contracts")).isEmpty(), before.toJson());

        AgentResponse published = publish(contract("Return exact matching product", "Return empty when missing"));
        assertEquals(AgentStatus.COMPLETED, published.status(), published.toJson());
        assertEquals(1L, ((Number) result(published).get("revision")).longValue());

        AgentResponse shown = next("claude", "inst-1");
        assertEquals(AgentStatus.NEEDS_CAPABILITY, shown.status(), shown.toJson());
        List<?> contracts = (List<?>) result(shown).get("contracts");
        assertEquals(1, contracts.size(), shown.toJson());
        assertEquals(List.of("src/ProductQuery.java"), ((Map<?, ?>) contracts.getFirst()).get("paths"));

        AgentResponse bound = bind("claude", "inst-1", 1);
        String handle = boundHandle(bound);
        assertTrue(handle.startsWith("req_"));
        assertEquals(Boolean.TRUE, result(bound).get("contractAccepted"), bound.toJson());
        assertEquals(CapabilityLifecycleState.ACCEPTED, replayedRequest(handle).state());

        AgentResponse consumerNext = next("claude", "inst-1");
        assertEquals(AgentStatus.READY, consumerNext.status(), consumerNext.toJson());
        assertNull(consumerNext.nextAction());
        assertEquals(Boolean.TRUE, result(consumerNext).get("contractAccepted"), consumerNext.toJson());
        assertFalse(result(consumerNext).containsKey("implementationPending"), consumerNext.toJson());

        AgentResponse ownerNext = next("codex", "inst-2");
        assertEquals(handle, result(ownerNext).get("capabilityRequestHandle"), ownerNext.toJson());
        assertEquals(Boolean.TRUE, result(ownerNext).get("implementationPending"), ownerNext.toJson());
    }

    @Test
    void publishingNeedsAClaimOnEveryPath() throws Exception {
        announceBoth();

        AgentResponse foreign = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-2",
                List.of(PRODUCT_QUERY, ResourceSelector.pathExact("src/ProductCli.java")), contract("anything"), List.of()));

        assertEquals(AgentStatus.BLOCKED, foreign.status(), foreign.toJson());
        assertEquals(AgentReason.POLICY_DENIED, foreign.reason(), foreign.toJson());
        assertEquals("CONTRACT_PATH_NOT_CLAIMED", result(foreign).get("reason"));
        assertTrue(replayedStore().contractProjection().contracts().isEmpty(), "a refused publication must leave no contract behind");

        AgentResponse consumerPublishes = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "claude", "inst-1", List.of(PRODUCT_QUERY), contract("anything"), List.of()));
        assertEquals("CONTRACT_PATH_NOT_CLAIMED", result(consumerPublishes).get("reason"), consumerPublishes.toJson());
        assertTrue(replayedStore().contractProjection().contracts().isEmpty());
    }

    @Test
    void publishingAgainWithTheSameTermsRecordsNoNewRevision() throws Exception {
        announceBoth();
        CapabilityContract terms = contract("Return exact matching product");
        publish(terms);

        AgentResponse again = publish(terms);

        assertEquals(AgentStatus.COMPLETED, again.status(), again.toJson());
        assertEquals(Boolean.FALSE, result(again).get("published"), again.toJson());
        assertEquals(1, replayedStore().contractProjection().contracts().getFirst().revision());
    }

    @Test
    void bindingBeforeAContractExistsAsksForOneAndRecordsNothing() throws Exception {
        announceBoth();

        AgentResponse unbound = bind("claude", "inst-1", 1);

        assertEquals(AgentStatus.NEEDS_CAPABILITY, unbound.status(), unbound.toJson());
        assertEquals(AgentReason.OWNER_REQUIRED, unbound.reason(), unbound.toJson());
        assertEquals("CONTRACT_NOT_PUBLISHED", result(unbound).get("reason"));
        assertTrue(replayedStore().capabilityRequestProjection().records().isEmpty());
    }

    @Test
    void bindingToAStaleRevisionIsRefusedWithTheCurrentContract() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        publish(contract("Return exact matching product", "Reject null input"));

        AgentResponse stale = bind("claude", "inst-1", 1);

        assertEquals(AgentStatus.BLOCKED, stale.status(), stale.toJson());
        assertEquals(AgentReason.STALE_REQUEST, stale.reason(), stale.toJson());
        assertEquals(2L, ((Number) result(stale).get("revision")).longValue(), "the refusal shows the contract to bind to");
        assertTrue(replayedStore().capabilityRequestProjection().records().isEmpty());
        assertEquals(AgentStatus.READY, bind("claude", "inst-1", 2).status());
    }

    /**
     * A file no other lane claims has no owner to bind to. The file decides who owns it: a lane that
     * claims it is the owner whatever the caller believes, and the caller's own file is never one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBindNamingAFileGoesToTheLaneThatClaimsIt() throws Exception {
        var location = projectService.locate(projectRoot);
        session("codex", "inst-3");
        var third = bindingService.find(location, "codex", "inst-3").orElseThrow();
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "codex", "inst-2", "Write the usage notes", "Notes cover every subcommand", List.of(ResourceSelector.pathExact("docs/USAGE.md")));
        collaboration.announce(projectRoot, "codex", "inst-3", "Implement the store", "Store passes its tests", List.of(ResourceSelector.pathExact("src/store.py")));
        collaboration.announce(projectRoot, "claude", "inst-1", "Implement the command line", "Command line passes its tests", List.of(ResourceSelector.pathExact("src/cli.py")));

        AgentResponse unclaimed = requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "claude", "inst-1", "store.api", ResourceSelector.pathExact("src/export.py"), 1));
        assertEquals(AgentReason.OWNER_REQUIRED, unclaimed.reason(), unclaimed.toJson());
        assertEquals(List.of(List.of("docs/USAGE.md"), List.of("src/store.py")), ((List<Map<String, Object>>) result(unclaimed).get("lanes")).stream().map(lane -> lane.get("claims")).toList());
        AgentResponse ownFile = requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "claude", "inst-1", "store.api", ResourceSelector.pathExact("src/cli.py"), 1));
        assertEquals(AgentReason.OWNER_REQUIRED, ownFile.reason(), ownFile.toJson());
        assertTrue(replayedStore().capabilityRequestProjection().records().isEmpty());

        ResourceSelector store = ResourceSelector.pathExact("src/store.py");
        AgentResponse published = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-3", List.of(store), contract("keeps tasks across runs"), List.of()));
        assertEquals(AgentStatus.COMPLETED, published.status(), published.toJson());
        AgentResponse bound = requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "claude", "inst-1", "store.api", store, 1));
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        assertEquals(List.of("src/store.py"), ((Map<String, Object>) result(bound).get("owner")).get("claims"));
        CapabilityRequestRecord request = replayedRequest((String) result(bound).get("capabilityRequestHandle"));
        PredictionEventStore replayed = replayedStore();
        String thirdParticipant = WorkspaceCollaborationService.participantHandle(third.sessionId());
        assertEquals(replayed.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(thirdParticipant)).findFirst().orElseThrow().authorityLineageId(),
                request.authorityLineageId());
        assertEquals(third.workerId(), request.ownerWorkerId());
    }

    @Test
    void concurrentEquivalentBindsRecordOneBinding() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));

        // Separate service instances stand in for separate MCP processes sharing one project.
        List<Callable<AgentResponse>> calls = new ArrayList<>();
        for (int caller = 0; caller < 4; caller++) {
            calls.add(() -> new ContractConsumerService().bind(new ContractConsumerService.BindRequest(projectRoot, "claude", "inst-1", CAPABILITY, PRODUCT_QUERY, 1)));
        }
        List<AgentResponse> responses = concurrently(calls);

        for (AgentResponse response : responses) {
            assertEquals(AgentStatus.READY, response.status(), response.toJson());
        }
        Set<Object> handles = responses.stream().map(response -> result(response).get("capabilityRequestHandle")).collect(Collectors.toSet());
        assertEquals(1, handles.size(), "every caller must observe the single durable binding");
        PredictionEventStore replayed = replayedStore();
        assertEquals(1, replayed.capabilityRequestProjection().records().size());
        assertEquals(1, replayed.contractProjection().bindings(replayed.contractProjection().contracts().getFirst().contractId()).size());
    }

    @Test
    void siblingSessionWithoutTheFileCannotPublishOrAnswer() throws Exception {
        announceBoth();
        // A second codex connection shares the project node identity. Only the claims of its own lane separate it from the owner.
        session("codex", "inst-3");
        new WorkspaceCollaborationService().announce(projectRoot, "codex", "inst-3", "Write the usage notes", "Notes cover every subcommand", List.of(ResourceSelector.pathExact("docs/USAGE.md")));
        String need = "Return empty when the product is missing";
        AgentResponse filed = requestChange("claude", "inst-1", need);
        String changeRequestId = (String) result(filed).get("changeRequest");

        AgentResponse publishes = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-3", List.of(PRODUCT_QUERY), contract("anything"), List.of()));
        assertEquals("CONTRACT_PATH_NOT_CLAIMED", result(publishes).get("reason"), publishes.toJson());
        AgentResponse answers = ownerService.answerChange(new ContractOwnerService.AnswerRequest(projectRoot, "codex", "inst-3", UUID.fromString(changeRequestId), false, "not mine"));
        assertEquals("CONTRACT_CHANGE_REQUEST_NOT_YOURS", result(answers).get("reason"), answers.toJson());
        assertEquals(ContractChangeRequest.State.OPEN, replayedStore().contractProjection().changeRequest(UUID.fromString(changeRequestId)).orElseThrow().state());

        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, next("codex", "inst-3").nextAction());
        AgentResponse ownerNext = next("codex", "inst-2");
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
    }

    /** The run that ended the old design: two consumers needing opposite things of one module. */
    @Test
    void opposingChangeRequestsAreShownToTheOwnerTogetherAndADeclineReachesItsConsumer() throws Exception {
        WorkIntent consumer = announceBoth();
        announceSecondConsumer(consumer.workGroupId());
        publish(contract("Return exact matching product"));

        requestChange("claude", "inst-1", "An unknown product id must raise NotFoundError");
        requestChange("codex", "inst-3", "An unknown product id must return an empty result and never raise");

        AgentResponse ownerNext = next("codex", "inst-2");
        assertEquals(AgentStatus.READY, ownerNext.status(), ownerNext.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, ownerNext.reason(), ownerNext.toJson());
        assertEquals(AgentNextAction.RESPOND_COORDINATION, ownerNext.nextAction(), ownerNext.toJson());
        List<?> shown = (List<?>) result(ownerNext).get("changeRequests");
        assertEquals(2, shown.size(), ownerNext.toJson());
        assertEquals(1, ((List<?>) result(ownerNext).get("yourContracts")).size(), ownerNext.toJson());
        Map<?, ?> raises = shown.stream().map(Map.class::cast).filter(item -> String.valueOf(item.get("need")).contains("NotFoundError")).findFirst().orElseThrow();
        Map<?, ?> empty = shown.stream().map(Map.class::cast).filter(item -> String.valueOf(item.get("need")).contains("never raise")).findFirst().orElseThrow();

        AgentResponse withoutReason = ownerService.answerChange(new ContractOwnerService.AnswerRequest(projectRoot, "codex", "inst-2", UUID.fromString((String) empty.get("changeRequest")), false, " "));
        assertEquals("CONTRACT_CHANGE_DECLINED_WITHOUT_REASON", result(withoutReason).get("reason"), withoutReason.toJson());
        AgentResponse accepted = ownerService.answerChange(new ContractOwnerService.AnswerRequest(projectRoot, "codex", "inst-2", UUID.fromString((String) empty.get("changeRequest")), true, null));
        assertEquals(AgentStatus.COMPLETED, accepted.status(), accepted.toJson());
        assertEquals("ACCEPTED", result(accepted).get("state"));

        AgentResponse declined = ownerService.answerChange(new ContractOwnerService.AnswerRequest(projectRoot, "codex", "inst-2", UUID.fromString((String) raises.get("changeRequest")), false,
                "A missing product is an empty result everywhere else, so it cannot raise here"));
        assertEquals(AgentStatus.COMPLETED, declined.status(), declined.toJson());
        assertEquals("DECLINED", result(declined).get("state"));

        AgentResponse consumerNext = next("claude", "inst-1");
        assertEquals(AgentStatus.NEEDS_CAPABILITY, consumerNext.status(), consumerNext.toJson());
        List<?> filed = (List<?>) result(consumerNext).get("yourChangeRequests");
        assertEquals(1, filed.size(), consumerNext.toJson());
        assertEquals("DECLINED", ((Map<?, ?>) filed.getFirst()).get("state"));
        assertEquals("A missing product is an empty result everywhere else, so it cannot raise here", ((Map<?, ?>) filed.getFirst()).get("reason"));
        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, consumerNext.nextAction());

        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, next("codex", "inst-2").nextAction(), "both requests are answered");
        assertEquals(AgentStatus.READY, bind("claude", "inst-1", 1).status(), "a declined consumer may bind anyway");
    }

    @Test
    void aRevisionAnswersTheChangeRequestsNamedInIt() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        requestChange("claude", "inst-1", "Reject null input");
        UUID id = UUID.fromString((String) ((Map<?, ?>) ((List<?>) result(next("codex", "inst-2")).get("changeRequests")).getFirst()).get("changeRequest"));

        AgentResponse revised = publish(contract("Return exact matching product", "Reject null input"), id);

        assertEquals(AgentStatus.COMPLETED, revised.status(), revised.toJson());
        assertEquals(List.of(id.toString()), result(revised).get("changeRequestsAnswered"));
        ContractChangeRequest answered = replayedStore().contractProjection().changeRequest(id).orElseThrow();
        assertEquals(ContractChangeRequest.State.ACCEPTED, answered.state());
        assertEquals(2, answered.answeredByRevision());
    }

    @Test
    void aNewRevisionMovesEachBindingToRevisionRequestedEndsItsValidationAndBindingAgainAcceptsIt() throws Exception {
        WorkIntent consumer = announceBoth();
        announceSecondConsumer(consumer.workGroupId());
        publish(contract("Return exact matching product"));
        String first = boundHandle(bind("claude", "inst-1", 1));
        String second = boundHandle(requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "codex", "inst-3", CAPABILITY, PRODUCT_QUERY, 1)));
        publishImplementation();
        assertEquals(AgentStatus.READY, validate("claude", "inst-1", first, "inspect", 1).status());
        assertTrue(replayedStore().capabilityRequestProjection().findValidationContext(first).isPresent(), "a validation is in progress");
        assertEquals(CapabilityLifecycleState.VALIDATING, replayedRequest(first).state());
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, replayedRequest(second).state());

        AgentResponse revised = publish(contract("Return exact matching product", "Reject null input"));

        assertEquals(2, ((Number) result(revised).get("bindingsToMakeAgain")).intValue(), revised.toJson());
        PredictionEventStore replayed = replayedStore();
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayed.capabilityRequestProjection().findByHandle(first).orElseThrow().state());
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayed.capabilityRequestProjection().findByHandle(second).orElseThrow().state());
        assertTrue(replayed.capabilityRequestProjection().findValidationContext(first).isEmpty(), "what was being validated under the earlier revision no longer counts");
        assertEquals(AgentStatus.BLOCKED, validate("claude", "inst-1", first, "accepted", 1).status(), "a verdict under the earlier revision is not accepted");

        AgentResponse stale = next("claude", "inst-1");
        assertEquals(AgentReason.REVISION_REQUIRED, stale.reason(), stale.toJson());
        assertEquals(AgentNextAction.REVISE_CAPABILITY_REQUEST, stale.nextAction(), stale.toJson());
        assertFalse(result(stale).containsKey("acceptRevision"), stale.toJson());
        Map<?, ?> bindAgain = (Map<?, ?>) result(stale).get("bindAgain");
        Map<?, ?> arguments = (Map<?, ?>) bindAgain.get("arguments");
        assertEquals("contract_bind", arguments.get("kind"));
        Map<?, ?> payload = (Map<?, ?>) arguments.get("payload");
        assertEquals(2L, ((Number) payload.get("revision")).longValue());
        assertEquals("src/ProductQuery.java", payload.get("ownerPath"));

        AgentResponse staleBind = bind("claude", "inst-1", 1);
        assertEquals(AgentStatus.BLOCKED, staleBind.status(), staleBind.toJson());
        assertEquals(AgentReason.STALE_REQUEST, staleBind.reason(), staleBind.toJson());
        AgentResponse again = bind("claude", "inst-1", 2);
        assertEquals(first, boundHandle(again), "binding again keeps the request record");
        assertEquals(CapabilityLifecycleState.ACCEPTED, replayedRequest(first).state());
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(second).state(), "each consumer binds for itself");
        assertEquals(2, replayedStore().capabilityRequestProjection().records().size(), "binding again records no new request");
    }

    @Test
    void anUnchangedCommitCanBePublishedAgainForANewRevisionAndIsValidatedAnew() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        String handle = boundHandle(bind("claude", "inst-1", 1));
        publishImplementation();
        var firstRevision = replayedStore().capabilityRequestProjection().findLatestImplementation(handle).orElseThrow();
        assertEquals(AgentStatus.READY, validate("claude", "inst-1", handle, "inspect", firstRevision.revisionNumber()).status());
        assertEquals(AgentStatus.COMPLETED, validate("claude", "inst-1", handle, "accepted", firstRevision.revisionNumber()).status());
        assertEquals(CapabilityLifecycleState.VALIDATED, replayedRequest(handle).state());

        publish(contract("Return exact matching product", "Reject null input"));
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(handle).state(), "a verdict under the earlier revision no longer counts");
        assertEquals(AgentStatus.READY, bind("claude", "inst-1", 2).status());
        publishImplementation();

        var secondRevision = replayedStore().capabilityRequestProjection().findLatestImplementation(handle).orElseThrow();
        assertEquals(firstRevision.revisionNumber() + 1, secondRevision.revisionNumber());
        assertEquals(firstRevision.commitSha(), secondRevision.commitSha(), "the owner changed nothing, so it is the same commit");
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, replayedRequest(handle).state());
        assertEquals(AgentStatus.READY, validate("claude", "inst-1", handle, "inspect", secondRevision.revisionNumber()).status());
        assertEquals(AgentStatus.COMPLETED, validate("claude", "inst-1", handle, "accepted", secondRevision.revisionNumber()).status());
        assertEquals(CapabilityLifecycleState.VALIDATED, replayedRequest(handle).state());
    }

    @Test
    void cancellingAConsumerLaneSupersedesItsBindingBeforeAndAfterPublication() throws Exception {
        WorkIntent consumer = announceBoth();
        announceSecondConsumer(consumer.workGroupId());
        publish(contract("Return exact matching product"));
        String early = boundHandle(bind("claude", "inst-1", 1));
        assertEquals(CapabilityLifecycleState.ACCEPTED, replayedRequest(early).state());

        AgentResponse cancelled = cancel("claude", "inst-1");

        assertEquals(AgentStatus.COMPLETED, cancelled.status(), cancelled.toJson());
        assertEquals(CapabilityLifecycleState.SUPERSEDED, replayedRequest(early).state());

        String late = boundHandle(requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "codex", "inst-3", CAPABILITY, PRODUCT_QUERY, 1)));
        publishImplementation();
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, replayedRequest(late).state());

        AgentResponse cancelledLate = cancel("codex", "inst-3");

        assertEquals(AgentStatus.COMPLETED, cancelledLate.status(), cancelledLate.toJson());
        assertEquals(CapabilityLifecycleState.SUPERSEDED, replayedRequest(late).state());
    }

    @Test
    void anOwnerWithAnOpenChangeRequestIsGivenItWhenAskingToFinish() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        requestChange("claude", "inst-1", "Reject null input");
        Path ownerWorktree = Path.of(bindingService.find(projectService.locate(projectRoot), "codex", "inst-2").orElseThrow().worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/ProductQuery.java"), "final class ProductQuery {}\n");

        AgentResponse completion = nextActionService.getNextAction(new NextActionRequest(projectRoot, "codex", "inst-2", true));

        assertEquals(AgentNextAction.RESPOND_COORDINATION, completion.nextAction(), completion.toJson());
        assertEquals(1, ((List<?>) result(completion).get("changeRequests")).size(), completion.toJson());

        AgentResponse consumerCompletion = nextActionService.getNextAction(new NextActionRequest(projectRoot, "claude", "inst-1", true));
        assertNotEquals(AgentNextAction.RESPOND_COORDINATION, consumerCompletion.nextAction(), "the consumer owes nobody an answer: " + consumerCompletion.toJson());
    }

    @Test
    void anOwnerCannotFinishWithAChangeRequestOpenUntilItAnswersIt() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        requestChange("claude", "inst-1", "Reject null input");
        UUID id = UUID.fromString((String) ((Map<?, ?>) ((List<?>) result(next("codex", "inst-2")).get("changeRequests")).getFirst()).get("changeRequest"));
        Path ownerWorktree = Path.of(bindingService.find(projectService.locate(projectRoot), "codex", "inst-2").orElseThrow().worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/ProductQuery.java"), "final class ProductQuery {}\n");

        AgentResponse refused = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, "codex", "inst-2", "Implement the product query API"));

        assertEquals(AgentStatus.BLOCKED, refused.status(), refused.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, refused.reason(), refused.toJson());
        assertEquals("CONTRACT_CHANGE_REQUEST_OPEN", result(refused).get("reason"), refused.toJson());

        ownerService.answerChange(new ContractOwnerService.AnswerRequest(projectRoot, "codex", "inst-2", id, false, "The contract stays as it is."));
        AgentResponse afterAnswer = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, "codex", "inst-2", "Implement the product query API"));
        assertNotEquals("CONTRACT_CHANGE_REQUEST_OPEN", String.valueOf(result(afterAnswer) == null ? null : result(afterAnswer).get("reason")), afterAnswer.toJson());
    }

    @Test
    void changeRequestsAreRecordedOncePerNeedAndSurviveAReplay() throws Exception {
        announceBoth();

        AgentResponse first = requestChange("claude", "inst-1", "Reject null input");
        AgentResponse again = requestChange("claude", "inst-1", "Reject null input");

        assertEquals(AgentStatus.WAITING, first.status(), first.toJson());
        assertEquals(AgentReason.OWNER_RESPONSE_PENDING, first.reason(), first.toJson());
        assertEquals(result(first).get("changeRequest"), result(again).get("changeRequest"), "the same need from the same lane is one request");
        long events = replayedStore().events().stream().filter(event -> event.type() == PredictionEventType.CONTRACT_CHANGE_REQUESTED).count();
        assertEquals(1, events);
    }

    /** The owner (codex, inst-2) writes its file and asks to finish; with nothing outstanding this integrates it. */
    private AgentResponse ownerFinishes() throws Exception {
        Path ownerWorktree = Path.of(bindingService.find(projectService.locate(projectRoot), "codex", "inst-2").orElseThrow().worktreePath());
        Files.createDirectories(ownerWorktree.resolve("src"));
        Files.writeString(ownerWorktree.resolve("src/ProductQuery.java"), "final class ProductQuery {}\n");
        return new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, "codex", "inst-2", "Implement the product query API"));
    }

    /** The control branch moved when the owner was integrated, so the consumer's session is refreshed first, as an agent is told to. */
    private AgentResponse nextAfterRefresh(String provider, String instance) throws Exception {
        new AgentSessionService().ensureSession(new SessionResolutionRequest(projectRoot, provider, instance, null, false));
        return next(provider, instance);
    }

    private AgentResponse ownerAsksToFinish() {
        return nextActionService.getNextAction(new NextActionRequest(projectRoot, "codex", "inst-2", true));
    }

    private Optional<OperatorRequest> absentConsumerRequest() throws Exception {
        return replayedStore().operatorRequestProjection().open(OperatorRequest.BOUND_CONSUMER_ABSENT);
    }

    /** A consumer bound to a published implementation it has not checked, with the owner waiting for it. */
    private String boundConsumerOwingAVerdict() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        String handle = boundHandle(bind("claude", "inst-1", 1));
        publishImplementation();
        assertEquals(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, replayedRequest(handle).state());
        return handle;
    }

    @Test
    void aConsumerArrivingAfterTheOwnersWorkWasIntegratedIsLetThroughWhileAnotherLaneIsStillOpen() throws Exception {
        WorkIntent consumer = announceBoth();
        announceSecondConsumer(consumer.workGroupId());
        publish(contract("Return exact matching product"));
        AgentResponse finished = ownerFinishes();
        assertEquals("integrated", ((Map<?, ?>) result(finished).get("completion")).get("task"), "the other lanes are still open, so the answer waits: " + finished.toJson());
        PredictionEventStore afterOwner = replayedStore();
        assertTrue(afterOwner.taskCompletionProjection().allSnapshots().stream()
                .anyMatch(snapshot -> afterOwner.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(null) == TaskCompletionState.INTEGRATED), "the owner's work is in");
        assertEquals("FROZEN", afterOwner.contractProjection().contracts().getFirst().state().name());
        assertEquals(2, afterOwner.collaborationProjection().activeIntents().size(), "the consumer and the third lane are still open");

        AgentResponse before = nextAfterRefresh("claude", "inst-1");
        assertEquals(AgentStatus.NEEDS_CAPABILITY, before.status(), before.toJson());
        List<?> listed = (List<?>) result(before).get("contracts");
        assertEquals(1, listed.size(), before.toJson());
        assertEquals("FROZEN", ((Map<?, ?>) listed.getFirst()).get("state"), "a frozen contract is listed to a late consumer");

        AgentResponse stale = bind("claude", "inst-1", 2);
        assertEquals(AgentStatus.BLOCKED, stale.status(), stale.toJson());
        assertEquals(AgentReason.STALE_REQUEST, stale.reason(), stale.toJson());
        assertEquals(1L, ((Number) result(stale).get("revision")).longValue(), "the refusal shows the frozen contract");
        assertTrue(replayedStore().capabilityRequestProjection().records().isEmpty(), "a stale bind records nothing");

        AgentResponse change = requestChange("claude", "inst-1", "Reject null input");
        assertEquals(AgentStatus.BLOCKED, change.status(), change.toJson());
        assertEquals(AgentReason.POLICY_DENIED, change.reason(), change.toJson());
        assertEquals("CONTRACT_OWNER_FINISHED", result(change).get("reason"), change.toJson());
        assertEquals(1L, ((Number) result(change).get("revision")).longValue(), change.toJson());
        assertTrue(replayedStore().events().stream().noneMatch(event -> event.type() == PredictionEventType.CONTRACT_CHANGE_REQUESTED), "nobody is left to answer it");

        AgentResponse bound = bind("claude", "inst-1", 1);
        assertEquals(AgentStatus.READY, bound.status(), bound.toJson());
        assertEquals("CAPABILITY_OWNER_INTEGRATED", result(bound).get("reason"), bound.toJson());
        assertNotNull(result(bound).get("instruction"));
        List<CapabilityRequestRecord> records = List.copyOf(replayedStore().capabilityRequestProjection().records().values());
        assertEquals(1, records.size());
        assertEquals(CapabilityLifecycleState.SUPERSEDED, records.getFirst().state());
        assertTrue(records.getFirst().ownerFinishedWithoutAnswer(), "the dependency is closed as the owner finishing without an answer");

        assertEquals(AgentStatus.READY, bind("claude", "inst-1", 1).status());
        assertEquals(1, replayedStore().capabilityRequestProjection().records().size(), "a second identical bind records nothing new");

        AgentResponse after = next("claude", "inst-1");
        assertNotEquals(AgentStatus.NEEDS_CAPABILITY, after.status(), after.toJson());
        assertNotEquals(AgentReason.OWNER_REQUIRED, after.reason(), after.toJson());

        Path consumerWorktree = Path.of(bindingService.find(projectService.locate(projectRoot), "claude", "inst-1").orElseThrow().worktreePath());
        Files.createDirectories(consumerWorktree.resolve("src"));
        Files.writeString(consumerWorktree.resolve("src/ProductCli.java"), "final class ProductCli {}\n");
        AgentResponse consumerFinished = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, "claude", "inst-1", "Implement the product CLI"));
        assertEquals("integrated", ((Map<?, ?>) result(consumerFinished).get("completion")).get("task"), "the dependency no longer holds the finish: " + consumerFinished.toJson());
        PredictionEventStore done = replayedStore();
        assertEquals(2, done.events().stream().filter(event -> event.type() == PredictionEventType.TASK_INTEGRATED).count());
        assertEquals(1, done.collaborationProjection().activeIntents().size(), "only the third lane is left");
    }

    @Test
    void anAbsentBoundConsumerIsPutToTheOperatorAndHoldsTheOwnerUntilItActs() throws Exception {
        String handle = boundConsumerOwingAVerdict();
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");

        AgentResponse raising = ownerAsksToFinish();
        OperatorRequest raised = absentConsumerRequest().orElseThrow(() -> new AssertionError("no operator request was raised: " + raising.toJson()));
        AgentResponse owner = ownerAsksToFinish();
        assertTrue(raised.evidence().contains(handle), raised.evidence());
        assertTrue(raised.evidence().contains(CAPABILITY), raised.evidence());
        assertEquals(1, raised.evidence().lines().count(), "one line per binding");
        assertEquals(AgentStatus.BLOCKED, owner.status(), owner.toJson());
        assertEquals(AgentReason.OPERATOR_REQUIRED, owner.reason(), owner.toJson());
        assertEquals(raised.id(), ((Map<?, ?>) result(owner).get("operatorRequest")).get("id"), owner.toJson());

        AgentResponse again = ownerAsksToFinish();
        assertEquals(AgentReason.OPERATOR_REQUIRED, again.reason(), again.toJson());
        assertEquals(1, replayedStore().operatorRequestProjection().all().stream().filter(request -> request.condition().equals(OperatorRequest.BOUND_CONSUMER_ABSENT)).count(),
                "one request however often the pump runs");

        assertEquals(AgentStatus.READY, validate("claude", "inst-1", handle, "inspect", 1).status());
        assertEquals(AgentStatus.COMPLETED, validate("claude", "inst-1", handle, "accepted", 1).status());
        AgentResponse afterVerdict = ownerAsksToFinish();

        assertTrue(absentConsumerRequest().isEmpty(), "the consumer acted, so the request closes");
        assertNotEquals(AgentReason.OPERATOR_REQUIRED, afterVerdict.reason(), afterVerdict.toJson());
    }

    /** The pass that raises the request is the pass whose answer must already tell the owner to stop polling. */
    @Test
    void theOwnerIsToldToStopInTheSamePollThatRaisesTheRequest() throws Exception {
        boundConsumerOwingAVerdict();
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");

        AgentResponse owner = ownerAsksToFinish();

        assertTrue(absentConsumerRequest().isPresent());
        assertEquals(AgentStatus.BLOCKED, owner.status(), owner.toJson());
        assertEquals(AgentReason.OPERATOR_REQUIRED, owner.reason(), owner.toJson());
    }

    @Test
    void cancellingTheAbsentConsumersLaneClosesTheOperatorRequest() throws Exception {
        boundConsumerOwingAVerdict();
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");
        ownerAsksToFinish();
        assertTrue(absentConsumerRequest().isPresent());

        assertEquals(AgentStatus.COMPLETED, cancel("claude", "inst-1").status());
        ownerAsksToFinish();

        assertTrue(absentConsumerRequest().isEmpty(), "no binding is left to wait for");
    }

    @Test
    void withTheDefaultGraceNoOperatorRequestIsRaisedForAConsumerThatJustReceivedAnImplementation() throws Exception {
        boundConsumerOwingAVerdict();
        System.clearProperty(RequesterAnswerPolicy.GRACE_PROPERTY);

        AgentResponse owner = ownerAsksToFinish();

        assertTrue(absentConsumerRequest().isEmpty());
        assertEquals(AgentStatus.WAITING, owner.status(), owner.toJson());
        assertEquals(AgentReason.VALIDATION_REQUIRED, owner.reason(), owner.toJson());
    }

    @Test
    void thePassingOfTheDeadlineDoesNotLetTheOwnerFinishWhileAConsumerHasNotBoundAgain() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        String handle = boundHandle(bind("claude", "inst-1", 1));
        publish(contract("Return exact matching product", "Reject null input"));
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(handle).state());
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");

        AgentResponse finished = ownerFinishes();

        assertNotEquals(AgentStatus.COMPLETED, finished.status(), finished.toJson());
        ownerAsksToFinish();
        AgentResponse owner = ownerAsksToFinish();
        assertEquals(AgentStatus.BLOCKED, owner.status(), owner.toJson());
        assertEquals(AgentReason.OPERATOR_REQUIRED, owner.reason(), owner.toJson());
        assertTrue(absentConsumerRequest().orElseThrow().evidence().contains(handle));
        assertTrue(replayedStore().events().stream().noneMatch(event -> event.type() == PredictionEventType.TASK_INTEGRATED), "nothing was integrated past the consumer");
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(handle).state(), "the consumer is not skipped or dropped");

        assertEquals(AgentStatus.READY, bind("claude", "inst-1", 2).status());
        ownerAsksToFinish();
        assertTrue(absentConsumerRequest().isEmpty(), "the consumer acted by binding again");
    }

    @Test
    void aDependencyAlreadyIntegratedIsANoteBesideTheLiveBindingTheLaneMustMakeAgainNotInsteadOfIt() throws Exception {
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        session("codex", "inst-3");
        WorkIntent consumer = collaboration.announce(projectRoot, "claude", "inst-1", "Implement the product CLI", "Use both APIs",
                List.of(ResourceSelector.pathExact("src/ProductCli.java")), null, WorkIntent.Role.PRODUCER, List.of(), List.of(CAPABILITY, REPORT_CAPABILITY)).intent();
        collaboration.announce(projectRoot, "codex", "inst-2", "Implement the product query API", "Publish the capability implementation", List.of(PRODUCT_QUERY), consumer.workGroupId());
        collaboration.announce(projectRoot, "codex", "inst-3", "Implement the product report", "Publish the report", List.of(PRODUCT_REPORT), consumer.workGroupId());
        publish(contract("Return exact matching product"));
        publishReport(contract("Total per product"));
        assertEquals("integrated", ((Map<?, ?>) result(ownerFinishes()).get("completion")).get("task"));
        nextAfterRefresh("claude", "inst-1");
        AgentResponse late = bind("claude", "inst-1", 1);
        assertEquals("CAPABILITY_OWNER_INTEGRATED", result(late).get("reason"), late.toJson());
        String live = boundHandle(requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "claude", "inst-1", REPORT_CAPABILITY, PRODUCT_REPORT, 1)));
        publishReport(contract("Total per product", "Skip withdrawn products"));
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(live).state());

        AgentResponse step = next("claude", "inst-1");

        assertEquals(AgentStatus.READY, step.status(), step.toJson());
        assertEquals(AgentReason.REVISION_REQUIRED, step.reason(), step.toJson());
        assertEquals(AgentNextAction.REVISE_CAPABILITY_REQUEST, step.nextAction(), step.toJson());
        assertEquals(live, result(step).get("capabilityRequestHandle"), step.toJson());
        assertNotNull(result(step).get("bindAgain"), step.toJson());
        assertEquals(List.of(CAPABILITY), result(step).get("dependenciesIntegrated"), step.toJson());
        assertNotNull(result(step).get("dependencyGuidance"), step.toJson());
    }

    private AgentResponse publishReport(CapabilityContract terms) {
        return ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-3", List.of(PRODUCT_REPORT), terms, List.of()));
    }

    @Test
    void aConsumerCancelledWhileValidatingLeavesNoValidationContextAndBlocksNoFinish() throws Exception {
        String handle = boundConsumerOwingAVerdict();
        assertEquals(AgentStatus.READY, validate("claude", "inst-1", handle, "inspect", 1).status());
        assertEquals(CapabilityLifecycleState.VALIDATING, replayedRequest(handle).state());
        assertFalse(replayedStore().capabilityRequestProjection().allValidationContexts().isEmpty(), "the validation is running");

        assertEquals(AgentStatus.COMPLETED, cancel("claude", "inst-1").status());

        assertEquals(CapabilityLifecycleState.SUPERSEDED, replayedRequest(handle).state());
        assertTrue(replayedStore().capabilityRequestProjection().allValidationContexts().isEmpty(), "a validation that ended with its binding is not running");
        AgentResponse finished = ownerFinishes();
        assertNotEquals(AgentReason.TASK_NOT_READY, finished.reason(), "no validation is running, so none holds the finish: " + finished.toJson());
    }

    @Test
    void anOwnerCancelledWithABoundConsumerWithdrawsItsContractAndTheConsumerIsNotToldTheDependencyIsIntegrated() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        String handle = boundHandle(bind("claude", "inst-1", 1));

        assertEquals(AgentStatus.COMPLETED, cancel("codex", "inst-2").status());

        PredictionEventStore after = replayedStore();
        assertEquals(ContractRecord.State.WITHDRAWN, after.contractProjection().contracts().getFirst().state());
        assertTrue(after.contractProjection().frozenCovering(PRODUCT_QUERY).isEmpty());
        CapabilityRequestRecord closed = after.capabilityRequestProjection().findByHandle(handle).orElseThrow();
        assertEquals(CapabilityLifecycleState.SUPERSEDED, closed.state());
        assertEquals(CapabilityRequestRecord.OWNER_LANE_ENDED_WITHOUT_WORK, closed.reason());
        assertTrue(closed.ownerLaneEnded());
        assertFalse(closed.ownerFinishedWithoutAnswer());
        AgentResponse consumerNext = next("claude", "inst-1");
        assertEquals(AgentStatus.NEEDS_CAPABILITY, consumerNext.status(), consumerNext.toJson());
        assertFalse(result(consumerNext).containsKey("dependenciesIntegrated"), consumerNext.toJson());
    }

    @Test
    void publishingAgainAfterTheRevisionWasRecordedButNotTheBindingsToldTellsTheStaleBindings() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        String handle = boundHandle(bind("claude", "inst-1", 1));
        ContractRecord prior = replayedStore().contractProjection().contracts().getFirst();
        CapabilityContract revised = contract("Return exact matching product", "Reject null input");
        // What an earlier call left behind when it failed after recording the revision.
        var location = projectService.locate(projectRoot);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.CONTRACT_PUBLISHED, identity.nodeId(),
                ContractCodec.encodePublished(new ContractRecord(prior.contractId(), prior.ownerLineageId(), 2, prior.paths(), revised, ContractRecord.State.ACTIVE)), identity);
        assertEquals(CapabilityLifecycleState.ACCEPTED, replayedRequest(handle).state(), "nothing has told the consumer yet");

        AgentResponse again = publish(revised);

        assertEquals(AgentStatus.COMPLETED, again.status(), again.toJson());
        assertEquals(Boolean.FALSE, result(again).get("published"), "the revision was already recorded");
        assertEquals(2L, ((Number) result(again).get("revision")).longValue());
        assertEquals(1, ((Number) result(again).get("bindingsToMakeAgain")).intValue(), again.toJson());
        assertEquals(CapabilityLifecycleState.REVISION_REQUESTED, replayedRequest(handle).state());
        assertEquals(0, ((Number) result(publish(revised)).get("bindingsToMakeAgain")).intValue(), "a binding already told is not told twice");
    }

    @Test
    void publishingAfterTheLaneHasPreparedItsCompletionIsRefused() throws Exception {
        announceBoth();
        publish(contract("Return exact matching product"));
        WorkIntent owner = replayedStore().collaborationProjection().activeIntents().stream().filter(intent -> intent.provider().equals("codex")).findFirst().orElseThrow();
        var location = projectService.locate(projectRoot);
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, identity.nodeId(),
                new CompletionPreparedPayload(owner.taskId(), "completion-1", owner.intentId(), owner.version(), "base", "refs/synesis/prepared", "tree", List.of("src/ProductQuery.java")).encode(), identity);

        AgentResponse refused = publish(contract("Return exact matching product", "Reject null input"));

        assertEquals(AgentStatus.BLOCKED, refused.status(), refused.toJson());
        assertEquals("CONTRACT_OWNER_COMPLETING", result(refused).get("reason"), refused.toJson());
        assertEquals(1L, replayedStore().contractProjection().contracts().getFirst().revision());
    }

    @Test
    void aContractCoveringMoreThanSixtyFourPathsIsRefused() throws Exception {
        new WorkspaceCollaborationService().announce(projectRoot, "codex", "inst-2", "Implement the product query API", "Publish the capability implementation",
                List.of(ResourceSelector.pathSubtree("src")));
        List<ResourceSelector> paths = java.util.stream.IntStream.rangeClosed(1, ContractRecord.MAX_PATHS + 1).mapToObj(index -> ResourceSelector.pathExact("src/File" + index + ".java")).toList();

        AgentResponse refused = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-2", paths, contract("Return exact matching product"), List.of()));

        assertEquals(AgentStatus.BLOCKED, refused.status(), refused.toJson());
        assertEquals("CONTRACT_COVERS_TOO_MANY_PATHS", result(refused).get("reason"), refused.toJson());
        assertTrue(replayedStore().contractProjection().contracts().isEmpty());
    }

    @Test
    void aContractOverASubtreeIsShownWithATrailingSlashAndPublishingItAsShownChangesNothing() throws Exception {
        new WorkspaceCollaborationService().announce(projectRoot, "codex", "inst-2", "Implement the product query API", "Publish the capability implementation",
                List.of(ResourceSelector.pathSubtree("src/store")));
        AgentResponse first = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-2", List.of(ResourceSelector.pathSubtree("src/store")),
                contract("Return exact matching product"), List.of()));
        assertEquals(List.of("src/store/"), result(first).get("paths"), first.toJson());

        List<ResourceSelector> asShown = ((List<?>) result(first).get("paths")).stream().map(String::valueOf)
                .map(path -> path.endsWith("/") ? ResourceSelector.pathSubtree(path) : ResourceSelector.pathExact(path)).toList();
        AgentResponse again = ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "codex", "inst-2", asShown, contract("Return exact matching product"), List.of()));

        assertEquals(Boolean.FALSE, result(again).get("published"), again.toJson());
        assertEquals(1L, ((Number) result(again).get("revision")).longValue());
        assertEquals(1, replayedStore().contractProjection().contracts().size());
        assertEquals(List.of("src/store/"), result(again).get("paths"));
    }

    @Test
    void aChangeRequestNeedOverFourThousandCharactersIsRefusedAndOneAtTheLimitIsNot() throws Exception {
        announceBoth();

        AgentResponse tooLong = requestChange("claude", "inst-1", "x".repeat(ContractChangeRequest.MAX_NEED_CHARACTERS + 1));

        assertEquals(AgentStatus.BLOCKED, tooLong.status(), tooLong.toJson());
        assertEquals("CONTRACT_CHANGE_NEED_TOO_LONG", result(tooLong).get("reason"), tooLong.toJson());
        assertTrue(replayedStore().events().stream().noneMatch(event -> event.type() == PredictionEventType.CONTRACT_CHANGE_REQUESTED));
        AgentResponse atLimit = requestChange("claude", "inst-1", "x".repeat(ContractChangeRequest.MAX_NEED_CHARACTERS));
        assertNotEquals("CONTRACT_CHANGE_NEED_TOO_LONG", String.valueOf(result(atLimit).get("reason")), atLimit.toJson());
    }

    /** A second owner (claude, inst-4) with its own consumer (codex, inst-3), beside the first pair. */
    private void secondOwnerAndConsumer(UUID workGroupId) throws Exception {
        session("codex", "inst-3");
        session("claude", "inst-4");
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        collaboration.announce(projectRoot, "codex", "inst-3", "Implement the product report", "Use the published report API",
                List.of(ResourceSelector.pathExact("src/ProductCli2.java")), workGroupId, WorkIntent.Role.PRODUCER, List.of(), List.of(REPORT_CAPABILITY));
        collaboration.announce(projectRoot, "claude", "inst-4", "Implement the report API", "Publish the report API",
                List.of(PRODUCT_REPORT), workGroupId);
    }

    @Test
    void onlyTheOwnerWhoseOwnConsumerIsAbsentIsToldToStop() throws Exception {
        WorkIntent consumer = announceBoth();
        secondOwnerAndConsumer(consumer.workGroupId());
        publish(contract("Return exact matching product"));
        ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "claude", "inst-4", List.of(PRODUCT_REPORT), contract("Total per product"), List.of()));
        boundHandle(bind("claude", "inst-1", 1));
        boundHandle(requestService.bind(new ContractConsumerService.BindRequest(projectRoot, "codex", "inst-3", REPORT_CAPABILITY, PRODUCT_REPORT, 1)));
        publish(contract("Return exact matching product", "Reject null input"));
        long firstBindingStaleBy = System.currentTimeMillis();
        // The second consumer's binding goes stale twelve seconds after the first's.
        Thread.sleep(12_000);
        ownerService.publish(new ContractOwnerService.PublishRequest(projectRoot, "claude", "inst-4", List.of(PRODUCT_REPORT), contract("Total per product", "Skip withdrawn products"), List.of()));
        for (List<String> owned : List.of(List.of("codex", "inst-2", "ProductQuery.java"), List.of("claude", "inst-4", "ProductReport.java"))) {
            Path worktree = Path.of(bindingService.find(projectService.locate(projectRoot), owned.get(0), owned.get(1)).orElseThrow().worktreePath());
            Files.createDirectories(worktree.resolve("src"));
            Files.writeString(worktree.resolve("src").resolve(owned.get(2)), "final class Stub {}\n");
        }

        // The wait is set to what has passed since the first binding went stale, so that one is
        // absent now and the second is not for about another twelve seconds, however long the
        // set-up took on a loaded machine.
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, String.valueOf((System.currentTimeMillis() - firstBindingStaleBy) / 1000));
        ownerAsksToFinish();
        AgentResponse firstOwner = ownerAsksToFinish();
        AgentResponse secondOwner = nextActionService.getNextAction(new NextActionRequest(projectRoot, "claude", "inst-4", true));

        assertTrue(absentConsumerRequest().isPresent(), "the first consumer was put to the operator");
        assertEquals(AgentReason.OPERATOR_REQUIRED, firstOwner.reason(), firstOwner.toJson());
        assertEquals(AgentNextAction.WAIT, secondOwner.nextAction(), secondOwner.toJson());
        assertEquals(AgentReason.REQUESTER_RESPONSE_PENDING, secondOwner.reason(), secondOwner.toJson());
    }

    @Test
    void anAbsentBoundConsumerDoesNotHoldItsProviderAndTheOwnerIsResumedAtTheMomentItIsAbsent() throws Exception {
        String handle = boundConsumerOwingAVerdict();
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");
        ownerAsksToFinish();
        assertTrue(absentConsumerRequest().isPresent());
        PredictionEventStore store = replayedStore();

        var observation = new EventLogObserver(projectService.locate(projectRoot)).observe();

        assertFalse(observation.heldByOperator("claude"));
        assertFalse(observation.heldByOperator("codex"));
        String consumerParticipant = WorkspaceCollaborationService.participantHandle(bindingService.find(projectService.locate(projectRoot), "claude", "inst-1").orElseThrow().sessionId());
        long consumerLastCall = org.synesis.workspace.application.collaboration.ParticipantActivity.overlay(projectService.locate(projectRoot).root(), store.collaborationProjection().participants()).stream().filter(participant -> participant.id().equals(consumerParticipant))
                .mapToLong(org.synesis.coordination.domain.collaboration.Participant::lastVerifiedActivity).max().orElse(0L);
        assertEquals(java.util.OptionalLong.of(RequesterAnswerPolicy.absentAtEpochMillis(store.capabilityRequestProjection().findByHandle(handle).orElseThrow(), consumerLastCall)),
                observation.answerDeadline("codex"));
        assertEquals(java.util.OptionalLong.empty(), observation.answerDeadline("claude"));
    }

    private long consumerAbsentAt() throws Exception {
        return org.synesis.workspace.application.integration.ConsumerAbsenceGuard.owed(projectRoot, replayedStore()).getFirst().absentAtEpochMillis();
    }

    @Test
    void aConsumerThatKeepsCallingIsAbsentLaterThanOneThatStopped() throws Exception {
        String handle = boundConsumerOwingAVerdict();
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "3600");
        long graceMillis = 3_600_000L;
        assertEquals(replayedRequest(handle).updatedAtEpochMillis() + graceMillis, consumerAbsentAt(), "no call yet: the wait runs from the record's last change");

        Thread.sleep(20);
        long before = System.currentTimeMillis();
        new WorkspaceCollaborationService().heartbeat(projectRoot, "claude", "inst-1");
        long after = System.currentTimeMillis();
        long called = consumerAbsentAt();
        assertTrue(called >= before + graceMillis && called <= after + graceMillis, "a call restarts the wait: " + called);

        Thread.sleep(20);
        assertEquals(called, consumerAbsentAt(), "a consumer that stopped calling is absent at a fixed moment");
        new WorkspaceCollaborationService().heartbeat(projectRoot, "claude", "inst-1");
        assertTrue(consumerAbsentAt() > called, "a consumer that keeps calling is absent later");
    }
}
