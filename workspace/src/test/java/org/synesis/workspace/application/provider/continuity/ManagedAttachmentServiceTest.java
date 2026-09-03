package org.synesis.workspace.application.provider.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/** Verifies hash-only managed attachment authentication and fencing. */
final class ManagedAttachmentServiceTest {

    @Test
    void issuesHashOnlyProofAndAuthenticatesExactAttachment() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-attachment-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        ManagedAttachmentService service = new ManagedAttachmentService();

        ManagedAttachmentService.IssuedAttachment issued = service.issue(location, location.projectId().toString(),
                "codex", "binding-a", "thread-a", "home-a", store);

        assertEquals(64, issued.proof().length());
        assertNotEquals(issued.proof(), issued.record().proofHash());
        assertTrue(!Files.readString(store.file()).contains(issued.proof()));
        var authenticated = service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), store);
        assertEquals("binding-a", authenticated.bindingSessionId());
        assertEquals(1L, authenticated.attachmentGeneration());
    }

    @Test
    void rejectsWrongScopeProofThreadGenerationAndReplayAfterRotation() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-fence-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        ManagedAttachmentService service = new ManagedAttachmentService();
        var issued = service.issue(location, location.projectId().toString(), "codex", "binding-a", "thread-a",
                "home-a", store);

        assertThrows(IllegalStateException.class, () -> service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-b", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), store));
        assertThrows(IllegalStateException.class, () -> service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-b", 1L,
                        issued.proof()), location.projectId().toString(), store));
        assertThrows(IllegalStateException.class, () -> service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 2L,
                        issued.proof()), location.projectId().toString(), store));

        var replacement = service.reattach(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), "thread-a", "home-a", store, true);
        assertEquals(2L, replacement.record().generation());
        assertNotEquals(issued.proof(), replacement.proof());
        assertThrows(IllegalStateException.class, () -> service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), store));
        assertTrue(Files.readString(store.file()).contains(replacement.record().proofHash()));
    }

    @Test
    void terminalAttachmentCannotBeRevived() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-terminal-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        ManagedAttachmentService service = new ManagedAttachmentService();
        var issued = service.issue(location, location.projectId().toString(), "codex", "binding-a", "thread-a",
                "home-a", store);
        service.terminalize(store);
        assertThrows(IllegalStateException.class, () -> service.reattach(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), "thread-a", "home-a", store, true));
    }

    @Test
    void disconnectedAttachmentCanRotateAfterProvenStop() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-disconnected-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        ManagedAttachmentService service = new ManagedAttachmentService();
        var issued = service.issue(location, location.projectId().toString(), "codex", "binding-a", "thread-a",
                "home-a", store);
        service.markDisconnected(store);

        var replacement = service.reattach(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), "thread-b", "home-b", store, true);

        assertEquals(2L, replacement.record().generation());
        assertEquals("thread-b", replacement.record().threadId());
        assertEquals(ManagedAttachmentRecord.Status.ACTIVE, store.read().orElseThrow().status());
    }

    @Test
    void crossServiceReplacementRaceHasOneWinner() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-race-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        var firstService = new ManagedAttachmentService();
        var secondService = new ManagedAttachmentService();
        var issued = firstService.issue(location, location.projectId().toString(), "codex", "binding-a", "thread-a",
                "home-a", store);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var service : new ManagedAttachmentService[]{firstService, secondService}) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        service.reattach(location,
                                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                                        issued.proof()), location.projectId().toString(), "thread-a", "home-a", store,
                                true);
                        winners.incrementAndGet();
                    } catch (Exception expected) {
                        // Exactly one caller must lose the compare-and-replace fence.
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        assertEquals(1, winners.get());
        assertEquals(2L, store.read().orElseThrow().generation());
    }
}
