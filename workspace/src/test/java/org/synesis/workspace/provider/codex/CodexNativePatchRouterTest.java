package org.synesis.workspace.provider.codex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;
import org.synesis.workspace.test.TestGit;

/**
 * Verifies native Codex patch routing uses broker-owned path and create preconditions.
 */
final class CodexNativePatchRouterTest {

    @TempDir
    Path projectRoot;

    private ProjectLocation location;
    private ProviderSessionBindingService.Binding binding;
    private Path worktree;

    @BeforeEach
    void setUp() throws Exception {
        TestGit.run(projectRoot, "init");
        TestGit.run(projectRoot, "config", "user.name", "Test User");
        TestGit.run(projectRoot, "config", "user.email", "test@example.com");
        Files.writeString(projectRoot.resolve("README.md"), "# test\n");
        TestGit.run(projectRoot, "add", ".");
        TestGit.run(projectRoot, "commit", "-m", "initial");

        location = new ProjectApplicationService().init(projectRoot).location();
        ProviderTestSupport.install(location, "codex");
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        binding = bindings.list(location, "codex").getLast();
        worktree = Path.of(binding.worktreePath());
        assertTrue(bindings.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree).verified());
    }

    @Test
    void addPatchIsCreatedOnceAndDuplicateCreateIsRejected() {
        String patch = "*** Begin Patch\n*** Add File: src/routed.txt\n+hello\n*** End Patch";
        CodexNativePatchRouter router = new CodexNativePatchRouter();

        CodexNativePatchRouter.RouteResult first = router.route(location, binding, patch);
        CodexNativePatchRouter.RouteResult duplicate = router.route(location, binding, patch);

        assertTrue(first.handled(), first.message());
        assertFalse(duplicate.handled());
        assertTrue(Files.exists(worktree.resolve("src/routed.txt")));
    }

    @Test
    void symlinkEscapeIsRejectedBeforeReadingOrWritingTheTarget() throws Exception {
        Path outside = Files.createDirectories(projectRoot.resolve("outside"));
        Path symlink = worktree.resolve("link-out");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return;
        }
        String patch = "*** Begin Patch\n*** Add File: link-out/private.txt\n+secret\n*** End Patch";

        CodexNativePatchRouter.RouteResult result = new CodexNativePatchRouter().route(location, binding, patch);

        assertFalse(result.handled());
        assertFalse(Files.exists(outside.resolve("private.txt")));
    }

    @Test
    void protectedSynesisFilesAreRejectedBeforeReadingThem() {
        String patch = "*** Begin Patch\n*** Add File: .synesis/local/private.json\n+{}\n*** End Patch";

        CodexNativePatchRouter.RouteResult result = new CodexNativePatchRouter().route(location, binding, patch);

        assertFalse(result.handled());
        assertFalse(Files.exists(worktree.resolve(".synesis/local/private.json")));
    }
}
