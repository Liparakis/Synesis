package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.ReplicationJournal;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.completion.CompleteTaskRequest;
import org.synesis.workspace.application.agent.completion.CompletionOutcome;
import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.AgentTaskCompletionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.conversation.ConversationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * Exercises what lanes with real session bindings may say to each other, who is handed it, and
 * that saying it changes nothing a lane may do (ADR-0093).
 */
@SuppressWarnings("unchecked")
class ConversationServiceTest {

    private static final String PROVIDER = "codex";
    private static final String A = "inst-1";
    private static final String B = "inst-2";
    private static final String C = "inst-3";
    private static final String NO_CHANGE = "Verification succeeds without repository mutation";

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private ProjectApplicationService projectService;
    private ProviderSessionBindingService bindingService;
    private ConversationService conversations;
    private AgentNextActionService nextActionService;
    private UUID workGroup;

    private static Map<String, Object> result(AgentResponse response) {
        return (Map<String, Object>) response.result();
    }

    private void session(String instance) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        new AgentSessionService().ensureSession(new SessionResolutionRequest(projectRoot, PROVIDER, instance, null, false));
        var binding = bindingService.find(location, PROVIDER, instance).orElseThrow();
        bindingService.verifyWorkspaceTrust(location, PROVIDER, binding.sessionId(), Path.of(binding.worktreePath()));
    }

    private WorkIntent claim(String instance, String file) throws Exception {
        return claim(instance, file, "Passes its tests");
    }

    private WorkIntent claim(String instance, String file, String acceptance) throws Exception {
        session(instance);
        WorkspaceCollaborationService collaboration = new WorkspaceCollaborationService();
        var claimed = workGroup == null
                ? collaboration.announce(projectRoot, PROVIDER, instance, "Build " + file, acceptance, List.of(ResourceSelector.pathExact(file)))
                : collaboration.announce(projectRoot, PROVIDER, instance, "Build " + file, acceptance, List.of(ResourceSelector.pathExact(file)), workGroup);
        workGroup = claimed.intent().workGroupId();
        return claimed.intent();
    }

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("test-project");
        Files.createDirectories(projectRoot);
        org.synesis.workspace.test.TestGit.run(projectRoot, "init");
        org.synesis.workspace.test.TestGit.run(projectRoot, "config", "user.name", "Test User");
        org.synesis.workspace.test.TestGit.run(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# Test Project\n");
        org.synesis.workspace.test.TestGit.run(projectRoot, "add", ".");
        org.synesis.workspace.test.TestGit.run(projectRoot, "commit", "-m", "Initial commit");

        projectService = new ProjectApplicationService();
        projectService.init(projectRoot);
        ProviderTestSupport.install(projectService.locate(projectRoot), PROVIDER);
        bindingService = new ProviderSessionBindingService();
        conversations = new ConversationService();
        nextActionService = new AgentNextActionService();

        claim(A, "src/A.java");
        claim(B, "src/B.java");
        claim(C, "src/C.java");
    }

    private PredictionEventStore store() throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        return new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
    }

    private String handle(String instance) throws Exception {
        return WorkspaceCollaborationService.participantHandle(bindingService.find(projectService.locate(projectRoot), PROVIDER, instance).orElseThrow().sessionId());
    }

    private WorkIntent laneOf(String instance) throws Exception {
        String handle = handle(instance);
        return store().collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(handle)).findFirst().orElseThrow();
    }

    private UUID lineage(String instance) throws Exception {
        return laneOf(instance).authorityLineageId();
    }

    private List<OperatorRequest> laneRequests() throws Exception {
        return store().operatorRequestProjection().open().stream().filter(request -> request.condition().equals(OperatorRequest.LANE_ASKS_OPERATOR)).toList();
    }

    private AgentResponse send(String from, List<String> to, String text) {
        return conversations.send(new ConversationService.SendRequest(projectRoot, PROVIDER, from, to, null, text, ConversationService.Cursor.START));
    }

    private AgentResponse sendTo(String from, UUID conversation, String text) {
        return conversations.send(new ConversationService.SendRequest(projectRoot, PROVIDER, from, List.of(), conversation, text, ConversationService.Cursor.START));
    }

    private UUID conversationOf(AgentResponse sent) {
        assertEquals(AgentStatus.COMPLETED, sent.status(), sent.toJson());
        return UUID.fromString((String) result(sent).get("conversation"));
    }

    private ConversationService.Unread unread(String instance) {
        return unread(instance, ConversationService.Cursor.START);
    }

    private ConversationService.Unread unread(String instance, ConversationService.Cursor handed) {
        return conversations.unread(projectRoot, PROVIDER, instance, handed);
    }

    private long deliveryMarks() throws Exception {
        return store().events().stream().filter(event -> event.type() == PredictionEventType.CONVERSATION_MESSAGES_DELIVERED).count();
    }

    private static List<Object> texts(ConversationService.Unread unread) {
        return unread.messages().stream().map(message -> message.get("text")).toList();
    }

    /** The finish a lane with no changes is shown, as the typed arguments of {@code finish_lane}. */
    private Map<String, Object> projectedNoChangeFinish(String instance) {
        AgentResponse projection = nextActionService.getNextAction(new NextActionRequest(projectRoot, PROVIDER, instance, true));
        Map<String, Object> workflow = (Map<String, Object>) result(projection).get("workflow");
        assertEquals("finish_lane", workflow.get("recommendedTool"), projection.toJson());
        return (Map<String, Object>) workflow.get("arguments");
    }

    private AgentResponse finishNoChange(String instance, Map<String, Object> arguments) {
        return new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, PROVIDER, instance, (String) arguments.get("summary"), CompletionOutcome.NO_CHANGE,
                UUID.fromString((String) arguments.get("intentId")), UUID.fromString((String) arguments.get("workGroupId")), ((Number) arguments.get("claimEpoch")).longValue(),
                ((Number) arguments.get("workGroupVersion")).longValue(), ((Number) arguments.get("expectedRevision")).longValue(), (String) arguments.get("participant"), false));
    }

    private List<Object> view(String instance) {
        AgentResponse response = nextActionService.getNextAction(new NextActionRequest(projectRoot, PROVIDER, instance));
        Object actionId = Optional.ofNullable(result(response)).map(map -> map.get("workflow")).map(workflow -> ((Map<String, Object>) workflow).get("actionId")).orElse(null);
        return java.util.Arrays.asList(response.status(), response.reason(), response.nextAction(), actionId);
    }

    @Test
    void aMemberIsNamedByAFileItClaimsOrByItsHandleAndBothGiveTheSameConversation() throws Exception {
        UUID byFile = conversationOf(send(A, List.of("src/B.java"), "does listing create the store?"));
        UUID byHandle = conversationOf(send(A, List.of(handle(B)), "I mean the store file"));

        assertEquals(byFile, byHandle);
        assertEquals(ConversationMessage.conversationOf(List.of(lineage(A), lineage(B))), byFile);
        assertEquals(2, unread(B).messages().size());
    }

    @Test
    void anUnknownNameIsRefusedWithTheOpenLanesAndNothingIsRecorded() throws Exception {
        AgentResponse unknown = send(A, List.of("src/Nobody.java"), "anyone?");

        assertEquals(AgentStatus.BLOCKED, unknown.status(), unknown.toJson());
        assertEquals("MESSAGE_MEMBER_NOT_FOUND", result(unknown).get("reason"));
        assertEquals(List.of("src/Nobody.java"), result(unknown).get("notFound"));
        List<Map<String, Object>> open = (List<Map<String, Object>>) result(unknown).get("openLanes");
        assertEquals(List.of(List.of("src/B.java"), List.of("src/C.java")), open.stream().map(lane -> lane.get("claims")).toList());
        assertTrue(store().conversationProjection().messages().isEmpty());
    }

    @Test
    void aCallerNamingOnlyItsOwnFileOrHandleHasNoOneToWriteTo() throws Exception {
        assertEquals("MESSAGE_MEMBER_NOT_FOUND", result(send(A, List.of("src/A.java"), "to myself")).get("reason"));
        assertEquals("MESSAGE_MEMBER_NOT_FOUND", result(send(A, List.of(handle(A)), "to myself")).get("reason"));
        assertTrue(store().conversationProjection().messages().isEmpty());
    }

    @Test
    void aNonMemberCannotWriteToOrReadAConversation() throws Exception {
        UUID conversation = conversationOf(send(A, List.of("src/B.java"), "between us"));

        AgentResponse intruder = sendTo(C, conversation, "let me in");
        AgentResponse nonexistent = sendTo(C, UUID.randomUUID(), "let me in");

        assertEquals(AgentStatus.BLOCKED, intruder.status(), intruder.toJson());
        assertEquals("CONVERSATION_NOT_FOUND", result(intruder).get("reason"));
        assertEquals(result(nonexistent).get("reason"), result(intruder).get("reason"), "that it exists is not shown to a non-member");
        assertTrue(conversations.history(projectRoot, PROVIDER, C).isEmpty());
        assertTrue(unread(C).messages().isEmpty());
        assertEquals(1, store().conversationProjection().messages().size());
    }

    @Test
    void aMessageIsHandedOverAgainUntilTheBatchIsAcknowledgedByTheNextCall() throws Exception {
        send(A, List.of("src/B.java"), "first");

        ConversationService.Unread handed = unread(B);
        assertEquals(1, handed.messages().size());
        assertEquals("first", handed.messages().getFirst().get("text"));
        assertEquals(1, unread(B).messages().size(), "a connection that never calls again leaves the batch unread");
        assertEquals(1, store().conversationProjection().unread(lineage(B)).size());

        ConversationService.Unread acknowledged = unread(B, handed.cursor());
        assertTrue(acknowledged.messages().isEmpty());
        assertTrue(store().conversationProjection().unread(lineage(B)).isEmpty(), "the durable mark moved");
        assertTrue(unread(B).messages().isEmpty(), "a connection that knows nothing is handed nothing more");

        send(A, List.of("src/B.java"), "second");
        assertEquals(List.of("second"), unread(B).messages().stream().map(message -> message.get("text")).toList());
    }

    @Test
    void aSuccessorLaneReadsItsPredecessorsConversationsAndIsHandedWhatWasUnread() throws Exception {
        UUID conversation = conversationOf(send(A, List.of("src/B.java"), "A asks"));
        send(B, List.of("src/A.java"), "B answers");
        assertEquals("B answers", unread(A).messages().getFirst().get("text"));

        ProjectLocation location = projectService.locate(projectRoot);
        var binding = bindingService.find(location, PROVIDER, A).orElseThrow();
        WorkIntent claim = laneOf(A);
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, binding.sessionId());
        WorkIntentService intents = new WorkIntentService(store(), new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        intents.suspend(claim.participant());
        intents.holdRecovery(claim.participant(), snapshot.root().toString() + "#" + snapshot.contentHash());
        String successor = "inst-4";
        session(successor);
        new WorkspaceCollaborationService().continueLane(projectRoot, PROVIDER, successor, null, claim.intentId(), claim.version());

        assertEquals(lineage(successor), claim.authorityLineageId(), "the successor holds the predecessor's lane");
        List<Map<String, Object>> history = conversations.history(projectRoot, PROVIDER, successor);
        assertEquals(1, history.size());
        assertEquals(conversation.toString(), history.getFirst().get("conversation"));
        assertEquals(List.of("A asks", "B answers"), ((List<Map<String, Object>>) history.getFirst().get("messages")).stream().map(message -> message.get("text")).toList());
        assertEquals(List.of("B answers"), unread(successor).messages().stream().map(message -> message.get("text")).toList(), "what the predecessor never acknowledged");
        assertEquals(conversation, conversationOf(sendTo(successor, conversation, "the successor speaks")));
        assertEquals(1, unread(B).messages().stream().filter(message -> message.get("text").equals("the successor speaks")).count());
    }

    @Test
    void atMostTenUnreadMessagesAreHandedOverAtATimeAndTheRestAreCounted() throws Exception {
        for (int number = 1; number <= 12; number++) {
            send(A, List.of("src/B.java"), "message " + number);
        }

        ConversationService.Unread first = unread(B);
        assertEquals(10, first.messages().size());
        assertEquals(2, first.moreUnread());
        assertEquals("message 1", first.messages().getFirst().get("text"));

        ConversationService.Unread second = unread(B, first.cursor());
        assertEquals(List.of("message 11", "message 12"), second.messages().stream().map(message -> message.get("text")).toList());
        assertEquals(0, second.moreUnread());
    }

    @Test
    void theFiftyFirstMessageOfALaneAndAnOverlongMessageAreRefused() throws Exception {
        for (int number = 1; number <= ConversationService.MAX_MESSAGES_PER_LANE; number++) {
            AgentResponse sent = send(A, List.of("src/B.java"), "message " + number);
            assertEquals(AgentStatus.COMPLETED, sent.status(), sent.toJson());
        }

        AgentResponse over = send(A, List.of("src/B.java"), "one too many");

        assertEquals(AgentStatus.BLOCKED, over.status(), over.toJson());
        assertEquals("MESSAGE_LIMIT_REACHED", result(over).get("reason"));
        assertEquals(50L, ((Number) result(over).get("sent")).longValue());
        assertEquals(50, ((Number) result(over).get("limit")).intValue());
        assertEquals(50, store().conversationProjection().sentBy(lineage(A)));
        assertEquals(AgentStatus.COMPLETED, send(B, List.of("src/A.java"), "another lane still may").status(), "the bound is the lane's own");

        assertEquals(AgentStatus.COMPLETED, send(C, List.of("src/A.java"), "x".repeat(ConversationService.MAX_MESSAGE_CHARS)).status());
        AgentResponse long2001 = send(C, List.of("src/A.java"), "x".repeat(ConversationService.MAX_MESSAGE_CHARS + 1));
        assertEquals("MESSAGE_TEXT_OUT_OF_BOUNDS", result(long2001).get("reason"), long2001.toJson());
        assertEquals("MESSAGE_TEXT_OUT_OF_BOUNDS", result(send(C, List.of("src/A.java"), "   ")).get("reason"));
    }

    @Test
    void anAddressMustBeMembersOrAConversationAndTheOperatorIsAddressedAlone() throws Exception {
        UUID conversation = conversationOf(send(A, List.of("src/B.java"), "hello"));

        assertEquals("MESSAGE_ADDRESS_REQUIRED", result(send(A, List.of(), "to nobody")).get("reason"));
        assertEquals("MESSAGE_ADDRESS_REQUIRED", result(conversations.send(new ConversationService.SendRequest(projectRoot, PROVIDER, A, List.of("src/B.java"), conversation, "both", ConversationService.Cursor.START))).get("reason"));
        assertEquals("OPERATOR_IS_ADDRESSED_ALONE", result(send(A, List.of("operator", "src/B.java"), "both")).get("reason"));
        assertEquals(1, store().conversationProjection().messages().size());
    }

    @Test
    void aMessageToTheOperatorIsAnOperatorRequestThatChangesNothingTheLaneMayDo() throws Exception {
        List<Object> before = view(A);

        AgentResponse asked = send(A, List.of("operator"), "Which license should the new file carry?");

        assertEquals(AgentStatus.COMPLETED, asked.status(), asked.toJson());
        Optional<OperatorRequest> open = laneRequests().stream().findFirst();
        assertTrue(open.isPresent());
        assertEquals("Which license should the new file carry?", open.get().summary());
        assertEquals(lineage(A).toString(), open.get().raisedBy());
        assertEquals(result(asked).get("operatorRequest"), open.get().id());
        assertEquals(before, view(A), "the lane's next action is what it was");
        assertTrue(store().conversationProjection().messages().isEmpty(), "the question is a request, not a conversation");

        AgentResponse again = send(A, List.of("operator"), "Ask twice");
        assertEquals("OPERATOR_ALREADY_ASKED", result(again).get("reason"), again.toJson());
        assertEquals(1, store().operatorRequestProjection().open().size());
        assertEquals(AgentStatus.COMPLETED, send(B, List.of("operator"), "another lane may ask").status());
    }

    @Test
    void theOperatorClosesARequestALaneRaisedAndCannotCloseOneSynesisRaised() throws Exception {
        String lane = (String) result(send(A, List.of("operator"), "Which license?")).get("operatorRequest");
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = store();
        var identity = location.loadOrCreateIdentity();
        OperatorRequest synesis = OperatorRequest.raised("opr_synesis000000001", OperatorRequest.BOUND_CONSUMER_ABSENT, "a consumer is absent", "");
        store.append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), synesis.encodeRaised(), identity);

        Optional<String> refused = conversations.closeOperatorRequest(location, synesis.id(), "closing by hand");
        assertTrue(refused.isPresent());
        assertTrue(store().operatorRequestProjection().open().stream().anyMatch(request -> request.id().equals(synesis.id())), "a block that still holds stays open");

        assertEquals(Optional.empty(), conversations.closeOperatorRequest(location, lane, "answered by phone"));
        assertTrue(laneRequests().isEmpty());
        assertTrue(conversations.closeOperatorRequest(location, lane, "again").isPresent(), "an unknown or closed request is refused");
        assertEquals(AgentStatus.COMPLETED, send(A, List.of("operator"), "a new question once it is closed").status());
    }

    @Test
    void addingOnlyMessagesToTheLogChangesNoLanesNextAction() throws Exception {
        List<List<Object>> before = List.of(view(A), view(B), view(C));

        send(A, List.of("src/B.java"), "question");
        send(B, List.of("src/A.java"), "answer");
        send(A, List.of("src/B.java", "src/C.java"), "to all");
        ConversationService.Unread handed = unread(B);
        unread(B, handed.cursor());

        assertEquals(1, deliveryMarks(), "the step acknowledged something real");
        assertTrue(store().conversationProjection().unread(lineage(B)).isEmpty());
        assertEquals(before, List.of(view(A), view(B), view(C)));
    }

    @Test
    void aMessageBetweenTwoLanesDoesNotMakeAThirdLanesProjectedFinishStale() throws Exception {
        WorkIntent third = laneOf(C);
        Path worktree = Path.of(bindingService.find(projectService.locate(projectRoot), PROVIDER, C).orElseThrow().worktreePath());
        Files.createDirectories(worktree.resolve("src"));
        Files.writeString(worktree.resolve("src/C.java"), "final class C {}\n");
        AgentResponse projection = nextActionService.getNextAction(new NextActionRequest(projectRoot, PROVIDER, C, true));
        Map<String, Object> arguments = (Map<String, Object>) ((Map<String, Object>) result(projection).get("workflow")).get("arguments");
        assertEquals(third.intentId().toString(), arguments.get("intentId"), projection.toJson());

        send(A, List.of("src/B.java"), "question");
        send(B, List.of("src/A.java"), "answer");

        AgentResponse finished = new AgentTaskCompletionService().completeTask(new CompleteTaskRequest(projectRoot, PROVIDER, C, (String) arguments.get("summary"), null,
                UUID.fromString((String) arguments.get("intentId")), UUID.fromString((String) arguments.get("workGroupId")), ((Number) arguments.get("claimEpoch")).longValue(),
                ((Number) arguments.get("workGroupVersion")).longValue(), ((Number) arguments.get("expectedRevision")).longValue(), (String) arguments.get("participant"), false));

        assertFalse(finished.toJson().toLowerCase().contains("evidence_stale"), finished.toJson());
        assertEquals("integrated", ((Map<String, Object>) result(finished).get("completion")).get("task"), finished.toJson());
    }

    @Test
    void theRunObserverSeesTheNewestMessageAddressedToAConnectionsLaneAndNoProgressInIt() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        AgentRunController.Observation before = observer.observe();
        assertTrue(before.newestUnreadMessageFor(PROVIDER, B).isEmpty());

        send(A, List.of("src/B.java"), "one");
        send(A, List.of("src/B.java", "src/C.java"), "two");
        List<ConversationMessage> said = store().conversationProjection().messages();
        AgentRunController.Observation after = observer.observe();
        assertFalse(after.progressedSince(before.headSequence()), "messages are not work");
        send(A, List.of("operator"), "a question");
        after = observer.observe();
        assertEquals(said.get(1).sequence(), after.newestUnreadMessageFor(PROVIDER, B).getAsLong());
        assertEquals(said.get(1).sequence(), after.newestUnreadMessageFor(PROVIDER, C).getAsLong());
        assertTrue(after.newestUnreadMessageFor(PROVIDER, A).isEmpty(), "a sender is not sent its own message");
        assertTrue(after.newestUnreadMessageFor(PROVIDER, "inst-unknown").isEmpty());
        assertEquals(1, after.operatorRequests().size());
        assertTrue(after.operatorRequests().getFirst().endsWith("CONDITION=lane_asks_operator"), after.operatorRequests().toString());
    }

    @Test
    void theRunObserverSaysOfEachConnectionWhetherItsOwnLaneIsStillOpen() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        assertTrue(observer.observe().holdsOpenLane(PROVIDER, A));
        assertTrue(observer.observe().holdsOpenLane(PROVIDER, B));

        AgentResponse cancelled = new org.synesis.workspace.application.agent.completion.AgentTaskCancellationService()
                .cancelTask(new org.synesis.workspace.application.agent.completion.AgentTaskCancellationService.CancelTaskRequest(projectRoot, PROVIDER, A, "Not needed after all"));
        assertEquals(AgentStatus.COMPLETED, cancelled.status(), cancelled.toJson());

        AgentRunController.Observation after = observer.observe();
        assertFalse(after.holdsOpenLane(PROVIDER, A), "its own lane ended");
        assertTrue(after.hasOpenLane(PROVIDER), "the provider still has open lanes, which is no reason to resume the one whose lane ended");
        assertTrue(after.holdsOpenLane(PROVIDER, B), "another connection of the provider keeps its lane");
        assertFalse(after.holdsOpenLane(PROVIDER, "inst-unknown"), "a connection nobody used holds no lane");
        assertFalse(after.holdsOpenLane(PROVIDER, null));
    }

    @Test
    void anOpenLaneOfAnActiveParticipantTheControllerDidNotStartKeepsTheRunGoingAndASuspendedHolderDoesNot() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        ProjectLocation location = projectService.locate(projectRoot);
        List<AgentRunController.Observation.Connection> onlyA = List.of(new AgentRunController.Observation.Connection(PROVIDER, A));
        List<AgentRunController.Observation.Connection> all = List.of(onlyA.getFirst(), new AgentRunController.Observation.Connection(PROVIDER, B), new AgentRunController.Observation.Connection(PROVIDER, C));

        assertTrue(observer.observe().foreignActiveLaneOpen(onlyA), "B and C hold open lanes and are not ours");
        assertFalse(observer.observe().foreignActiveLaneOpen(all), "every lane is ours");

        WorkIntentService intents = new WorkIntentService(store(), new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        intents.suspend(laneOf(B).participant());
        intents.suspend(laneOf(C).participant());

        assertFalse(observer.observe().foreignActiveLaneOpen(onlyA), "a suspended holder will not progress, so nothing is waited for");
    }

    @Test
    void theRunObserverSeesAnotherComputerConnectedOnlyWhenItIsAPeerOfTheProjectAndItsLatestSessionIsOpen() throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        EventLogObserver observer = new EventLogObserver(location);
        ReplicationJournal journal = new ReplicationJournal(location.coordinationDirectory());
        journal.record("CONNECTED peer=sl1-stranger expiry=30s");
        assertFalse(observer.observe().otherComputerConnected(), "an unshared project has no other computer, whatever the journal says");

        ReplicationSettings.leader(java.util.Set.of("sl1-member")).write(location.coordinationDirectory());
        assertFalse(observer.observe().otherComputerConnected(), "a node that is not a member counts for nothing");
        journal.record("CONNECTED peer=sl1-member expiry=30s");
        assertTrue(observer.observe().otherComputerConnected());
        journal.record("DISCONNECT peer=sl1-member reason=LIVENESS_EXPIRED liveness=EXPIRED lasted=840s");
        assertFalse(observer.observe().otherComputerConnected(), "the member dropped");

        ReplicationSettings.member("sl1-stranger").write(location.coordinationDirectory());
        assertTrue(observer.observe().otherComputerConnected(), "a member sees its leader");
    }

    @Test
    void aSuspendedHolderStillHoldsItsOpenLaneAndALaneContinuedByAnotherSessionIsNoLongerItsOwn() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        ProjectLocation location = projectService.locate(projectRoot);
        WorkIntent claim = laneOf(A);
        RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, bindingService.find(location, PROVIDER, A).orElseThrow().sessionId());
        WorkIntentService intents = new WorkIntentService(store(), new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        intents.suspend(claim.participant());
        intents.holdRecovery(claim.participant(), snapshot.root().toString() + "#" + snapshot.contentHash());

        assertTrue(observer.observe().holdsOpenLane(PROVIDER, A), "a suspended holder's lane is still open under its connection");

        String successor = "inst-4";
        session(successor);
        new WorkspaceCollaborationService().continueLane(projectRoot, PROVIDER, successor, null, claim.intentId(), claim.version());

        AgentRunController.Observation after = observer.observe();
        assertFalse(after.holdsOpenLane(PROVIDER, A), "another session continued it");
        assertTrue(after.holdsOpenLane(PROVIDER, successor));
    }

    @Test
    void aMessageTheLaneAlreadyAcknowledgedWakesNobody() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        send(A, List.of("src/B.java", "src/C.java"), "one");
        assertTrue(observer.observe().newestUnreadMessageFor(PROVIDER, B).isPresent());

        ConversationService.Unread handed = unread(B);
        assertTrue(observer.observe().newestUnreadMessageFor(PROVIDER, B).isPresent(), "handed but not yet acknowledged is still unread");
        unread(B, handed.cursor());

        assertTrue(observer.observe().newestUnreadMessageFor(PROVIDER, B).isEmpty());
        assertTrue(observer.observe().newestUnreadMessageFor(PROVIDER, C).isPresent(), "another lane's mark is its own");
    }

    @Test
    void aLaneRaisingAnOperatorRequestAndTheOperatorClosingItAreNotProgress() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        long beforeAsking = observer.observe().headSequence();

        String request = (String) result(send(A, List.of("operator"), "Which license?")).get("operatorRequest");
        AgentRunController.Observation asked = observer.observe();
        assertFalse(asked.progressedSince(beforeAsking));

        assertEquals(Optional.empty(), conversations.closeOperatorRequest(projectService.locate(projectRoot), request, "answered by phone"));
        assertFalse(observer.observe().progressedSince(asked.headSequence()), "closing it resumes nobody");
        assertFalse(observer.observe().progressedSince(beforeAsking));
    }

    @Test
    void anOperatorRequestSynesisRaisedIsProgress() throws Exception {
        EventLogObserver observer = new EventLogObserver(projectService.locate(projectRoot));
        long before = observer.observe().headSequence();
        var identity = projectService.locate(projectRoot).loadOrCreateIdentity();
        OperatorRequest synesis = OperatorRequest.raised("opr_synesis000000001", OperatorRequest.BOUND_CONSUMER_ABSENT, "a consumer is absent", "");
        store().append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), synesis.encodeRaised(), identity);

        assertTrue(observer.observe().progressedSince(before));
    }

    @Test
    void aLaneThatHasEndedIsStillNamedAsItWasInWhatItSaid() throws Exception {
        String speaker = handle(A);
        UUID lineage = lineage(A);
        List<String> claims = laneOf(A).selectors().stream().map(ResourceSelector::value).toList();
        conversationOf(send(A, List.of(handle(B)), "The store keeps one file per project."));

        AgentResponse cancelled = new org.synesis.workspace.application.agent.completion.AgentTaskCancellationService()
                .cancelTask(new org.synesis.workspace.application.agent.completion.AgentTaskCancellationService.CancelTaskRequest(projectRoot, PROVIDER, A, "Not needed after all"));
        assertEquals(AgentStatus.COMPLETED, cancelled.status(), cancelled.toJson());

        // Whoever reads the conversation afterwards has to be able to tell who said what.
        Map<String, Object> ended = ConversationService.memberMap(store().collaborationProjection(), lineage);
        assertEquals(speaker, ended.get("participant"));
        assertEquals(claims, ended.get("claims"));
        assertEquals("ended", ended.get("lane"));
        assertFalse(ConversationService.memberMap(store().collaborationProjection(), lineage(B)).containsKey("lane"), "an open lane is not marked as ended");
        assertEquals(Map.of("lane", "ended"), ConversationService.memberMap(store().collaborationProjection(), UUID.randomUUID()), "a lineage no lane ever had says only that");
    }

    @Test
    void aMessageAndItsDeliveryMarkDoNotMakeANoChangeFinishStale() throws Exception {
        claim("inst-4", "src/D.java", NO_CHANGE);
        Map<String, Object> arguments = projectedNoChangeFinish("inst-4");

        send(A, List.of("src/B.java"), "question");
        unread(B, unread(B).cursor());
        assertEquals(1, deliveryMarks());

        AgentResponse finished = finishNoChange("inst-4", arguments);

        assertEquals(AgentStatus.COMPLETED, finished.status(), finished.toJson());
        assertFalse(finished.toJson().toLowerCase().contains("evidence_stale"), finished.toJson());
    }

    @Test
    void aLanesOwnPollBeforeItsNoChangeFinishDoesNotMakeItStale() throws Exception {
        claim("inst-4", "src/D.java", NO_CHANGE);
        send(A, List.of("src/D.java"), "ping");
        ConversationService.Unread handed = unread("inst-4");
        assertEquals(List.of("ping"), texts(handed));
        Map<String, Object> arguments = projectedNoChangeFinish("inst-4");

        unread("inst-4", handed.cursor());
        assertEquals(1, deliveryMarks(), "the poll appended the lane's own mark");

        AgentResponse finished = finishNoChange("inst-4", arguments);

        assertEquals(AgentStatus.COMPLETED, finished.status(), finished.toJson());
        assertFalse(finished.toJson().toLowerCase().contains("evidence_stale"), finished.toJson());
    }

    @Test
    void aLaneThatFinishedWithNoChangeIsStillNamedAsItWasInWhatItSaid() throws Exception {
        WorkIntent lane = claim("inst-4", "src/D.java", NO_CHANGE);
        send("inst-4", List.of("src/A.java"), "nothing to change here");
        AgentResponse finished = finishNoChange("inst-4", projectedNoChangeFinish("inst-4"));
        assertEquals(AgentStatus.COMPLETED, finished.status(), finished.toJson());

        Map<String, Object> ended = ConversationService.memberMap(store().collaborationProjection(), lane.authorityLineageId());

        assertEquals(lane.participant(), ended.get("participant"));
        assertEquals(List.of("src/D.java"), ended.get("claims"));
        assertEquals("ended", ended.get("lane"));
    }

    @Test
    void aCursorReachedInAnotherLaneDoesNotMarkThisLanesOlderMessagesDelivered() throws Exception {
        send(B, List.of("src/A.java"), "older, for A");
        send(A, List.of("src/B.java"), "newer, for B");
        ConversationService.Cursor elsewhere = unread(B).cursor();
        assertEquals(lineage(B), elsewhere.lineageId());
        assertEquals(store().conversationProjection().messages().getLast().sequence(), elsewhere.through());

        ConversationService.Unread own = unread(A, elsewhere);

        assertEquals(List.of("older, for A"), texts(own), "a position reached in lane B says nothing of lane A");
        assertEquals(lineage(A), own.cursor().lineageId());
        assertEquals(0, deliveryMarks());
        assertEquals(1, store().conversationProjection().unread(lineage(A)).size());
    }

    @Test
    void moreThanFifteenOtherLanesInOneAddressAreRefusedAsTooMany() throws Exception {
        List<String> others = new java.util.ArrayList<>(List.of("src/B.java", "src/C.java"));
        for (int number = 1; number <= 14; number++) {
            claim("inst-" + (10 + number), "src/Extra" + number + ".java");
            others.add("src/Extra" + number + ".java");
        }

        assertEquals(AgentStatus.COMPLETED, send(A, others.subList(0, 15), "fifteen others and the caller are sixteen").status());
        AgentResponse tooMany = send(A, others, "sixteen others");

        assertEquals(AgentStatus.BLOCKED, tooMany.status(), tooMany.toJson());
        assertEquals("CONVERSATION_HAS_TOO_MANY_MEMBERS", result(tooMany).get("reason"), tooMany.toJson());
        assertEquals(1, store().conversationProjection().messages().size());
    }

    @Test
    void aSessionWithoutAnOpenLaneIsRefusedAndHandedNothing() throws Exception {
        session("inst-5");

        AgentResponse refused = send("inst-5", List.of("src/B.java"), "hello");

        assertEquals(AgentStatus.BLOCKED, refused.status(), refused.toJson());
        assertEquals(AgentReason.COORDINATION_INTENT_REQUIRED, refused.reason());
        assertTrue(unread("inst-5").messages().isEmpty());
        assertTrue(conversations.history(projectRoot, PROVIDER, "inst-5").isEmpty());
    }
}
