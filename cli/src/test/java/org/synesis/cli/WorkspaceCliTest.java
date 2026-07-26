package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.workspace.project.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;

class WorkspaceCliTest {

    private Path tempDir;
    private ProjectApplicationService.ProjectLocation location;
    private ProviderSessionBindingService bindingService;

    private static Invocation createInvocation(Path profile) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(stream(out), stream(err));
        StatusRenderer renderer = new StatusRenderer(terminal);
        CliRuntime runtime = new CliRuntime(new Onboarding(profile, renderer),
                terminal,
                new ReadinessInspector(profile));
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
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .start();
        p.getInputStream()
                .readAllBytes();
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
        location = projectService.init(tempDir)
                .location();
        UUID projectId = location.projectId();
        new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64)))
                .save(location.profile()
                        .resolve("project.conf"));

        bindingService = new ProviderSessionBindingService();
        bindingService.ensure(location, "codex", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    @Test
    void test12InstalledCliContainsBrokerOperation() throws Exception {
        Invocation invocation = createInvocation(location.profile());

        int exit = SynesisCli.execute(new String[]{"workspace", "mutate", "--help"}, invocation.runtime());

        assertEquals(0, exit);
        assertTrue(invocation.output()
                .contains("mutate"));
        assertTrue(invocation.output()
                .contains("--target"));
    }

    @Test
    void test14RepeatedBrokerRequestWithSameIdempotencyKeyDoesNotDuplicateMutation() throws Exception {
        var binding = bindingService.list(location, "codex")
                .getLast();
        Path worktreePath = Path.of(binding.worktreePath());
        bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktreePath);

        String key = "idempotent-key-" + UUID.randomUUID();

        Invocation inv1 = createInvocation(location.profile());
        int exit1 = SynesisCli.execute(new String[]{
                "workspace", "mutate",
                "--project", location.root().toString(),
                "--provider", "codex",
                "--target", "src/idempotent.txt",
                "--content", "first write",
                "--idempotency-key", key
        }, inv1.runtime());

        assertEquals(0, exit1);
        String output1 = inv1.output();
        assertTrue(output1.contains("\"RESULT\":\"SUCCESS\""));

        Invocation inv2 = createInvocation(location.profile());
        int exit2 = SynesisCli.execute(new String[]{
                "workspace", "mutate",
                "--project", location.root().toString(),
                "--provider", "codex",
                "--target", "src/idempotent.txt",
                "--content", "second write",
                "--idempotency-key", key
        }, inv2.runtime());

        assertEquals(0, exit2);
        String output2 = inv2.output();
        assertEquals(output1, output2);
    }

    private record Invocation(CliRuntime runtime, ByteArrayOutputStream out, ByteArrayOutputStream err) {

        private String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
