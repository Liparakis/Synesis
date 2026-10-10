package org.synesis.cli.workspace;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.link.onboarding.Onboarding;

import org.synesis.projectrecord.domain.ProjectConfig;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/**
 * Exercises workspace CLI parsing, readiness, and project-state behavior.
 */
class WorkspaceCliTest {

    private Path tempDir;
    private ProjectLocation location;
    private ProviderSessionBindingService bindingService;

    private static Invocation createInvocation(Path profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        StatusRenderer renderer = new StatusRenderer(terminal);
        CliRuntime runtime = new CliRuntime(new Onboarding(profile, renderer), terminal);
        return new Invocation(runtime, out, err);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private static void runGit(Path root, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = root.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("synesis-cli-broker-test-");
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.name", "Test User");
        runGit(tempDir, "config", "user.email", "test@example.com");
        Files.writeString(tempDir.resolve("README.md"), "# Test\n");
        runGit(tempDir, "add", "README.md");
        runGit(tempDir, "commit", "-m", "initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        location = projectService.init(tempDir).location();
        UUID projectId = location.projectId();
        new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64))).save(location.profile().resolve("project.conf"));

        bindingService = new ProviderSessionBindingService();
        bindingService.ensure(location, "codex", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void test12InstalledCliContainsBrokerOperation() {
        Invocation invocation = createInvocation(location.profile());

        int exit = SynesisCli.execute(new String[]{"workspace", "mutate", "--help"}, invocation.runtime());

        assertEquals(0, exit);
        assertTrue(invocation.output().contains("mutate"));
        assertTrue(invocation.output().contains("--target"));
    }

    @Test
    void test14RepeatedBrokerRequestWithSameIdempotencyKeyDoesNotDuplicateMutation() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktreePath = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktreePath);

        String key = "idempotent-key-" + UUID.randomUUID();

        Invocation inv1 = createInvocation(location.profile());
        int exit1 = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--target", "src/idempotent.txt", "--content", "first write", "--session",
                binding.sessionId(), "--idempotency-key", key}, inv1.runtime());

        assertEquals(0, exit1);
        String output1 = inv1.output();
        assertTrue(output1.contains("\"RESULT\":\"SUCCESS\""));

        Invocation inv2 = createInvocation(location.profile());
        int exit2 = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--target", "src/idempotent.txt", "--content", "first write", "--session",
                binding.sessionId(), "--idempotency-key", key}, inv2.runtime());

        assertEquals(0, exit2);
        String output2 = inv2.output();
        assertEquals(output1, output2);
    }

    @Test
    void requiresAnExactSessionInsteadOfSelectingTheNewestBinding() {
        Invocation invocation = createInvocation(location.profile());
        int exit = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--target", "src/session-required.txt", "--content", "content"}, invocation.runtime());

        assertEquals(2, exit);
        assertFalse(Files.exists(location.root().resolve("src/session-required.txt")));
    }

    @Test
    void rejectsReusingOneIdempotencyKeyForDifferentMutationContent() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktree = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);
        String key = "reused-key-" + UUID.randomUUID();

        Invocation first = createInvocation(location.profile());
        int firstExit = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target", "src/idempotency-conflict.txt",
                "--content", "first", "--idempotency-key", key}, first.runtime());
        assertEquals(0, firstExit);

        Invocation second = createInvocation(location.profile());
        int secondExit = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target",
                "src/idempotency-conflict.txt", "--content", "different", "--idempotency-key", key}, second.runtime());

        assertEquals(2, secondExit);
        assertTrue(second.output().contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals("first", Files.readString(worktree.resolve("src/idempotency-conflict.txt")));
    }

    @Test
    void concurrentRequestsWithOneKeyApplyAtMostOneDistinctMutation() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktree = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);
        String key = "concurrent-key-" + UUID.randomUUID();
        Invocation first = createInvocation(location.profile());
        Invocation second = createInvocation(location.profile());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var firstRun = workers.submit(() -> {
                start.await();
                return SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target",
                        "src/concurrent-idempotency.txt", "--content", "first", "--idempotency-key", key}, first.runtime());
            });
            var secondRun = workers.submit(() -> {
                start.await();
                return SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target",
                        "src/concurrent-idempotency.txt", "--content", "second", "--idempotency-key", key}, second.runtime());
            });
            start.countDown();
            int firstExit = firstRun.get(30, TimeUnit.SECONDS);
            int secondExit = secondRun.get(30, TimeUnit.SECONDS);
            assertEquals(1, (firstExit == 0 ? 1 : 0) + (secondExit == 0 ? 1 : 0));
            assertTrue(first.output().contains("IDEMPOTENCY_KEY_REUSED") || second.output().contains("IDEMPOTENCY_KEY_REUSED"));
            String persisted = Files.readString(worktree.resolve("src/concurrent-idempotency.txt"));
            assertTrue("first".equals(persisted) || "second".equals(persisted));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void traversalPreconditionsAreEvaluatedOnlyAfterBrokerPathValidation() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktree = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktree);
        Path outsideFile = location.root().resolve("outside-worktree.json");
        Files.writeString(outsideFile, "private marker");
        String target = worktree.relativize(outsideFile).toString();
        Invocation invocation = createInvocation(location.profile());

        int exit = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target", target, "--create-only"},
                invocation.runtime());

        assertEquals(2, exit);
        assertTrue(invocation.output().contains("PATH_TRAVERSAL_REJECTED"));
        assertFalse(invocation.output().contains("FILE_ALREADY_EXISTS"));
        assertEquals("private marker", Files.readString(outsideFile));
    }

    @Test
    void idempotencyKeyCannotTraverseIntoAnotherJsonFile() throws Exception {
        Path evidenceDirectory = location.synesisDirectory().resolve("local/evidence/codex").toAbsolutePath().normalize();
        Path sentinel = location.root().resolve("cache-sentinel.json").toAbsolutePath().normalize();
        String sentinelContents = "{\"PRIVATE_CACHE_MARKER\":true}";
        Files.writeString(sentinel, sentinelContents);
        Path relative = evidenceDirectory.relativize(sentinel);
        long parents = java.util.stream.StreamSupport.stream(relative.spliterator(), false).filter(part -> "..".equals(part.toString())).count();
        String separator = evidenceDirectory.getFileSystem().getSeparator();
        String traversalKey = String.join(separator, java.util.Collections.nCopies(Math.toIntExact(parents + 2), "..")) + separator + "cache-sentinel";
        Path oldCacheResolution = evidenceDirectory.resolve("idempotency-" + traversalKey + ".json").normalize();
        assertEquals(sentinel, oldCacheResolution);

        var binding = bindingService.list(location, "codex").getLast();
        Invocation invocation = createInvocation(location.profile());
        int exit = SynesisCli.execute(new String[]{"workspace", "mutate", "--project", location.root().toString(), "--provider", "codex", "--session", binding.sessionId(), "--target", "src/cache-probe.txt", "--content",
                "probe", "--idempotency-key", traversalKey}, invocation.runtime());

        assertFalse(invocation.output().contains(sentinelContents));
        assertEquals(sentinelContents, Files.readString(sentinel));
        assertTrue(exit != 0);
    }

    /**
     * Holds isolated streams and runtime for one workspace CLI invocation.
     */
    private record Invocation(CliRuntime runtime, ByteArrayOutputStream out, ByteArrayOutputStream err) {

        private String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
