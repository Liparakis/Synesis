package org.synesis.workspace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Verifies discovered project initialization and local-state separation.
 */
final class ProjectApplicationServiceTest {

    private static String git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    @Test
    void initializesAndDiscoversFromNestedDirectory() throws Exception {
        Path root = Files.createTempDirectory("synesis-init-");
        ProjectApplicationService service = new ProjectApplicationService();
        ProjectApplicationService.InitResult initialized = service.init(root);

        assertEquals(ProjectApplicationService.InitStatus.SUCCESS, initialized.status());
        assertTrue(Files.exists(root.resolve(".synesis/project.json")));
        assertTrue(Files.exists(root.resolve(".synesis/local/profile/link/identity.bin")));
        assertTrue(Files.isDirectory(root.resolve(".synesis/shared/records")));
        String agents = Files.readString(root.resolve("AGENTS.md"));
        assertTrue(agents.startsWith("<!-- SYNESIS-BEGIN -->"));
        assertTrue(agents.contains("This repository uses Synesis."));
        assertTrue(agents.contains("use Synesis MCP for all reads, writes, and commands"));
        assertTrue(agents.contains("Native provider hooks are optional"));
        String metadata = Files.readString(root.resolve(".synesis/project.json"));
        assertFalse(metadata.contains("identity.bin"));
        assertFalse(metadata.contains("private"));

        Files.createDirectories(root.resolve("nested/child"));
        ProjectApplicationService.ProjectLocation discovered = service.locate(root.resolve("nested/child"));
        assertEquals(root.toAbsolutePath()
                .normalize(), discovered.root());
        assertEquals(initialized.location()
                .projectId(), discovered.projectId());
        String firstAgents = Files.readString(root.resolve("AGENTS.md"));
        assertEquals(ProjectApplicationService.InitStatus.ALREADY_INITIALIZED,
                service.init(root)
                        .status());
        assertEquals(firstAgents, Files.readString(root.resolve("AGENTS.md")));
    }

    @Test
    void preservesUserTextAndReplacesOnlyManagedSection() throws Exception {
        Path root = Files.createTempDirectory("synesis-agents-");
        String existing = "# Project rules\n\nKeep this text.\n\n"
                + "<!-- SYNESIS-BEGIN -->\nold managed text\n<!-- SYNESIS-END -->\n\n# End\n";
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

        ProjectApplicationService.ProjectApplicationException failure = assertThrows(
                ProjectApplicationService.ProjectApplicationException.class,
                () -> new ProjectApplicationService().init(root));

        assertEquals("CONFLICT", failure.code());
        assertEquals(existing, Files.readString(agents));
    }

    @Test
    void partialAndMalformedStateFailsClosed() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path partial = Files.createTempDirectory("synesis-partial-");
        Files.createDirectories(partial.resolve(".synesis/local"));
        ProjectApplicationService.ProjectApplicationException partialFailure =
                assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.init(partial));
        assertEquals("CONFLICT", partialFailure.code());

        Path malformed = Files.createTempDirectory("synesis-malformed-");
        Files.createDirectories(malformed.resolve(".synesis"));
        Files.writeString(malformed.resolve(".synesis/project.json"), "{}\n");
        ProjectApplicationService.ProjectApplicationException malformedFailure =
                assertThrows(ProjectApplicationService.ProjectApplicationException.class,
                        () -> service.locate(malformed));
        assertEquals("MALFORMED", malformedFailure.code());
    }

    @Test
    void projectCreateUsesShareableProjectId() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path root = Files.createTempDirectory("synesis-project-");
        var init = service.init(root);
        String peer = "sl1-" + "a".repeat(64);
        var created = service.createProject(init.location(), peer);
        assertEquals(init.location()
                .projectId(), created.projectId());
        assertTrue(Files.readString(root.resolve(".synesis/local/profile/project.conf"))
                .contains(init.location()
                        .projectId()
                        .toString()));
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
        assertEquals(ProjectApplicationService.InitStatus.ALREADY_INITIALIZED, second.status());
        assertEquals("GIT_HEAD_VALID", second.gitHeadStatus());
        assertEquals(head, git(root, "rev-parse", "--verify", "HEAD"));
    }
}
