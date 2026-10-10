package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.projectrecord.domain.ProjectConfig;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.hook.HookApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderContinuityMode;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Verifies project-scoped provider session identity and trust bootstrap.
 */
@SuppressWarnings("MultipleOccurrences")
final class ProviderSessionBindingServiceTest {

    private static void git(Path root, String... arguments) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, arguments);
    }

    private static String gitHead(Path root) throws Exception {
        Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static Optional<Path> packagedSynesisAlias() throws Exception {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return Optional.empty();
        }
        Path normal = Path.of(localAppData, "Synesis");
        Path packages = Path.of(localAppData, "Packages");
        if (!Files.isDirectory(normal) || !Files.isDirectory(packages)) {
            return Optional.empty();
        }
        try (var entries = Files.list(packages)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("OpenAI.Codex_")).map(path -> path.resolve("LocalCache/Local/Synesis")).filter(Files::isDirectory).filter(path -> {
                try {
                    return Files.isSameFile(normal, path);
                } catch (Exception unavailable) {
                    return false;
                }
            }).findFirst();
        }
    }

    private static ProviderSessionBindingService.Binding withWorktreePath(ProviderSessionBindingService.Binding binding, Path worktree) {
        return new ProviderSessionBindingService.Binding(binding.schemaVersion(),
                binding.sessionId(),
                binding.projectId(),
                binding.nodeId(),
                binding.provider(),
                binding.providerInstanceFingerprint(),
                binding.supervisorId(),
                binding.workerId(),
                binding.worktreeId(),
                worktree.toString(),
                binding.controlCheckoutPath(),
                binding.branch(),
                binding.baseCommit(),
                binding.gitCommonDir(),
                binding.creationState(),
                binding.verificationState(),
                binding.lastSeenState(),
                binding.status(),
                binding.createdAtEpochMillis(),
                binding.lastSeenEpochMillis(),
                binding.lastVerifiedProjectSequence(),
                binding.providerTrustState(),
                binding.bindingVersion(),
                binding.completedAt());
    }

    @Test
    void bindsAndResumesByExplicitProviderInstanceWithoutChangingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-binding-");
        ProjectLocation location = new ProjectApplicationService().init(root).location();
        String projectId = location.projectId().toString();
        String nodeId = new ProjectApplicationService().init(root).identity().nodeId();
        ProviderSessionBindingService service = new ProviderSessionBindingService();

        var first = service.ensure(location, "codex", "chat-a");
        var resumed = service.ensure(location, "codex", "chat-a");
        var second = service.ensure(location, "codex", "chat-b");
        var otherProvider = service.ensure(location, "claude", "chat-a");

        assertEquals(projectId, first.binding().projectId());
        assertEquals(nodeId, first.binding().nodeId());
        assertEquals(first.binding().sessionId(), resumed.binding().sessionId());
        assertEquals(first.binding().supervisorId(), resumed.binding().supervisorId());
        assertNotEquals(first.binding().sessionId(), second.binding().sessionId());
        assertNotEquals(first.binding().sessionId(), otherProvider.binding().sessionId());
        assertEquals("WORKSPACE_UNVERIFIED", first.binding().providerTrustState());
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertEquals(3, paths.count());
        }
    }

    @Test
    void completionRetainsBindingForRemainingOwnedIntent() throws Exception {
        assertCompletionWaitsForOwnedIntents(false);
    }

    @Test
    void integrationCompletionRetainsBindingForRemainingOwnedIntent() throws Exception {
        assertCompletionWaitsForOwnedIntents(true);
    }

    @Test
    void integrationCompletionReusesHeldProjectLock() throws Exception {
        assertCompletionWaitsForOwnedIntents(true, true);
    }

    private static void assertCompletionWaitsForOwnedIntents(boolean bySession) throws Exception {
        assertCompletionWaitsForOwnedIntents(bySession, false);
    }

    private static void assertCompletionWaitsForOwnedIntents(boolean bySession, boolean heldLock) throws Exception {
        Path root = Files.createTempDirectory("synesis-session-multiple-intents-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "multiple@example.invalid");
        git(root, "config", "user.name", "Multiple Intents Test");
        git(root, "commit", "-m", "baseline");
        var service = new ProviderSessionBindingService();
        var binding = service.ensure(location, "codex", "multiple-intents").binding();
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId());
        var intents = new WorkIntentService(store, identity);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID id : List.of(first, second)) {
            assertTrue(intents.announce(new WorkIntent(id,
                    location.projectId(),
                    participant,
                    "codex",
                    UUID.randomUUID(),
                    "shared goal",
                    "complete owned responsibility",
                    binding.baseCommit(),
                    List.of(ResourceSelector.pathExact(id + ".py")),
                    1,
                    UUID.randomUUID(),
                    WorkIntent.Status.ANNOUNCED)).acquired());
        }
        intents.release(first, participant);
        boolean completed = completeBinding(service, location, binding.sessionId(), bySession, heldLock);
        assertFalse(completed, "one released lane cannot terminalize its sibling's exact binding");
        assertEquals("BOUND", service.find(location, "codex", "multiple-intents").orElseThrow().status());
        assertEquals(second, new PredictionEventStore(store.rootDirectory(), location.projectId()).collaborationProjection().activeIntents().getFirst().intentId());
        intents.release(second, participant);
        assertTrue(completeBinding(service, location, binding.sessionId(), bySession, heldLock));
        assertEquals("COMPLETED", service.find(location, "codex", "multiple-intents").orElseThrow().status());
    }

    private static boolean completeBinding(ProviderSessionBindingService service, ProjectLocation location, String sessionId, boolean bySession, boolean heldLock) throws Exception {
        if (heldLock) {
            try (var lock = org.synesis.coordination.persistence.ProjectAppendLock.acquire(location.root().resolve(".synesis/coordination"))) {
                return service.completeBySessionId(location, "codex", sessionId, lock);
            }
        }
        return bySession ? service.completeBySessionId(location, "codex", sessionId) : service.complete(location, "codex", "multiple-intents");
    }

    @Test
    void acceptsTheSameWorktreeThroughTheCodexPackagePathAlias() throws Exception {
        Optional<Path> aliasRoot = packagedSynesisAlias();
        Assumptions.assumeTrue(aliasRoot.isPresent(), "Codex package-local Synesis alias is unavailable on this host");

        Path root = Files.createTempDirectory("synesis-session-package-alias-");
        git(root, "init");
        git(root, "config", "user.email", "package-alias@example.invalid");
        git(root, "config", "user.name", "Package Alias Test");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "baseline");
        var location = new ProjectApplicationService().init(root).location();
        var service = new ProviderSessionBindingService();
        var original = service.ensure(location, "codex", "package-alias").binding();
        Path alias = aliasRoot.get().resolve("workspaces").resolve(original.projectId()).resolve("worktrees").resolve(original.sessionId());
        Path registered = Path.of(original.worktreePath());
        assertTrue(Files.isSameFile(registered, alias));

        var aliasedBinding = withWorktreePath(original, alias);
        var verification = service.verifyWorkspace(location, aliasedBinding, alias);

        assertTrue(verification.verified(), verification::code);
        git(root, "worktree", "remove", "--force", registered.toString());
    }

    @Test
    void authorityResolverRejectsAProviderSiblingAndUnknownConnection() throws Exception {
        Path root = Files.createTempDirectory("synesis-exact-authority-");
        var location = new ProjectApplicationService().init(root).location();
        var service = new ProviderSessionBindingService();
        service.ensure(location, "codex", "chat-a");
        service.ensure(location, "codex", "chat-b");

        var resolver = new SessionAuthorityResolver(service);
        var first = resolver.resolve(location, "codex", "chat-a");
        assertEquals("codex", first.provider());
        assertEquals("BOUND", first.status());
        assertThrows(IllegalStateException.class, () -> resolver.resolve(location, "codex", "chat-c"));
    }

    @Test
    void fallbackEvidenceIsExplicitlyMarkedAndDoesNotClaimChatIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-fallback-");
        var location = new ProjectApplicationService().init(root).location();
        var result = new ProviderSessionBindingService().ensure(location, "codex", null);

        assertTrue(result.fallbackEvidence());
        assertTrue(Files.exists(root.resolve(".synesis/local/providers/codex.bootstrap-key")));
    }

    @Test
    void resolvesTheExactConnectionInsteadOfTheNewestProviderBinding() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-exact-connection-");
        var location = new ProjectApplicationService().init(root).location();
        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "connection-a").binding();
        var second = service.ensure(location, "codex", "connection-b").binding();

        assertEquals(first.sessionId(), service.find(location, "codex", "connection-a").orElseThrow().sessionId());
        assertEquals(second.sessionId(), service.find(location, "codex", "connection-b").orElseThrow().sessionId());
        assertTrue(service.find(location, "codex", "connection-missing").isEmpty());
    }

    @Test
    void reallocatesAStaleCleanWorkerAndBlocksAStaleDirtyWorker() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-recovery-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "recovery@example.invalid");
        git(root, "config", "user.name", "Recovery Test");
        git(root, "commit", "-m", "baseline");
        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "recovery-clean").binding();
        Path cleanWorker = Path.of(first.worktreePath());
        Files.writeString(cleanWorker.resolve("README.md"), "advanced\n");
        git(cleanWorker, "add", "README.md");
        git(cleanWorker, "commit", "-m", "unexpected worker advance");
        var recovered = service.ensure(location, "codex", "recovery-clean").binding();
        assertNotEquals(first.sessionId(), recovered.sessionId());
        assertNotEquals(first.worktreePath(), recovered.worktreePath());

        var dirty = service.ensure(location, "codex", "recovery-dirty").binding();
        Path dirtyWorktree = Path.of(dirty.worktreePath());
        Files.writeString(dirtyWorktree.resolve("README.md"), "dirty\n");
        git(dirtyWorktree, "add", "README.md");
        git(dirtyWorktree, "commit", "-m", "worker change");
        Files.writeString(dirtyWorktree.resolve("README.md"), "dirty again\n");
        assertThrows(ProviderSessionBindingService.BindingException.class, () -> service.ensure(location, "codex", "recovery-dirty"));
        assertTrue(Files.exists(dirtyWorktree));
    }

    @Test
    void refreshesClaimedDirtyWorkAfterDisjointControlAdvance() throws Exception {
        Path root = Files.createTempDirectory("synesis-claimed-dirty-refresh-");
        git(root, "init");
        Files.writeString(root.resolve("owned.txt"), "baseline\n");
        Files.writeString(root.resolve("deleted.txt"), "delete me\n");
        Files.writeString(root.resolve("peer.txt"), "peer baseline\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        var location = new ProjectApplicationService().init(root).location();
        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "dirty-refresh").binding();
        Path worker = Path.of(first.worktreePath());
        String participant = WorkspaceCollaborationService.participantHandle(first.sessionId());
        var store = new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        WorkIntent intent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                participant,
                "codex",
                UUID.randomUUID(),
                "Implement owned files",
                "Owned behavior passes",
                first.baseCommit(),
                List.of(ResourceSelector.pathExact("owned.txt"), ResourceSelector.pathExact("deleted.txt"), ResourceSelector.pathExact("new.bin")),
                1,
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());
        Files.writeString(worker.resolve("owned.txt"), "unfinished\r\n");
        Files.delete(worker.resolve("deleted.txt"));
        byte[] binary = new byte[]{0, 1, (byte) 255, 13, 10};
        Files.write(worker.resolve("new.bin"), binary);
        Files.writeString(root.resolve("peer.txt"), "integrated peer\n");
        git(root, "add", "peer.txt");
        git(root, "commit", "-m", "integrate peer");

        var refreshed = service.ensure(location, "codex", "dirty-refresh").binding();
        Path target = Path.of(refreshed.worktreePath());
        assertEquals(first.sessionId(), refreshed.sessionId());
        assertEquals(first.workerId(), refreshed.workerId());
        assertNotEquals(worker, target);
        assertEquals(gitHead(root), refreshed.baseCommit());
        assertTrue(service.verifyWorkspace(location, refreshed, target).verified());
        assertEquals("unfinished\r\n", Files.readString(target.resolve("owned.txt")));
        assertTrue(java.util.Arrays.equals(binary, Files.readAllBytes(target.resolve("new.bin"))));
        assertTrue(Files.notExists(target.resolve("deleted.txt")));
        assertEquals("integrated peer\n", Files.readString(target.resolve("peer.txt")));
        assertEquals("unfinished\r\n", Files.readString(worker.resolve("owned.txt")));
        assertEquals("peer baseline\n", Files.readString(worker.resolve("peer.txt")));
        assertEquals(intent, new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId()).collaborationProjection().activeIntents().getFirst());
        var snapshots = new org.synesis.workspace.application.task.TaskSnapshotService();
        String publicationBase = snapshots.snapshotBaseForBoundWorktree(target, intent.baseCommit(), refreshed.baseCommit());
        assertEquals(refreshed.baseCommit(), publicationBase);
        assertTrue(snapshots.hasScopedPublishableChanges(target, intent.selectors(), intent.selectors(), List.of(), publicationBase));
        assertEquals(target.toString(), new ProviderSessionBindingService().ensure(location, "codex", "dirty-refresh").binding().worktreePath());

        Files.writeString(root.resolve("owned.txt"), "conflicting peer change\n");
        git(root, "add", "owned.txt");
        git(root, "commit", "-m", "conflicting integration");
        assertEquals("WORKSPACE_STALE_DIRTY", assertThrows(ProviderSessionBindingService.BindingException.class, () -> service.ensure(location, "codex", "dirty-refresh")).code());
        assertEquals("unfinished\r\n", Files.readString(target.resolve("owned.txt")));
    }

    @Test
    void refusesUnsafeClaimedDirtyRefreshAndPreservesOriginalWork() throws Exception {
        for (String failure : List.of("unclaimed", "staged", "suspended", "private-commit")) {
            Path root = Files.createTempDirectory("synesis-dirty-refresh-" + failure + "-");
            git(root, "init");
            Files.writeString(root.resolve("owned.txt"), "baseline\n");
            Files.writeString(root.resolve("peer.txt"), "peer baseline\n");
            git(root, "add", ".");
            git(root, "commit", "-m", "baseline");
            var location = new ProjectApplicationService().init(root).location();
            var service = new ProviderSessionBindingService();
            var first = service.ensure(location, "codex", failure).binding();
            Path worker = Path.of(first.worktreePath());
            String participant = WorkspaceCollaborationService.participantHandle(first.sessionId());
            var store = new PredictionEventStore(root.resolve(".synesis/coordination"), location.projectId());
            var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            var intents = new WorkIntentService(store, identity);
            assertTrue(intents.announce(new WorkIntent(UUID.randomUUID(),
                    location.projectId(),
                    participant,
                    "codex",
                    UUID.randomUUID(),
                    "Implement owned file",
                    "Behavior passes",
                    first.baseCommit(),
                    List.of(ResourceSelector.pathExact("owned.txt")),
                    1,
                    WorkIntent.Status.ANNOUNCED)).acquired());
            Files.writeString(worker.resolve("owned.txt"), "unfinished\n");
            switch (failure) {
                case "unclaimed" -> Files.writeString(worker.resolve("unclaimed.txt"), "preserve me\n");
                case "staged" -> git(worker, "add", "owned.txt");
                case "suspended" -> intents.suspend(participant);
                case "private-commit" -> {
                    git(worker, "add", "owned.txt");
                    git(worker, "commit", "-m", "private generation");
                    Files.writeString(worker.resolve("owned.txt"), "unfinished\nmore\n");
                }
                default -> throw new AssertionError(failure);
            }
            String preserved = Files.readString(worker.resolve("owned.txt"));
            Files.writeString(root.resolve("peer.txt"), "integrated peer\n");
            git(root, "add", "peer.txt");
            git(root, "commit", "-m", "integrate peer");
            assertEquals("WORKSPACE_STALE_DIRTY", assertThrows(ProviderSessionBindingService.BindingException.class, () -> service.ensure(location, "codex", failure)).code(), failure);
            assertEquals(preserved, Files.readString(worker.resolve("owned.txt")), failure);
            assertEquals(first.worktreePath(), service.list(location, "codex").getFirst().worktreePath(), failure);
        }
    }

    @Test
    void preservesSessionIdentityWhenOnlyTheControlCheckoutAdvanced() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-control-advance-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "control@example.invalid");
        git(root, "config", "user.name", "Control Test");
        git(root, "commit", "-m", "baseline");

        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "reviewer-recovery").binding();
        String originalSession = first.sessionId();
        String originalWorktree = first.worktreePath();
        Files.writeString(root.resolve("README.md"), "integrated control state\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrated snapshot");

        var recovered = service.ensure(location, "codex", "reviewer-recovery").binding();

        assertEquals(originalSession, recovered.sessionId());
        assertNotEquals(originalWorktree, recovered.worktreePath());
        assertEquals(gitHead(root), recovered.baseCommit());
        assertTrue(service.verifyWorkspace(location, recovered, Path.of(recovered.worktreePath())).verified());
        for (int retry = 0; retry < 3; retry++) {
            var repeated = service.ensure(location, "codex", "reviewer-recovery").binding();
            assertEquals(recovered.sessionId(), repeated.sessionId());
            assertEquals(recovered.supervisorId(), repeated.supervisorId());
            assertEquals(recovered.workerId(), repeated.workerId());
            assertEquals(recovered.worktreeId(), repeated.worktreeId());
            assertEquals(recovered.worktreePath(), repeated.worktreePath());
            assertEquals(recovered.branch(), repeated.branch());
            assertEquals(recovered.baseCommit(), repeated.baseCommit());
            assertTrue(service.verifyWorkspace(location, repeated, Path.of(repeated.worktreePath())).verified());
        }
    }

    @Test
    void retainsTheOriginalWorkspaceForAnActiveNoChangeIntentAfterControlAdvance() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-no-change-control-advance-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "no-change@example.invalid");
        git(root, "config", "user.name", "No Change Test");
        git(root, "commit", "-m", "baseline");

        var service = new ProviderSessionBindingService();
        var original = service.ensure(location, "codex", "no-change-reviewer").binding();
        String participant = WorkspaceCollaborationService.participantHandle(original.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntent intent = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                participant,
                "codex",
                UUID.randomUUID(),
                "Review an immutable candidate",
                "Review succeeds; no repository mutation is required",
                original.baseCommit(),
                List.of(ResourceSelector.pathExact("review-notes.md")),
                1,
                UUID.randomUUID(),
                WorkIntent.Status.ANNOUNCED);
        assertTrue(new WorkIntentService(store, identity).announce(intent).acquired());

        Files.writeString(root.resolve("README.md"), "integrated control state\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrate sibling snapshot");

        var retained = service.ensure(location, "codex", "no-change-reviewer").binding();

        assertEquals(original.sessionId(), retained.sessionId());
        assertEquals(original.worktreePath(), retained.worktreePath());
        assertEquals(original.baseCommit(), retained.baseCommit());
        assertTrue(service.verifyNoChangeWorkspace(location, retained, Path.of(retained.worktreePath())).verified());
        assertEquals("CONTROL_BASE_ADVANCED", service.verifyWorkspace(location, retained, Path.of(retained.worktreePath())).code());
    }

    @Test
    void reallocatesCleanActiveRecoveryContinuationAndPreservesItsDirtyWorktree() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-active-continuation-advance-");
        git(root, "init");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("README.md"), "baseline\n");
        Files.writeString(root.resolve("src/Product.java"), "public class Product {}\n");
        git(root, "add", ".");
        git(root, "config", "user.email", "continuation-control@example.invalid");
        git(root, "config", "user.name", "Continuation Control Test");
        git(root, "commit", "-m", "baseline");

        var location = new ProjectApplicationService().init(root).location();
        var service = new ProviderSessionBindingService();
        var binding = service.ensure(location, "codex", "active-continuation-recovery").binding();
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        var identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        var intents = new WorkIntentService(store, identity);
        WorkIntent source = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                "agt-interrupted",
                "codex",
                UUID.randomUUID(),
                "Implement source",
                "Publish the source snapshot",
                gitHead(root),
                List.of(ResourceSelector.pathExact("src/Product.java")),
                1,
                WorkIntent.Status.ANNOUNCED);
        intents.announce(source);
        intents.suspend(source.participant());
        String recoveryReference = root.resolve("recovery").toAbsolutePath() + "#verified";
        intents.holdRecovery(source.participant(), recoveryReference);

        WorkIntent successor = new WorkIntent(UUID.randomUUID(),
                location.projectId(),
                participant,
                "codex",
                source.taskId(),
                source.goal(),
                source.acceptance(),
                source.baseCommit(),
                source.selectors(),
                source.version() + 1,
                source.workGroupId(),
                source.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                source.role(),
                source.reviewTargetSelectors(),
                source.knownDependencies());
        intents.continueEligibleFromRecovery(new LaneContinuation(UUID.randomUUID(), source.intentId(), successor, source.participant(), participant, source.version(), recoveryReference));

        String initialWorktree = binding.worktreePath();
        Files.writeString(root.resolve("README.md"), "integrated snapshot\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrate snapshot");

        var recovered = service.ensure(location, "codex", "active-continuation-recovery").binding();

        assertEquals(binding.sessionId(), recovered.sessionId());
        assertNotEquals(initialWorktree, recovered.worktreePath());
        assertEquals(gitHead(root), recovered.baseCommit());
        assertTrue(service.verifyWorkspace(location, recovered, Path.of(recovered.worktreePath())).verified());

        Path recoveredWorktree = Path.of(recovered.worktreePath());
        Path reviewerNotes = recoveredWorktree.resolve("reviewer-notes.txt");
        Files.writeString(reviewerNotes, "preserve this continuation work\n");
        Files.writeString(root.resolve("README.md"), "second integrated snapshot\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "advance control again");

        var dirtyContinuation = service.ensure(location, "codex", "active-continuation-recovery").binding();

        assertEquals(recovered.worktreePath(), dirtyContinuation.worktreePath());
        assertEquals("preserve this continuation work\n", Files.readString(reviewerNotes));
        var staleCheck = service.verifyWorkspace(location, dirtyContinuation, recoveredWorktree);
        assertEquals("CONTROL_BASE_ADVANCED", staleCheck.code());
    }

    @Test
    void preservesLiveManagedWorktreeWhenOnlyTheControlCheckoutAdvanced() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-managed-control-advance-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "managed-control@example.invalid");
        git(root, "config", "user.name", "Managed Control Test");
        git(root, "commit", "-m", "baseline");

        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "managed-control-advance").binding();
        ManagedAttachmentService.storeFor(location, first.sessionId()).write(new ManagedAttachmentRecord(1,
                location.projectId().toString(),
                "codex",
                ProviderContinuityMode.MANAGED_CONTINUITY,
                first.sessionId(),
                "thread-managed",
                1,
                "a".repeat(64),
                "normal-provider-home",
                ManagedAttachmentRecord.Status.ACTIVE,
                1,
                System.currentTimeMillis()));
        var attachment = ManagedAttachmentService.storeFor(location, first.sessionId()).read().orElseThrow();
        assertEquals(first.projectId(), attachment.projectId());
        assertEquals(first.sessionId(), attachment.bindingSessionId());
        assertEquals(ProviderContinuityMode.MANAGED_CONTINUITY, attachment.mode());
        assertEquals(ManagedAttachmentRecord.Status.ACTIVE, attachment.status());
        Files.writeString(root.resolve("README.md"), "integrated control state\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrated snapshot");

        var retained = service.ensure(location, "codex", "managed-control-advance").binding();

        assertEquals(first.sessionId(), retained.sessionId(), () -> first + " retained=" + retained);
        assertEquals(first.worktreePath(), retained.worktreePath());
        assertEquals(first.baseCommit(), retained.baseCommit());
    }

    @Test
    void treatsPythonBytecodeCacheAsEphemeralDuringStaleSessionRecovery() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-python-cache-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "python-cache@example.invalid");
        git(root, "config", "user.name", "Python Cache Test");
        git(root, "commit", "-m", "baseline");

        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "python-cache-recovery").binding();
        Path firstWorktree = Path.of(first.worktreePath());
        Files.createDirectories(firstWorktree.resolve("__pycache__"));
        Files.write(firstWorktree.resolve("__pycache__/test_todo.cpython-313.pyc"), new byte[]{0x42, 0x43, 0x48});

        Files.writeString(root.resolve("README.md"), "integrated control state\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrated snapshot");

        var recovered = service.ensure(location, "codex", "python-cache-recovery").binding();

        assertEquals(first.sessionId(), recovered.sessionId());
        assertNotEquals(first.worktreePath(), recovered.worktreePath());
        assertEquals(gitHead(root), recovered.baseCommit());
        assertTrue(service.verifyWorkspace(location, recovered, Path.of(recovered.worktreePath())).verified());
    }

    @Test
    void staleRecoveryStillBlocksRealUntrackedUserContent() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-untracked-user-content-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "config", "user.email", "untracked-content@example.invalid");
        git(root, "config", "user.name", "Untracked Content Test");
        git(root, "commit", "-m", "baseline");

        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "untracked-content-recovery").binding();
        Path worker = Path.of(first.worktreePath());
        Files.writeString(worker.resolve("reviewer-notes.txt"), "legitimate user content\n");

        Files.writeString(root.resolve("README.md"), "integrated control state\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "integrated snapshot");

        assertThrows(ProviderSessionBindingService.BindingException.class, () -> service.ensure(location, "codex", "untracked-content-recovery"));
        assertTrue(Files.exists(worker.resolve("reviewer-notes.txt")));
    }

    @Test
    void preservesSessionIdentityWhenACleanWorkerAlreadyContainsTheAdvancedControlHead() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-advanced-clean-worker-");
        git(root, "init");
        git(root, "config", "user.email", "advanced-worker@example.invalid");
        git(root, "config", "user.name", "Advanced Worker Test");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "baseline");
        var location = new ProjectApplicationService().init(root).location();

        var service = new ProviderSessionBindingService();
        var first = service.ensure(location, "codex", "advanced-clean-worker").binding();
        String originalSession = first.sessionId();
        String originalWorktree = first.worktreePath();

        Files.writeString(root.resolve("README.md"), "control advanced\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "advance control");
        git(Path.of(originalWorktree), "merge", "--ff-only", "master");

        var recovered = service.ensure(location, "codex", "advanced-clean-worker").binding();

        assertEquals(originalSession, recovered.sessionId());
        assertNotEquals(originalWorktree, recovered.worktreePath());
        assertEquals(gitHead(root), recovered.baseCommit());
        assertTrue(service.verifyWorkspace(location, recovered, Path.of(recovered.worktreePath())).verified());
    }

    @Test
    void allocatesDistinctWorktreeOnlyForACommittedGitProject() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-worktree-");
        var location = new ProjectApplicationService().init(root).location();
        Files.writeString(root.resolve("README.md"), "proof\n");
        git(root, "init");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "initial");

        var service = new ProviderSessionBindingService();
        var binding = service.ensure(location, "codex", "chat-worktree").binding();
        Path bindingWorktree = Path.of(binding.worktreePath());
        var second = service.ensure(location, "codex", "chat-worktree-2").binding();
        assertNotEquals(root.toAbsolutePath().normalize().toString(), binding.worktreePath());
        assertTrue(Files.isDirectory(bindingWorktree));
        assertNotEquals(binding.worktreePath(), second.worktreePath());
        assertNotEquals(binding.branch(), second.branch());
        assertTrue(binding.baseCommit().matches("[0-9a-f]{40}"));
        var check = new ProviderSessionBindingService().verifyWorkspace(location, binding, bindingWorktree);
        assertTrue(check.verified(), check::code);
        assertEquals("CONTROL_CHECKOUT_MUTATION_DENIED", service.verifyWorkspace(location, binding, root).code());
        git(root, "worktree", "remove", "--force", binding.worktreePath());
        assertEquals("WORKSPACE_TRANSITION_REQUIRED", service.verifyWorkspace(location, binding, bindingWorktree).code());
    }

    @Test
    void malformedBindingFailsClosedWithoutReplacingProjectIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-malformed-");
        var location = new ProjectApplicationService().init(root).location();
        Path binding = root.resolve(".synesis/local/sessions/codex-bad.json");
        Files.createDirectories(binding.getParent());
        Files.writeString(binding, "{broken");

        assertThrows(ProviderSessionBindingService.BindingException.class, () -> new ProviderSessionBindingService().list(location, "codex"));
        assertTrue(Files.exists(location.profile().resolve("link/identity.bin")));
    }

    @Test
    void hookResolvesControlSessionFromAssignedWorktreeMarker() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-routing-");
        git(root, "init");
        var location = new ProjectApplicationService().init(root).location();
        String peer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        new ProjectConfig(location.projectId(), java.util.Set.of(peer)).save(location.profile().resolve("project.conf"));
        var binding = new ProviderSessionBindingService().ensure(location, "codex", "routing-session").binding();
        String event = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"routing-session\"," + "\"cwd\":\"" + binding.worktreePath().replace("\\", "\\\\")
                + "\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Add File: src/free.txt\\n*** End Patch\"}}";

        var result = new HookApplicationService().codex(new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("ALLOWED", result.outcome(), result.responseJson() + " " + result.humanReason());
        assertTrue(result.humanReason().contains("SESSION_ID=" + binding.sessionId()));
        assertTrue(Files.notExists(root.resolve("src/free.txt")));
    }

    @Test
    void codexHookBootstrapsProjectSessionBeforePolicyEvaluation() throws Exception {
        Path root = Files.createTempDirectory("synesis-session-codex-hook-");
        var location = new ProjectApplicationService().init(root).location();
        String peer = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId();
        ProjectConfig config = new ProjectConfig(location.projectId(), java.util.Set.of(peer));
        config.save(location.profile().resolve("project.conf"));
        String event = "{\"hook_event_name\":\"PreToolUse\",\"session_id\":\"codex-chat-a\"," + "\"cwd\":\"" + root.toString().replace("\\", "\\\\")
                + "\",\"tool_name\":\"apply_patch\",\"tool_input\":{\"command\":\"*** Begin Patch\\n*** Update File: src/free.txt\\n*** End Patch\"}}";

        HookApplicationService.HookExecutionResult result = new HookApplicationService().codex(new java.io.ByteArrayInputStream(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("INVALID_INPUT", result.outcome());
        assertTrue(result.responseJson().contains("GIT_HEAD_UNAVAILABLE"));
        try (var paths = Files.list(root.resolve(".synesis/local/sessions"))) {
            assertTrue(paths.findAny().isPresent());
        }
    }

}
