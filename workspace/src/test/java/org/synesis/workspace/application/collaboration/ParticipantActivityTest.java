package org.synesis.workspace.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Pins what the time of a participant's latest verified call means to its readers, and that recording
 * it appends nothing to the coordination log.
 */
class ParticipantActivityTest {

    private final WorkspaceCollaborationService service = new WorkspaceCollaborationService();

    private static ProjectLocation project(Path root) throws Exception {
        Files.createDirectories(root);
        TestGit.run(root, "init");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        TestGit.run(root, "add", "README.md");
        TestGit.run(root, "config", "user.email", "synesis-test@example.invalid");
        TestGit.run(root, "config", "user.name", "Synesis Test");
        TestGit.run(root, "commit", "-m", "baseline");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        ProviderTestSupport.install(location, "codex");
        return location;
    }

    private void lane(ProjectLocation location, String chat, String path) throws Exception {
        new ProviderSessionBindingService().ensure(location, "codex", chat);
        service.announce(location.root(), "codex", chat, "goal " + chat, "done", List.of(ResourceSelector.pathExact(path)));
    }

    private long lastVerifiedActivity(Path root, String chat) throws Exception {
        ProjectLocation location = new ProjectApplicationService().locate(root);
        String participant = WorkspaceCollaborationService.participantHandle(
                new ProviderSessionBindingService().find(location, "codex", chat).orElseThrow().sessionId());
        return service.status(root).participants().stream().filter(candidate -> candidate.id().equals(participant))
                .mapToLong(Participant::lastVerifiedActivity).max().orElseThrow();
    }

    private long headSequence(ProjectLocation location) throws Exception {
        return location.openCoordinationStore().headSequence();
    }

    @Test
    void collaborationStatusReportsTheTimeOfTheLatestVerifiedCall(@TempDir Path temp) throws Exception {
        ProjectLocation location = project(temp.resolve("status"));
        lane(location, "chat-a", "src/a.txt");

        Thread.sleep(20);
        long before = System.currentTimeMillis();
        service.heartbeat(location.root(), "codex", "chat-a");
        long after = System.currentTimeMillis();

        long reported = lastVerifiedActivity(location.root(), "chat-a");
        assertTrue(reported >= before && reported <= after, "reported " + reported + " outside [" + before + ", " + after + "]");

        Thread.sleep(20);
        service.heartbeat(location.root(), "codex", "chat-a");
        assertTrue(lastVerifiedActivity(location.root(), "chat-a") > reported, "a later call moves the time forward");
    }

    @Test
    void theRunControllerSeesEventsAfterTheLastCallAsUnseen(@TempDir Path temp) throws Exception {
        ProjectLocation location = project(temp.resolve("wake"));
        lane(location, "chat-a", "src/a.txt");
        Thread.sleep(20);
        service.heartbeat(location.root(), "codex", "chat-a");
        Thread.sleep(20);
        lane(location, "chat-b", "src/b.txt");

        AgentRunController.Observation unseen = new EventLogObserver(location).observe();
        long seenByA = unseen.lastActivitySequence("codex", "chat-a").orElseThrow();
        assertTrue(seenByA < unseen.headSequence(), "chat-b's announcement came after chat-a's last call");

        Thread.sleep(20);
        service.heartbeat(location.root(), "codex", "chat-a");
        AgentRunController.Observation seen = new EventLogObserver(location).observe();
        assertEquals(seen.headSequence(), seen.lastActivitySequence("codex", "chat-a").orElseThrow(),
                "a call after the last event has looked at all of it");
    }

    @Test
    void aVerifiedCallAppendsNothingToTheLog(@TempDir Path temp) throws Exception {
        ProjectLocation location = project(temp.resolve("quiet"));
        lane(location, "chat-a", "src/a.txt");
        long head = headSequence(location);

        for (int call = 0; call < 3; call++) {
            service.heartbeat(location.root(), "codex", "chat-a");
        }

        assertEquals(head, headSequence(location));
        assertTrue(location.openCoordinationStore().events().stream().noneMatch(event -> event.type() == PredictionEventType.PARTICIPANT_HEARTBEAT));
    }

    @Test
    void aHeartbeatInAnOlderLogCountsWhenItIsLaterThanTheRecordedCall(@TempDir Path temp) throws Exception {
        ProjectLocation location = project(temp.resolve("older"));
        lane(location, "chat-a", "src/a.txt");
        service.heartbeat(location.root(), "codex", "chat-a");
        long recorded = lastVerifiedActivity(location.root(), "chat-a");

        Thread.sleep(20);
        String participant = WorkspaceCollaborationService.participantHandle(
                new ProviderSessionBindingService().find(location, "codex", "chat-a").orElseThrow().sessionId());
        PredictionEventStore store = location.openCoordinationStore();
        var identity = location.loadOrCreateIdentity();
        store.append(UUID.randomUUID(), PredictionEventType.PARTICIPANT_HEARTBEAT, identity.nodeId(),
                CollaborationCodec.encodeHeartbeat(participant), identity);
        long logged = lastVerifiedActivity(location.root(), "chat-a");
        assertTrue(logged > recorded, "the logged heartbeat is the later time");

        Thread.sleep(20);
        service.heartbeat(location.root(), "codex", "chat-a");
        assertTrue(lastVerifiedActivity(location.root(), "chat-a") > logged, "a later recorded call wins again");
    }

    @Test
    void aParticipantThatMadeNoCallKeepsZeroAndAnUnknownOneIsRefused(@TempDir Path temp) throws Exception {
        ProjectLocation location = project(temp.resolve("none"));
        lane(location, "chat-a", "src/a.txt");

        assertEquals(0L, lastVerifiedActivity(location.root(), "chat-a"));
        new ProviderSessionBindingService().ensure(location, "codex", "chat-c");
        IOException unknown = assertThrows(IOException.class, () -> service.heartbeat(location.root(), "codex", "chat-c"));
        assertEquals("PARTICIPANT_NOT_FOUND", unknown.getMessage());
    }
}
