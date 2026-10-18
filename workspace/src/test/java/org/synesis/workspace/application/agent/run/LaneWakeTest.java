package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * An agent told WAIT ends its turn and its Stop hook holds it until something is recorded for its own lane
 * (ADR-0099). These drive the hold against a real log: what wakes it, what does not, and how often.
 */
@Timeout(60)
class LaneWakeTest {

    private static final String CONNECTION = "conn-a";

    @TempDir
    Path temp;

    private final UUID project = UUID.randomUUID();
    private ProjectLocation location;
    private NodeIdentity identity;
    private WorkIntent laneA;
    private WorkIntent laneB;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("project");
        location = new ProjectLocation(root, root.resolve(".synesis"), root.resolve(".synesis/project.json"), temp.resolve("profile"), project, Instant.now());
        identity = new IdentityBootstrap(temp.resolve("profile/link")).loadOrCreate().identity();
        laneA = announce("agt-a", "src/a.py");
        laneB = announce("agt-b", "src/b.py");
    }

    static WorkIntent lane(UUID project, String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, project, participant, "claude", UUID.randomUUID(), "Build " + path, "It works", "base-commit",
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private WorkIntent announce(String participant, String path) throws Exception {
        WorkIntent intent = lane(project, participant, path);
        assertTrue(intents().announce(intent).acquired());
        return intent;
    }

    private WorkIntentService intents() throws Exception {
        return new WorkIntentService(new PredictionEventStore(location.coordinationDirectory(), project), identity);
    }

    private void request(String from, WorkIntent to) throws Exception {
        intents().request(from, to.intentId(), CoordinationRequest.Kind.CONTRACT, "Please publish the contract of " + to.selectors().getFirst());
    }

    private void message(WorkIntent from, WorkIntent to) throws Exception {
        new PredictionEventStore(location.coordinationDirectory(), project).append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGE_SENT, identity.nodeId(),
                ConversationMessage.encodeSent(UUID.randomUUID(), from.authorityLineageId(), List.of(from.authorityLineageId(), to.authorityLineageId()), "Is slug() lower case?"), identity);
    }

    private void askOperator(String id, WorkIntent lane) throws Exception {
        new PredictionEventStore(location.coordinationDirectory(), project).append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(),
                OperatorRequest.askedByLane(id, lane.authorityLineageId().toString(), "Please install the test runner", "").encodeRaised(), identity);
    }

    private void closeOperatorRequest(String id) throws Exception {
        new PredictionEventStore(location.coordinationDirectory(), project).append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_CLOSED, identity.nodeId(),
                OperatorRequest.encodeClosed(id, "Installed."), identity);
    }

    private void noteWait(String participant) throws Exception {
        LaneWake.noteWait(location, CONNECTION, participant, null, OptionalLong.empty());
    }

    private Optional<String> hold(Duration atMost) {
        return LaneWake.awaitAddressed(location, CONNECTION, atMost);
    }

    @Test
    void anAgentThatWasNotToldToWaitIsLetStopAtOnce() {
        long started = System.nanoTime();
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(20)));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void aRequestToTheWaitingLaneWakesItAndSaysWhatArrived() throws Exception {
        noteWait("agt-a");
        CompletableFuture<Optional<String>> held = CompletableFuture.supplyAsync(() -> hold(Duration.ofSeconds(20)));
        Thread.sleep(600);
        assertTrue(!held.isDone(), "held until something arrives");

        request("agt-b", laneA);

        String reason = held.get(10, TimeUnit.SECONDS).orElseThrow();
        assertTrue(reason.contains("coordination request"), reason);
        assertTrue(reason.contains("get_next_action"), reason);
    }

    @Test
    void aMessageToTheWaitingLaneWakesIt() throws Exception {
        noteWait("agt-a");
        message(laneB, laneA);
        assertTrue(hold(Duration.ofSeconds(10)).orElseThrow().contains("message"));
    }

    /**
     * A lane that asked the operator and ended its turn to wait for the answer kept waiting after the
     * operator closed its request: nothing in its lane view changed (ADR-0099 amendment).
     */
    @Test
    void theOperatorClosingARequestTheLaneAskedWakesIt() throws Exception {
        askOperator("opr_a", laneA);
        askOperator("opr_b", laneB);
        noteWait("agt-a");
        closeOperatorRequest("opr_b");
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(1)), "another lane's request closing is not addressed to it");

        noteWait("agt-a");
        CompletableFuture<Optional<String>> held = CompletableFuture.supplyAsync(() -> hold(Duration.ofSeconds(20)));
        Thread.sleep(600);
        closeOperatorRequest("opr_a");

        assertTrue(held.get(10, TimeUnit.SECONDS).orElseThrow().contains("the operator closed a request your lane put to it"));
    }

    @Test
    void workBetweenOtherLanesDoesNotWakeIt() throws Exception {
        WorkIntent laneC = announce("agt-c", "src/c.py");
        noteWait("agt-a");
        request("agt-c", laneB);
        message(laneC, laneB);
        announce("agt-d", "src/d.py");

        long started = System.nanoTime();
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(2)));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(1900), "held for the whole time");
    }

    @Test
    void whatArrivedBetweenTheWaitAnswerAndTheStopStillWakesIt() throws Exception {
        noteWait("agt-a");
        request("agt-b", laneA);

        long started = System.nanoTime();
        assertTrue(hold(Duration.ofSeconds(20)).isPresent());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void theSameEventDoesNotWakeItTwice() throws Exception {
        noteWait("agt-a");
        request("agt-b", laneA);
        assertTrue(hold(Duration.ofSeconds(5)).isPresent());
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(1)));
    }

    @Test
    void wakesAreBoundedUntilTheAgentIsToldToWaitAgain() throws Exception {
        noteWait("agt-a");
        for (int wake = 1; wake <= LaneWake.MAX_WAKES_WITHOUT_A_WAIT; wake++) {
            message(laneB, laneA);
            assertTrue(hold(Duration.ofSeconds(5)).isPresent(), "wake " + wake);
        }
        message(laneB, laneA);
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(1)), "an agent that ignores its wakes is let stop");

        noteWait("agt-a");
        message(laneB, laneA);
        assertTrue(hold(Duration.ofSeconds(5)).isPresent(), "told to wait again, it can be woken again");
    }

    @Test
    void itsLaneEndingWakesItOnceSoItLearnsWhatIsLeft() throws Exception {
        noteWait("agt-a");
        intents().release(laneA.intentId(), "agt-a");
        assertTrue(hold(Duration.ofSeconds(5)).orElseThrow().contains("your lane has ended"));
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(1)));
    }

    @Test
    void theTimeItsWaitEndsWakesItWithoutAnyEvent() throws Exception {
        LaneWake.noteWait(location, CONNECTION, "agt-a", null, OptionalLong.of(System.currentTimeMillis() + 1_200));
        long started = System.nanoTime();
        assertTrue(hold(Duration.ofSeconds(20)).orElseThrow().contains("time Synesis gave your wait has passed"));
        long waited = System.nanoTime() - started;
        assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(800) && waited < TimeUnit.SECONDS.toNanos(10), "woken at its time");
    }

    @Test
    void anAnswerOtherThanWaitMeansTheAgentIsNotHeld() throws Exception {
        noteWait("agt-a");
        LaneWake.clearWait(location, CONNECTION);
        request("agt-b", laneA);
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(5)));
    }

    @Test
    void aParticipantWithoutAnOpenLaneIsNotHeld() throws Exception {
        noteWait("agt-nobody");
        long started = System.nanoTime();
        assertEquals(Optional.empty(), hold(Duration.ofSeconds(20)));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
    }
}
