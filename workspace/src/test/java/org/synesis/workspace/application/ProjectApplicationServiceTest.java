package org.synesis.workspace.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.synesis.projectrecord.domain.ProjectConfig;

import org.synesis.workspace.application.project.ProjectCommandSpec;
import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectInitStatus;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies discovered project initialization and local-state separation.
 */
@SuppressWarnings("TextBlockMigration")
final class ProjectApplicationServiceTest {

    private static String git(Path root, String... arguments) throws Exception {
        return org.synesis.workspace.test.TestGit.output(root, arguments);
    }

    @Test
    void initializesAndDiscoversFromNestedDirectory() throws Exception {
        Path root = Files.createTempDirectory("synesis-init-");
        ProjectApplicationService service = new ProjectApplicationService();
        ProjectInitResult initialized = service.init(root);

        assertEquals(ProjectInitStatus.SUCCESS, initialized.status());
        assertTrue(Files.exists(root.resolve(".synesis/project.json")));
        assertTrue(Files.exists(root.resolve(".synesis/local/profile/link/identity.bin")));
        assertTrue(Files.isDirectory(root.resolve(".synesis/shared/records")));
        String agents = Files.readString(root.resolve("AGENTS.md"));
        assertTrue(agents.startsWith("<!-- SYNESIS-BEGIN -->"));
        assertTrue(agents.contains("This repository uses Synesis."));
        assertTrue(agents.contains("synesis provider install claude"));
        assertTrue(agents.contains("synesis provider install codex"));
        assertTrue(agents.contains("Before the first MCP session after `synesis init`"));
        assertTrue(agents.contains("in the control project directory"));
        assertTrue(agents.contains("do not rerun provider installation from the assigned isolated worktree"));
        assertTrue(agents.contains("use Synesis MCP for all reads, writes, and commands"));
        assertTrue(agents.contains("Native provider hooks are optional"));
        assertTrue(agents.contains("do not call finish_lane or another lifecycle tool merely because the coding appears complete"));
        assertTrue(agents.contains("Execute lifecycle actions only when get_next_action projects the exact tool and arguments"));
        assertTrue(agents.contains("A mutating Synesis tool may return a required continuation"));
        assertTrue(agents.contains("the continuation is part of the durable protocol, not an optional suggestion"));
        assertTrue(agents.contains("After any completed Synesis mutation, call get_next_action again before ending the turn"));
        assertTrue(agents.contains("do not end the session when IMPLEMENT has no concrete action"));
        assertTrue(agents.contains("Perform a bounded wait and call get_next_action again"));
        assertTrue(agents.contains("integrationCheck input only evaluates explicitly supplied compatibility facts"));
        assertTrue(agents.contains("When get_next_action projects WAIT"));
        assertTrue(agents.contains("Do not report success or stop merely because your own lane is complete"));
        assertTrue(agents.contains("A ready session is not yet an announced collaboration intent"));
        assertTrue(agents.contains("task.claims containing the exact repository-relative files or subtrees"));
        assertTrue(agents.contains("likelyScopes is descriptive only"));
        String metadata = Files.readString(root.resolve(".synesis/project.json"));
        assertTrue(metadata.contains("\"schemaVersion\": 2"));
        assertFalse(metadata.contains("identity.bin"));
        assertFalse(metadata.contains("private"));

        Files.createDirectories(root.resolve("nested/child"));
        ProjectLocation discovered = service.locate(root.resolve("nested/child"));
        assertEquals(root.toAbsolutePath().normalize(), discovered.root());
        assertEquals(initialized.location().projectId(), discovered.projectId());
        String firstAgents = Files.readString(root.resolve("AGENTS.md"));
        assertEquals(ProjectInitStatus.ALREADY_INITIALIZED, service.init(root).status());
        assertEquals(firstAgents, Files.readString(root.resolve("AGENTS.md")));
    }

    @Test
    void existingProjectOpenReensuresVisibilityWithoutRewritingIdentity() throws Exception {
        Path root = Files.createTempDirectory("synesis-visibility-reopen-");
        ProjectApplicationService service = new ProjectApplicationService();
        var initialized = service.init(root, false);
        byte[] metadataBefore = Files.readAllBytes(initialized.location().metadataFile());
        DosFileAttributeView dos = Files.getFileAttributeView(root.resolve(".synesis"), DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos == null) {
            return;
        }
        dos.setHidden(false);

        var reopened = service.require(root);

        assertEquals(initialized.location().identity(), reopened.identity());
        assertEquals(java.util.Arrays.toString(metadataBefore), java.util.Arrays.toString(Files.readAllBytes(reopened.metadataFile())));
        assertEquals(Boolean.TRUE, Files.getAttribute(root.resolve(".synesis"), "dos:hidden", LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void projectRootKeepsUserOutputVisibleAndContainsNoLooseSynesisBookkeeping() throws Exception {
        Path root = Files.createTempDirectory("synesis-root-hygiene-");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/CreatedByAgent.java"), "class CreatedByAgent {}\n");

        new ProjectApplicationService().init(root, false);

        Set<String> names;
        try (var children = Files.list(root)) {
            names = children.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
        assertTrue(names.contains(".synesis"));
        assertTrue(names.contains("AGENTS.md"));
        assertTrue(names.contains("src"));
        assertTrue(Files.isRegularFile(root.resolve("src/CreatedByAgent.java")));
        assertTrue(names.stream().noneMatch(name -> name.matches("synesis-.*") || name.endsWith(".lock") || name.endsWith(".tmp") || name.contains(".tmp-")));
    }

    @Test
    void copiedPortableMetadataReopensWithSameIdentityAndFreshVisibilityPolicy() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path source = Files.createTempDirectory("synesis-copy-source-");
        Path moved = Files.createTempDirectory("synesis-copy-target-");
        var original = service.init(source, false);
        Path copiedMetadata = Files.createDirectories(moved.resolve(".synesis")).resolve("project.json");
        Files.copy(original.location().metadataFile(), copiedMetadata);

        var reopened = service.require(moved);

        assertEquals(original.location().identity(), reopened.identity());
        assertEquals(Files.readString(original.location().metadataFile()), Files.readString(reopened.metadataFile()));
        DosFileAttributeView dos = Files.getFileAttributeView(moved.resolve(".synesis"), DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            assertEquals(Boolean.TRUE, Files.getAttribute(moved.resolve(".synesis"), "dos:hidden", LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void initializesGitAndSynesisForUninitializedRegistrationFolder() throws Exception {
        Path root = Files.createTempDirectory("synesis-registration-");

        ProjectInitResult initialized = new ProjectApplicationService().initForRegistration(root);

        assertEquals(ProjectInitStatus.SUCCESS, initialized.status());
        assertTrue(Files.isDirectory(root.resolve(".git")));
        assertTrue(Files.exists(root.resolve(".synesis/project.json")));
        assertEquals(40, git(root, "rev-parse", "--verify", "HEAD").length());
    }

    @Test
    void preservesUserTextAndReplacesOnlyManagedSection() throws Exception {
        Path root = Files.createTempDirectory("synesis-agents-");
        String existing = "# Project rules\n\nKeep this text.\n\n" + "<!-- SYNESIS-BEGIN -->\nold managed text\n<!-- SYNESIS-END -->\n\n# End\n";
        Files.writeString(root.resolve("AGENTS.md"), existing);

        new ProjectApplicationService().init(root);

        String updated = Files.readString(root.resolve("AGENTS.md"));
        assertTrue(updated.startsWith("# Project rules\n\nKeep this text."));
        assertTrue(updated.endsWith("\n\n# End\n"));
        assertTrue(updated.contains("This repository uses Synesis."));
        assertFalse(updated.contains("old managed text"));
    }

    @Test
    void rejectsMalformedManagedMarkersWithoutOverwritingUserFile() throws Exception {
        Path root = Files.createTempDirectory("synesis-agents-malformed-");
        Path agents = root.resolve("AGENTS.md");
        String existing = "User instructions\n<!-- SYNESIS-BEGIN -->\n";
        Files.writeString(agents, existing);

        ProjectApplicationService.ProjectApplicationException failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> new ProjectApplicationService().init(root));

        assertEquals("CONFLICT", failure.code());
        assertEquals(existing, Files.readString(agents));
    }

    @Test
    void partialAndMalformedStateFailsClosed() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path partial = Files.createTempDirectory("synesis-partial-");
        Files.createDirectories(partial.resolve(".synesis/local"));
        ProjectApplicationService.ProjectApplicationException partialFailure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.init(partial));
        assertEquals("CONFLICT", partialFailure.code());

        Path malformed = Files.createTempDirectory("synesis-malformed-");
        Files.createDirectories(malformed.resolve(".synesis"));
        Files.writeString(malformed.resolve(".synesis/project.json"), "{}\n");
        ProjectApplicationService.ProjectApplicationException malformedFailure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.locate(malformed));
        assertEquals("MALFORMED", malformedFailure.code());
    }

    @Test
    void requireGitRepositoryRejectsNonGitDirectoryWithoutCreatingState() throws Exception {
        Path root = Files.createTempDirectory("synesis-git-required-");
        ProjectApplicationService service = new ProjectApplicationService();

        ProjectApplicationService.ProjectApplicationException failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.requireGitRepository(root));

        assertEquals("GIT_REQUIRED", failure.code());
        assertFalse(Files.exists(root.resolve(".synesis")));
    }

    @Test
    void requireGitRepositoryAcceptsUnbornRepositoryForBaselineBootstrap() throws Exception {
        Path root = Files.createTempDirectory("synesis-unborn-preflight-");
        git(root, "init");

        assertDoesNotThrow(() -> new ProjectApplicationService().requireGitRepository(root));
        assertEquals("GIT_INITIAL_COMMIT_CREATED", new ProjectApplicationService().init(root).gitHeadStatus());
    }

    @Test
    void rejectsDirtyGitCheckoutWithoutCreatingProjectState() throws Exception {
        Path root = Files.createTempDirectory("synesis-dirty-init-");
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Baseline\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "initial commit");
        String head = git(root, "rev-parse", "--verify", "HEAD");
        Files.writeString(root.resolve("bootstrap.txt"), "uncommitted bootstrap work\n");

        ProjectApplicationService.ProjectApplicationException failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> new ProjectApplicationService().init(root));

        assertEquals("CONTROL_CHECKOUT_DIRTY", failure.code());
        assertFalse(Files.exists(root.resolve(".synesis")));
        assertEquals(head, git(root, "rev-parse", "--verify", "HEAD"));
        assertTrue(git(root, "status", "--porcelain").contains("?? bootstrap.txt"));
    }

    @Test
    void projectCreateUsesShareableProjectId() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path root = Files.createTempDirectory("synesis-project-");
        var init = service.init(root);
        String peer = "sl1-" + "a".repeat(64);
        var created = service.createProject(init.location(), peer);
        assertEquals(init.location().projectId(), created.projectId());
        assertTrue(Files.readString(root.resolve(".synesis/local/profile/project.conf")).contains(init.location().projectId().toString()));
    }

    @Test
    void projectCreateRejectsExistingConfigFromAnotherCanonicalProject() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path root = Files.createTempDirectory("synesis-project-config-mismatch-");
        var initialized = service.init(root, false);
        String peer = "sl1-" + "b".repeat(64);
        new ProjectConfig(UUID.randomUUID(), Set.of(peer)).save(initialized.location().profile().resolve("project.conf"));

        ProjectApplicationService.ProjectApplicationException failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.createProject(initialized.location(), peer));

        assertEquals("PROJECT_MISMATCH", failure.code());
    }

    @Test
    void replicaPreservesPortableIdentityAndCreatesFreshPrivateState() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path source = Files.createTempDirectory("synesis-replica-source-");
        Path replica = Files.createTempDirectory("synesis-replica-target-");
        var original = service.init(source, false);

        var materialized = service.initReplica(replica, original.location().identity(), false);

        assertEquals(original.location().identity(), materialized.location().identity());
        assertNotEquals(original.identity().nodeId(), materialized.identity().nodeId());
        assertTrue(Files.exists(replica.resolve(".synesis/project.json")));
        assertTrue(Files.exists(replica.resolve(".synesis/local/profile/link/identity.bin")));
        assertTrue(Files.exists(replica.resolve(".synesis/shared/records")));
        assertFalse(Files.exists(replica.resolve(".synesis/local/profile/project.conf")));
    }

    @Test
    void replicaRejectsMismatchedPortableIdentityAndPrivateState() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path source = Files.createTempDirectory("synesis-replica-mismatch-source-");
        Path replica = Files.createTempDirectory("synesis-replica-mismatch-target-");
        var original = service.init(source, false);
        Path replicaMetadata = Files.createDirectories(replica.resolve(".synesis")).resolve("project.json");
        Files.copy(original.location().metadataFile(), replicaMetadata);

        ProjectApplicationService.ProjectApplicationException mismatch = assertThrows(ProjectApplicationService.ProjectApplicationException.class,
                () -> service.initReplica(replica, new ProjectIdentity(UUID.randomUUID(), original.location().createdAt()), false));
        assertEquals("IDENTITY_MISMATCH", mismatch.code());

        Path privateReplica = Files.createTempDirectory("synesis-replica-private-target-");
        Path privateMetadata = Files.createDirectories(privateReplica.resolve(".synesis")).resolve("project.json");
        Files.copy(original.location().metadataFile(), privateMetadata);
        Files.createDirectories(privateReplica.resolve(".synesis/local"));

        ProjectApplicationService.ProjectApplicationException privateState = assertThrows(ProjectApplicationService.ProjectApplicationException.class,
                () -> service.initReplica(privateReplica, original.location().identity(), false));
        assertEquals("PRIVATE_STATE_PRESENT", privateState.code());
    }

    @Test
    void explicitForkCreatesNewIdentityAndFreshPrivateState() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path source = Files.createTempDirectory("synesis-fork-source-");
        Path fork = Files.createTempDirectory("synesis-fork-target-");
        var original = service.init(source, false);
        Path forkMetadata = Files.createDirectories(fork.resolve(".synesis")).resolve("project.json");
        Files.copy(original.location().metadataFile(), forkMetadata);

        var result = service.fork(fork, false);

        assertNotEquals(original.location().projectId(), result.location().projectId());
        assertNotEquals(original.location().identity(), result.location().identity());
        assertTrue(Files.exists(fork.resolve(".synesis/local/profile/link/identity.bin")));
        assertFalse(Files.exists(fork.resolve(".synesis/local/profile/project.conf")));
    }

    @Test
    void identicalGitHistoryCanHaveDistinctCanonicalProjectIdentities() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path root = Files.createTempDirectory("synesis-same-git-history-");
        Path origin = Files.createDirectories(root.resolve("origin"));
        TestGit.run(origin, "init");
        Files.writeString(origin.resolve("README.md"), "same content\n");
        TestGit.run(origin, "add", ".");
        TestGit.run(origin, "commit", "-m", "same content");
        Path clone = root.resolve("clone");
        TestGit.run(root, "clone", origin.toString(), clone.toString());

        var first = service.init(origin, false);
        var second = service.init(clone, false);

        assertNotEquals(first.location().projectId(), second.location().projectId());
        assertEquals(git(origin, "rev-parse", "HEAD^").trim(), git(clone, "rev-parse", "HEAD^").trim());
    }

    @Test
    void unbornGitRepositoryReceivesOnlyTheDocumentedSynesisInitialCommit() throws Exception {
        Path root = Files.createTempDirectory("synesis-unborn-git-");
        git(root, "init");

        ProjectApplicationService service = new ProjectApplicationService();
        var first = service.init(root);
        assertEquals("GIT_INITIAL_COMMIT_CREATED", first.gitHeadStatus());
        String head = git(root, "rev-parse", "--verify", "HEAD");
        assertTrue(head.matches("[0-9a-f]{40}"));
        assertTrue(git(root, "show", "--format=", "--name-only", "HEAD").contains(".synesis/project.json"));
        assertTrue(git(root, "show", "--format=", "--name-only", "HEAD").contains("AGENTS.md"));

        var second = service.init(root);
        assertEquals(ProjectInitStatus.ALREADY_INITIALIZED, second.status());
        assertEquals("GIT_HEAD_VALID", second.gitHeadStatus());
        assertEquals(head, git(root, "rev-parse", "--verify", "HEAD"));
    }

    @Test
    void acceptsVersionOneMetadataWithoutValidationAndReadsVersionTwoValidation() throws Exception {
        Path root = Files.createTempDirectory("synesis-validation-metadata-");
        ProjectApplicationService service = new ProjectApplicationService();
        var initialized = service.init(root, false);
        String v1 = "{\n  \"schemaVersion\": 1,\n  \"projectId\": \"" + initialized.location().projectId() + "\",\n  \"createdAt\": \"" + initialized.location().createdAt() + "\"\n}\n";
        Files.writeString(initialized.location().metadataFile(), v1);
        assertNull(service.locate(root).validation());

        String v2 = "{\n  \"schemaVersion\": 2,\n  \"projectId\": \"" + initialized.location().projectId() + "\",\n  \"createdAt\": \"" + initialized.location().createdAt() + "\",\n  \"validation\": {\n"
                + "    \"argv\": [\"powershell.exe\", \"-NoProfile\", \"-Command\", \"exit 0\"],\n" + "    \"workingDirectory\": \".\",\n    \"timeoutSeconds\": 120\n  }\n}\n";
        Files.writeString(initialized.location().metadataFile(), v2);
        var withValidation = service.locate(root);
        assertEquals(List.of("powershell.exe", "-NoProfile", "-Command", "exit 0"), withValidation.validation().argv());
        assertEquals(ProjectCommandSpec.DEFAULT_TIMEOUT_SECONDS, withValidation.validation().timeoutSeconds());
    }

    @Test
    void rejectsAbsoluteProjectValidationWorkingDirectory() throws Exception {
        Path root = Files.createTempDirectory("synesis-invalid-validation-");
        var initialized = new ProjectApplicationService().init(root, false);
        String invalid = "{\"schemaVersion\":2,\"projectId\":\"" + initialized.location().projectId() + "\",\"createdAt\":\"" + initialized.location().createdAt()
                + "\",\"validation\":{\"argv\":[\"git\",\"status\"],\"workingDirectory\":\"C:/outside\",\"timeoutSeconds\":120}}";
        Files.writeString(initialized.location().metadataFile(), invalid);
        var failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> new ProjectApplicationService().locate(root));
        assertEquals("MALFORMED", failure.code());
    }

    @Test
    void rejectsNonIntegerProjectValidationTimeout() throws Exception {
        Path root = Files.createTempDirectory("synesis-invalid-validation-timeout-");
        var initialized = new ProjectApplicationService().init(root, false);
        String invalid = "{\"schemaVersion\":2,\"projectId\":\"" + initialized.location().projectId() + "\",\"createdAt\":\"" + initialized.location().createdAt() + "\",\"validation\":{\"argv\":[\"git\",\"status\"],"
                + "\"workingDirectory\":\".\",\"timeoutSeconds\":1.5}}";
        Files.writeString(initialized.location().metadataFile(), invalid);
        var failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> new ProjectApplicationService().locate(root));
        assertEquals("MALFORMED", failure.code());
    }

    @Test
    void rejectsNullValidationFieldsInsteadOfTreatingThemAsOmitted() throws Exception {
        Path root = Files.createTempDirectory("synesis-invalid-validation-null-");
        var initialized = new ProjectApplicationService().init(root, false);
        String invalid = "{\"schemaVersion\":2,\"projectId\":\"" + initialized.location().projectId() + "\",\"createdAt\":\"" + initialized.location().createdAt() + "\",\"validation\":{\"argv\":[\"git\",\"status\"],"
                + "\"workingDirectory\":null,\"timeoutSeconds\":null}}";
        Files.writeString(initialized.location().metadataFile(), invalid);
        var failure = assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> new ProjectApplicationService().locate(root));
        assertEquals("MALFORMED", failure.code());
    }
}
