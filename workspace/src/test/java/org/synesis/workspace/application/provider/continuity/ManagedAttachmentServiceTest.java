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
    void productionIssuePathDerivesThreadFromDurableOwnership() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-owned-attachment-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        var ownership = new ProviderThreadOwnershipStore(root.resolve("ownership"))
                .acquire(location.projectId().toString(), "codex", "thread-owned", "binding-a");

        var issued = new ManagedAttachmentService().issueFromOwnership(location,
                location.projectId().toString(), "codex", "binding-a", "normal-provider-home", ownership, store);

        assertEquals("thread-owned", issued.record().threadId());
        assertEquals(ProviderContinuityMode.MANAGED_CONTINUITY, issued.record().mode());
        assertEquals(ManagedAttachmentRecord.Status.PENDING_ACTIVATION, store.read().orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> new ManagedAttachmentService().authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-owned", 1L,
                        issued.proof()), location.projectId().toString(), store));
        assertEquals(ManagedAttachmentRecord.Status.PENDING_ACTIVATION,
                new ManagedAttachmentService().authenticateTransport(location,
                        new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-owned", 1L,
                                issued.proof()), location.projectId().toString(), store).status());
        new ManagedAttachmentService().activate(store, 1L);
        assertEquals(ManagedAttachmentRecord.Status.ACTIVE, store.read().orElseThrow().status());
    }

    @Test
    void transportAuthenticationStillRejectsWrongProofAndStaleOrTerminalPendingRecords() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-transport-fence-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        var ownership = new ProviderThreadOwnershipStore(root.resolve("ownership"))
                .acquire(location.projectId().toString(), "codex", "thread-owned", "binding-a");
        var service = new ManagedAttachmentService();
        var issued = service.issueFromOwnership(location, location.projectId().toString(), "codex", "binding-a",
                "normal-provider-home", ownership, store);

        assertThrows(IllegalStateException.class, () -> service.authenticateTransport(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-owned", 1L,
                        "0".repeat(64)), location.projectId().toString(), store));
        assertThrows(IllegalStateException.class, () -> service.authenticateTransport(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-owned", 2L,
                        issued.proof()), location.projectId().toString(), store));
        service.terminalize(store);
        assertThrows(IllegalStateException.class, () -> service.authenticateTransport(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-owned", 1L,
                        issued.proof()), location.projectId().toString(), store));
    }

    @Test
    void oneTransportConnectionWinsPerPendingGeneration() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-transport-slot-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var ownership = new ProviderThreadOwnershipStore(root.resolve("ownership"))
                .acquire(location.projectId().toString(), "codex", "thread-owned", "binding-a");
        new ManagedAttachmentService().issueFromOwnership(location, location.projectId().toString(), "codex",
                "binding-a", "normal-provider-home", ownership, store);
        ManagedAttachmentService service = new ManagedAttachmentService();

        try (ManagedAttachmentService.TransportLease first = service.acquireTransport(location, "binding-a", 1L)) {
            assertTrue(first != null);
            assertThrows(Exception.class, () -> service.acquireTransport(location, "binding-a", 1L));
        }
        try (ManagedAttachmentService.TransportLease second = service.acquireTransport(location, "binding-a", 1L)) {
            assertTrue(second != null);
        }
    }

    @Test
    void ownershipReplacementRejectsAThreadSwitch() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-owned-replacement-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = new ManagedAttachmentStore(root.resolve("adapter/attachment.json"));
        var ownership = new ProviderThreadOwnershipStore(root.resolve("ownership"))
                .acquire(location.projectId().toString(), "codex", "thread-owned", "binding-a");
        var service = new ManagedAttachmentService();
        var issued = service.issueFromOwnership(location, location.projectId().toString(), "codex", "binding-a",
                "normal-provider-home", ownership, store);

        assertThrows(IllegalStateException.class, () -> service.reattachFromOwnership(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-other", 1L,
                        issued.proof()), location.projectId().toString(), ownership, "normal-provider-home", store,
                true));
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

    @Test
    void activeAttachmentWithoutTrustedDeathReceiptCannotBeReplaced() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-no-death-receipt-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        var issued = new ManagedAttachmentService().issue(location, location.projectId().toString(), "codex",
                "binding-a", "thread-a", "home-a", store);

        assertThrows(IllegalStateException.class, () -> new ManagedAttachmentService().replaceAfterTrustedDeath(
                location, location.projectId().toString(), "codex", "binding-a", "home-b", ownership, store,
                ManagedRuntimeDeathReceiptStore.storeFor(location), 1L));
        assertEquals(issued.record(), store.read().orElseThrow());
    }

    @Test
    void trustedDeathReceiptAllowsProoflessReplacementAndPreservesOwnership() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-proofless-replacement-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .markPersistenceReady(location.projectId().toString(), "codex", "thread-a", "binding-a");
        var service = new ManagedAttachmentService();
        var issued = service.issue(location, location.projectId().toString(), "codex", "binding-a", "thread-a",
                "home-a", store);
        var receiptStore = ManagedRuntimeDeathReceiptStore.storeFor(location);
        receiptStore.write(new ManagedRuntimeDeathReceipt(1, location.projectId().toString(), "codex", "binding-a",
                1L, 7123L, 991L, "codex.exe", "codex app-server", "managed-supervisor", 1L,
                System.currentTimeMillis()));

        var replacement = service.replaceAfterTrustedDeath(location, location.projectId().toString(), "codex",
                "binding-a", "home-b", ownership, store, receiptStore, 1L);

        assertEquals(2L, replacement.record().generation());
        assertEquals("thread-a", replacement.record().threadId());
        assertNotEquals(issued.proof(), replacement.proof());
        assertEquals(ManagedAttachmentRecord.Status.PENDING_ACTIVATION, replacement.record().status());
        assertEquals(ownership, ProviderThreadOwnershipStore.storeFor(location)
                .findByBinding("codex", "binding-a").orElseThrow());
        assertThrows(IllegalStateException.class, () -> service.authenticate(location,
                new RuntimeAuthenticator.AttachmentRequest("codex", "binding-a", "thread-a", 1L,
                        issued.proof()), location.projectId().toString(), store));
        assertThrows(IllegalStateException.class, () -> service.replaceAfterTrustedDeath(location,
                location.projectId().toString(), "codex", "binding-a", "home-c", ownership, store,
                receiptStore, 1L));
    }

    @Test
    void deathReceiptMustMatchExactGenerationAndBinding() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-death-scope-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        new ManagedAttachmentService().issue(location, location.projectId().toString(), "codex", "binding-a",
                "thread-a", "home-a", store);
        var receiptStore = ManagedRuntimeDeathReceiptStore.storeFor(location);
        receiptStore.write(new ManagedRuntimeDeathReceipt(1, location.projectId().toString(), "codex", "binding-a",
                2L, 7123L, 991L, "codex.exe", "codex app-server", "managed-supervisor", 1L,
                System.currentTimeMillis()));

        assertThrows(IllegalStateException.class, () -> new ManagedAttachmentService().replaceAfterTrustedDeath(
                location, location.projectId().toString(), "codex", "binding-a", "home-b", ownership, store,
                receiptStore, 1L));
    }

    @Test
    void provisionalProviderThreadCannotBeReplacedAfterTrustedDeath() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-provisional-replacement-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        new ManagedAttachmentService().issue(location, location.projectId().toString(), "codex", "binding-a",
                "thread-a", "home-a", store);
        var receiptStore = ManagedRuntimeDeathReceiptStore.storeFor(location);
        receiptStore.write(new ManagedRuntimeDeathReceipt(1, location.projectId().toString(), "codex", "binding-a",
                1L, 7123L, 991L, "codex.exe", "codex app-server", "managed-supervisor", 1L,
                System.currentTimeMillis()));

        assertThrows(IllegalStateException.class, () -> new ManagedAttachmentService().replaceAfterTrustedDeath(
                location, location.projectId().toString(), "codex", "binding-a", "home-b", ownership, store,
                receiptStore, 1L));
        assertEquals(1L, store.read().orElseThrow().generation());
    }

    @Test
    void pendingGenerationCanBindThreadAfterStartBeforeActivation() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-pending-thread-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var service = new ManagedAttachmentService();
        var issued = service.issuePending(location, location.projectId().toString(), "codex", "binding-a", "home-a",
                store);

        assertEquals(null, issued.record().threadId());
        var bound = service.bindProviderThread(store, "codex", "binding-a", "thread-a", 1L);

        assertEquals("thread-a", bound.threadId());
        assertEquals(ManagedAttachmentRecord.Status.PENDING_ACTIVATION, bound.status());
        assertEquals(bound, service.bindProviderThread(store, "codex", "binding-a", "thread-a", 1L));
        assertThrows(IllegalStateException.class,
                () -> service.bindProviderThread(store, "codex", "binding-a", "thread-b", 1L));
        service.activate(store, 1L);
        assertEquals("thread-a", store.read().orElseThrow().threadId());
    }

    @Test
    void twoProoflessReplacementAttemptsHaveOneWinner() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-proofless-race-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .markPersistenceReady(location.projectId().toString(), "codex", "thread-a", "binding-a");
        new ManagedAttachmentService().issue(location, location.projectId().toString(), "codex", "binding-a",
                "thread-a", "home-a", store);
        var receiptStore = ManagedRuntimeDeathReceiptStore.storeFor(location);
        receiptStore.write(new ManagedRuntimeDeathReceipt(1, location.projectId().toString(), "codex", "binding-a",
                1L, 7123L, 991L, "codex.exe", "codex app-server", "managed-supervisor", 1L,
                System.currentTimeMillis()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        new ManagedAttachmentService().replaceAfterTrustedDeath(location,
                                location.projectId().toString(), "codex", "binding-a", "home-b", ownership, store,
                                receiptStore, 1L);
                        winners.incrementAndGet();
                    } catch (Exception expected) {
                        // The generation/proof compare-and-replace fence rejects the loser.
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

    @Test
    void terminalAttachmentCannotUseDeathReceiptForReplacement() throws Exception {
        Path root = Files.createTempDirectory("synesis-managed-terminal-death-");
        ProjectApplicationService.ProjectLocation location = new ProjectApplicationService().init(root).location();
        ManagedAttachmentStore store = ManagedAttachmentService.storeFor(location, "binding-a");
        var ownership = ProviderThreadOwnershipStore.storeFor(location)
                .acquire(location.projectId().toString(), "codex", "thread-a", "binding-a");
        new ManagedAttachmentService().issue(location, location.projectId().toString(), "codex", "binding-a",
                "thread-a", "home-a", store);
        var service = new ManagedAttachmentService();
        service.terminalize(store);
        var receiptStore = ManagedRuntimeDeathReceiptStore.storeFor(location);
        receiptStore.write(new ManagedRuntimeDeathReceipt(1, location.projectId().toString(), "codex", "binding-a",
                1L, 7123L, 991L, "codex.exe", "codex app-server", "managed-supervisor", 1L,
                System.currentTimeMillis()));

        assertThrows(IllegalStateException.class, () -> service.replaceAfterTrustedDeath(location,
                location.projectId().toString(), "codex", "binding-a", "home-b", ownership, store,
                receiptStore, 1L));
    }
}
