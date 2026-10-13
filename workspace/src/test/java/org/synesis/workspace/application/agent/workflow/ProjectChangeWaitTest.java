package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies what ends a caller's hold on a project: any recorded event but liveness bookkeeping
 * and a mark of delivery, and nothing else.
 */
class ProjectChangeWaitTest {

    private static final Duration HOLD = Duration.ofSeconds(1);

    @TempDir
    Path tempDir;

    private ProjectLocation location;
    private String participant;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Synesis Test");
        TestGit.run(root, "config", "user.email", "synesis-test@example.invalid");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        TestGit.run(root, "add", "README.md");
        TestGit.run(root, "commit", "-m", "baseline");
        location = new ProjectApplicationService().init(root).location();
        ProviderTestSupport.install(location, "codex");
        new AgentSessionService().ensureSession(new SessionResolutionRequest(root, "codex", "waiting-connection", null, false));
        var binding = new ProviderSessionBindingService().find(location, "codex", "waiting-connection").orElseThrow();
        new ProviderSessionBindingService().verifyWorkspaceTrust(location, "codex", binding.sessionId(), Path.of(binding.worktreePath()));
        new WorkspaceCollaborationService().announce(root, "codex", "waiting-connection", "Build the file", "Passes its tests", List.of(ResourceSelector.pathExact("src/A.java")));
        participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
    }

    private void record(PredictionEventType type, byte[] payload) throws Exception {
        var identity = location.loadOrCreateIdentity();
        location.openCoordinationStore().append(UUID.randomUUID(), type, identity.nodeId(), payload, identity);
    }

    private void message() throws Exception {
        UUID sender = UUID.randomUUID();
        record(PredictionEventType.CONVERSATION_MESSAGE_SENT, ConversationMessage.encodeSent(UUID.randomUUID(), sender, List.of(sender, UUID.randomUUID()), "hello"));
    }

    private void delivery() throws Exception {
        record(PredictionEventType.CONVERSATION_MESSAGES_DELIVERED, ConversationMessage.encodeDelivered(UUID.randomUUID(), 0L));
    }

    private void heartbeat() throws Exception {
        record(PredictionEventType.PARTICIPANT_HEARTBEAT, CollaborationCodec.encodeHeartbeat(participant));
    }

    /** Records after a pause, from another thread, as another lane's process would. */
    private Thread later(Runnable recording) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                return;
            }
            recording.run();
        });
        thread.start();
        return thread;
    }

    private interface Recording {
        void record() throws Exception;
    }

    private Runnable unchecked(Recording recording) {
        return () -> {
            try {
                recording.record();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @Test
    void nothingRecordedAfterTheKnownHeadHoldsForAboutTheGivenTime() {
        long head = ProjectChangeWait.head(location);
        long started = System.nanoTime();

        assertFalse(ProjectChangeWait.awaitChange(location, head, HOLD));

        long took = millisSince(started);
        assertTrue(took >= 800L, "held for most of the time: " + took);
        assertTrue(took < 4_000L, "and not for much longer: " + took);
    }

    @Test
    void aMessageRecordedFromAnotherThreadDuringTheHoldEndsItEarly() throws Exception {
        long head = ProjectChangeWait.head(location);
        Thread other = later(unchecked(this::message));
        long started = System.nanoTime();

        assertTrue(ProjectChangeWait.awaitChange(location, head, Duration.ofSeconds(10)));

        assertTrue(millisSince(started) < 5_000L, "ended long before the ten seconds: " + millisSince(started));
        other.join();
    }

    @Test
    void anEventThatIsNeitherLivenessNorDeliveryEndsTheHoldEarly() throws Exception {
        long head = ProjectChangeWait.head(location);
        Thread other = later(unchecked(() -> record(PredictionEventType.OPERATOR_REQUEST_RAISED, OperatorRequest.raised("opr_synesis000000001", OperatorRequest.BOUND_CONSUMER_ABSENT, "a consumer is absent", "").encodeRaised())));
        long started = System.nanoTime();

        assertTrue(ProjectChangeWait.awaitChange(location, head, Duration.ofSeconds(10)));

        assertTrue(millisSince(started) < 5_000L, "ended long before the ten seconds: " + millisSince(started));
        other.join();
    }

    @Test
    void aHeartbeatAloneDoesNotEndTheHold() throws Exception {
        long head = ProjectChangeWait.head(location);
        Thread other = later(unchecked(this::heartbeat));
        long started = System.nanoTime();

        assertFalse(ProjectChangeWait.awaitChange(location, head, HOLD));

        assertTrue(millisSince(started) >= 800L, "the heartbeat did not cut the hold short");
        other.join();
        assertEquals(head + 1, ProjectChangeWait.head(location), "the heartbeat was recorded within the hold");
    }

    @Test
    void aMarkOfDeliveryAloneDoesNotEndTheHold() throws Exception {
        long head = ProjectChangeWait.head(location);
        Thread other = later(unchecked(this::delivery));
        long started = System.nanoTime();

        assertFalse(ProjectChangeWait.awaitChange(location, head, HOLD));

        assertTrue(millisSince(started) >= 800L, "the mark of delivery did not cut the hold short");
        other.join();
        assertEquals(head + 1, ProjectChangeWait.head(location), "the mark was recorded within the hold");
    }

    @Test
    void anEventThatCountsEndsTheHoldEvenWhenBookkeepingCameFirst() throws Exception {
        long head = ProjectChangeWait.head(location);
        heartbeat();
        delivery();
        Thread other = later(unchecked(this::message));
        long started = System.nanoTime();

        assertTrue(ProjectChangeWait.awaitChange(location, head, Duration.ofSeconds(10)));

        assertTrue(millisSince(started) < 5_000L, "ended long before the ten seconds: " + millisSince(started));
        other.join();
    }

    @Test
    void aZeroOrNegativeDurationHoldsNothingEvenWhenThereIsSomethingNew() throws Exception {
        long head = ProjectChangeWait.head(location);
        message();
        long started = System.nanoTime();

        assertFalse(ProjectChangeWait.awaitChange(location, head, Duration.ZERO));
        assertFalse(ProjectChangeWait.awaitChange(location, head, Duration.ofSeconds(-5)));

        assertTrue(millisSince(started) < 300L, "answered at once: " + millisSince(started));
    }

    @Test
    void theHeadIsTheNumberOfRecordedEvents() throws Exception {
        long before = ProjectChangeWait.head(location);
        assertEquals(location.openCoordinationStore().events().size(), before);

        message();
        heartbeat();
        delivery();

        assertEquals(before + 3, ProjectChangeWait.head(location));
        assertEquals(location.openCoordinationStore().events().size(), ProjectChangeWait.head(location));
    }
}
