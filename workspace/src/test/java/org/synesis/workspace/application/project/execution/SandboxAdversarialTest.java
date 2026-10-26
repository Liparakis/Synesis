package org.synesis.workspace.application.project.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.project.execution.sandbox.LinuxBubblewrapSandbox;
import org.synesis.workspace.application.project.execution.sandbox.Sandbox;
import org.synesis.workspace.application.project.execution.sandbox.SandboxAvailability;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;

/**
 * The adversarial test of ADR-0104 stage 2: one agent command per attack, run through the real
 * {@link ProjectProcessExecutor} with the real Linux sandbox. Every attack must fail, and the state afterwards is
 * checked from outside the sandbox. The ordinary work an agent needs (Git in its lane, a build, the network) must
 * still succeed.
 *
 * <p>On a host with no working sandbox the tests are skipped with the explicit reason {@code SKIPPED, no sandbox}.
 * A host that must have one sets {@code SYNESIS_REQUIRE_SANDBOX=true}, and then the skip is a failure.</p>
 */
class SandboxAdversarialTest {

    private static final String KEY_BYTES = "KEYBYTES-0f3a9c-do-not-print";

    private static Path base;
    private static Path control;
    private static Path lane1;
    private static Path lane2;
    private static Path integration;
    private static Path stateDirectory;
    private static Path daemonEndpoint;
    private static Path controlGit;
    private static Path adminDirectory;
    private static ProjectProcessExecutor executor;
    private static String mainHead;

    @BeforeAll
    static void setUp() throws Exception {
        LinuxBubblewrapSandbox sandbox = new LinuxBubblewrapSandbox();
        SandboxAvailability availability = sandbox.probe();
        if (!availability.available() && Boolean.parseBoolean(System.getenv("SYNESIS_REQUIRE_SANDBOX"))) {
            throw new AssertionError("this host must have a sandbox: " + availability.reason());
        }
        assumeTrue(availability.available(), "SKIPPED, no sandbox: " + availability.reason());
        executor = new ProjectProcessExecutor(sandbox, UnsandboxedAllowance.DENY);

        base = Files.createTempDirectory("synesis-adv-").toRealPath();
        control = base.resolve("control");
        controlGit = control.resolve(".git");
        git(base, "init", "-q", "-b", "main", control.toString());
        Files.createDirectories(control.resolve(".synesis/local/profile/link"));
        Files.createDirectories(control.resolve(".synesis/coordination"));
        Files.writeString(control.resolve(".synesis/project.json"), "{\"validation\":{\"argv\":[\"true\"]}}");
        Files.writeString(control.resolve(".synesis/local/profile/link/identity.bin"), KEY_BYTES);
        Files.writeString(control.resolve(".synesis/coordination/replication.properties"), "code.remote=none\n");
        Files.writeString(control.resolve("README.md"), "control\n");
        git(control, "add", "README.md");
        git(control, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "first");
        git(control, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "second");
        mainHead = git(control, "rev-parse", "refs/heads/main");

        // Lanes live in the application state, where Synesis puts them.
        stateDirectory = Path.of(System.getProperty("user.home"), ".synesis", "Synesis", "workspaces", "adv-" + UUID.randomUUID(), "worktrees");
        Files.createDirectories(stateDirectory);
        stateDirectory = stateDirectory.toRealPath();
        lane1 = stateDirectory.resolve("lane1");
        lane2 = stateDirectory.resolve("lane2");
        integration = stateDirectory.resolveSibling("integration").resolve("attempt");
        Files.createDirectories(integration.getParent());
        git(control, "worktree", "add", "-q", "-b", "synesis/test/lane1", lane1.toString());
        git(control, "worktree", "add", "-q", "-b", "synesis/test/lane2", lane2.toString());
        git(control, "worktree", "add", "-q", "--detach", integration.toString());
        adminDirectory = controlGit.resolve("worktrees").resolve("lane1");
        daemonEndpoint = stateDirectory.getParent().getParent().getParent().resolve("daemon.endpoint");
        Files.writeString(daemonEndpoint, "port=1 token=" + KEY_BYTES);

        // Provider configuration the provider writes into the lane.
        Files.createDirectories(lane1.resolve(".claude"));
        Files.writeString(lane1.resolve(".claude/settings.json"), "{}");
        Files.writeString(lane1.resolve(".mcp.json"), "{}");
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (base == null) {
            return;
        }
        deleteTree(base);
        deleteTree(stateDirectory.getParent());
        deleteTree(integration.getParent());
        Files.deleteIfExists(daemonEndpoint);
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), command + " -> " + output);
        return output;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Runs one shell script as an agent's command in lane 1 and returns its result.
     */
    private static ExecutionResult run(String script) {
        return run(script, 60);
    }

    private static ExecutionResult run(String script, int timeoutSeconds) {
        return executor.execute(new ExecutionRequest(List.of("/bin/sh", "-c", script), lane1, ".", timeoutSeconds, control));
    }

    private static String shown(ExecutionResult result) {
        return result.outcome() + " stdout=" + result.stdout() + " stderr=" + result.stderr();
    }

    /**
     * An attack script: the attack runs in a subshell; success of the attack prints BREACH.
     */
    private static void assertAttackFails(String attack) {
        ExecutionResult result = run("( " + attack + " ) >/dev/null 2>&1 && echo BREACH; echo DONE");
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), shown(result));
        assertTrue(result.stdout().contains("DONE"), shown(result));
        assertFalse(result.stdout().contains("BREACH"), "attack succeeded: " + attack + " " + shown(result));
        assertFalse((result.stdout() + result.stderr()).contains(KEY_BYTES), shown(result));
    }

    @Test
    void policyFileInTheControlCheckoutIsNotWritable() throws Exception {
        String before = Files.readString(control.resolve(".synesis/project.json"));
        assertAttackFails("echo '{}' > '" + control.resolve(".synesis/project.json") + "'");
        assertAttackFails("rm '" + control.resolve(".synesis/project.json") + "'");
        assertAttackFails("echo x > '" + control.resolve("planted.txt") + "'");
        assertEquals(before, Files.readString(control.resolve(".synesis/project.json")));
        assertFalse(Files.exists(control.resolve("planted.txt")));
    }

    @Test
    void mainCannotBeMovedByGit() throws Exception {
        String other = git(control, "rev-parse", "refs/heads/main~1");
        assertAttackFails("git -C '" + control + "' update-ref refs/heads/main " + other);
        assertAttackFails("git update-ref refs/heads/main " + other);
        assertAttackFails("git update-ref refs/synesis/planted " + other);
        assertAttackFails("git commit --allow-empty -q -m planted");
        assertAttackFails("git branch planted");
        assertEquals(mainHead, git(control, "rev-parse", "refs/heads/main"));
        assertEquals("", git(control, "for-each-ref", "refs/synesis/", "refs/heads/planted"));
    }

    @Test
    void sharedGitConfigHooksAndWorktreeConfigAreNotWritable() throws Exception {
        String configBefore = Files.readString(controlGit.resolve("config"));
        assertAttackFails("git config core.fsmonitor /tmp/pwn");
        assertAttackFails("git config --local core.hooksPath /tmp");
        assertAttackFails("echo '[core]' >> '" + controlGit.resolve("config") + "'");
        assertAttackFails("printf '#!/bin/sh\\ntouch /tmp/pwned\\n' > '" + controlGit.resolve("hooks/pre-commit") + "'");
        assertAttackFails("printf '[core]\\n\\tfsmonitor = /tmp/pwn\\n' > '" + adminDirectory.resolve("config.worktree") + "'");
        assertAttackFails("git config --worktree core.fsmonitor /tmp/pwn");
        assertEquals(configBefore, Files.readString(controlGit.resolve("config")));
        assertFalse(Files.exists(controlGit.resolve("hooks/pre-commit")));
        assertEquals("", Files.readString(adminDirectory.resolve("config.worktree")));
    }

    @Test
    void gitDirectoryPointersOfTheLaneCannotBeRewritten() throws Exception {
        String pointer = Files.readString(lane1.resolve(".git"));
        String commondir = Files.readString(adminDirectory.resolve("commondir"));
        assertAttackFails("echo 'gitdir: /tmp/hostile' > '" + lane1.resolve(".git") + "'");
        assertAttackFails("rm -f '" + lane1.resolve(".git") + "'");
        assertAttackFails("echo /tmp/hostile > '" + adminDirectory.resolve("commondir") + "'");
        assertAttackFails("echo /tmp/hostile/.git > '" + adminDirectory.resolve("gitdir") + "'");
        assertEquals(pointer, Files.readString(lane1.resolve(".git")));
        assertEquals(commondir, Files.readString(adminDirectory.resolve("commondir")));
    }

    @Test
    void keysAndApplicationStateAreNotReadable() {
        ExecutionResult result = run("cat '" + control.resolve(".synesis/local/profile/link/identity.bin") + "'; "
                + "cat '" + daemonEndpoint + "'; ls '" + control.resolve(".synesis") + "'; "
                + "find / -xdev \\( -name identity.bin -o -name daemon.endpoint \\) 2>/dev/null; "
                + "grep -r " + KEY_BYTES + " /tmp \"$HOME\" 2>/dev/null; echo DONE");
        assertTrue(result.stdout().contains("DONE"), shown(result));
        assertFalse((result.stdout() + result.stderr()).contains(KEY_BYTES), shown(result));
        assertFalse(result.stdout().contains("identity.bin"), shown(result));
        assertFalse(result.stdout().contains("daemon.endpoint"), shown(result));
        assertFalse(result.stdout().contains("project.json"), shown(result));
    }

    @Test
    void otherLanesAndTheIntegrationWorktreeAreNotWritableOrReadable() throws Exception {
        assertAttackFails("echo x > '" + lane2.resolve("planted.txt") + "'");
        assertAttackFails("echo x > '" + lane2.resolve("README.md") + "'");
        assertAttackFails("echo x > '" + integration.resolve("planted.txt") + "'");
        assertAttackFails("ls '" + lane2 + "' | grep -q README");
        assertAttackFails("cat '" + integration.resolve("README.md") + "'");
        assertFalse(Files.exists(lane2.resolve("planted.txt")));
        assertFalse(Files.exists(integration.resolve("planted.txt")));
        assertEquals("control\n", Files.readString(lane2.resolve("README.md")));
    }

    @Test
    void localReplicationAndDaemonFilesAreNotWritable() throws Exception {
        String before = Files.readString(control.resolve(".synesis/coordination/replication.properties"));
        assertAttackFails("echo 'code.remote=evil' > '" + control.resolve(".synesis/coordination/replication.properties") + "'");
        assertAttackFails("echo 'port=2' > '" + daemonEndpoint + "'");
        assertAttackFails("mkdir -p '" + daemonEndpoint.getParent() + "/link' && echo x > '" + daemonEndpoint.getParent() + "/link/identity.bin'");
        assertEquals(before, Files.readString(control.resolve(".synesis/coordination/replication.properties")));
        assertEquals("port=1 token=" + KEY_BYTES, Files.readString(daemonEndpoint));
        assertFalse(Files.exists(daemonEndpoint.getParent().resolve("link")));
    }

    @Test
    void looseObjectsOfMainCannotBeOverwritten() throws Exception {
        String blob = git(control, "rev-parse", "HEAD:README.md");
        Path object = controlGit.resolve("objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2));
        assertTrue(Files.isRegularFile(object), "the committed blob is a loose object");
        byte[] before = Files.readAllBytes(object);
        assertAttackFails("chmod u+w '" + object + "'; echo evil > '" + object + "'");
        assertAttackFails("rm -f '" + object + "'");
        assertAttackFails("echo evil > '" + controlGit.resolve("objects/ev") + "'");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(object)));
        git(control, "fsck", "--no-dangling");
    }

    @Test
    void providerConfigurationOfTheLaneIsNotWritable() throws Exception {
        assertAttackFails("echo '{\"hooks\":{}}' > '" + lane1.resolve(".claude/settings.json") + "'");
        assertAttackFails("echo '{\"mcpServers\":{}}' > '" + lane1.resolve(".mcp.json") + "'");
        assertAttackFails("rm -rf '" + lane1.resolve(".claude") + "'");
        assertAttackFails("mv '" + lane1.resolve(".claude") + "' '" + lane1.resolve("moved") + "'");
        assertEquals("{}", Files.readString(lane1.resolve(".claude/settings.json")));
        assertEquals("{}", Files.readString(lane1.resolve(".mcp.json")));
    }

    @Test
    void symlinkInTheWorktreeToTheControlCheckoutIsDangling() throws Exception {
        ExecutionResult result = run("ln -s '" + control + "/.synesis' viewer; cat viewer/project.json; "
                + "ln -sf '" + control + "/.synesis/local/profile/link/identity.bin' key; cat key; echo DONE");
        assertTrue(result.stdout().contains("DONE"), shown(result));
        assertFalse((result.stdout() + result.stderr()).contains(KEY_BYTES), shown(result));
        assertFalse(result.stdout().contains("validation"), shown(result));
        Files.deleteIfExists(lane1.resolve("viewer"));
        Files.deleteIfExists(lane1.resolve("key"));
    }

    @Test
    void ordinaryWorkInTheLaneSucceeds() throws Exception {
        ExecutionResult write = run("echo hello > work.txt && cat work.txt");
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, write.outcome(), shown(write));
        assertEquals("hello", write.stdout().strip());

        ExecutionResult status = run("git status --porcelain && git diff --stat && git log --oneline | head -3 && git add work.txt && git status --porcelain");
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, status.outcome(), shown(status));
        assertTrue(status.stdout().contains("?? work.txt"), shown(status));
        assertTrue(status.stdout().contains("A  work.txt"), shown(status));
        assertTrue(status.stdout().contains("second"), shown(status));
        // The add went to the lane's own index and a private object folder, not into the control repository.
        assertTrue(git(lane1, "status", "--porcelain").contains("A  work.txt"));
        assertEquals(mainHead, git(control, "rev-parse", "refs/heads/main"));
        String blob = git(lane1, "rev-parse", ":work.txt");
        assertFalse(Files.exists(controlGit.resolve("objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2))),
                "a new object stays out of the control repository");
        git(lane1, "reset", "-q");
        Files.deleteIfExists(lane1.resolve("work.txt"));
    }

    @Test
    void aBuildInTheLaneRunsAndTheNetworkIsReachable() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin");
        Files.writeString(lane1.resolve("Hello.java"), "public class Hello { public static void main(String[] a) { System.out.println(\"built \" + (1 + 2)); } }");
        ExecutionResult build = run("'" + java.resolve("javac") + "' -d out Hello.java && '" + java.resolve("java") + "' -cp out Hello", 120);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, build.outcome(), shown(build));
        assertEquals("built 3", build.stdout().strip());

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    socket.getOutputStream().write("pong\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The assertion on the command's output reports it.
                }
            });
            Files.writeString(lane1.resolve("Net.java"), "public class Net { public static void main(String[] a) throws Exception {"
                    + " try (var s = new java.net.Socket(\"127.0.0.1\", Integer.parseInt(a[0]))) { System.out.println(new String(s.getInputStream().readAllBytes()).strip()); } } }");
            ExecutionResult network = run("'" + java.resolve("java") + "' Net.java " + server.getLocalPort(), 120);
            acceptor.join(5_000);
            assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, network.outcome(), shown(network));
            assertEquals("pong", network.stdout().strip());
        }
        deleteTree(lane1.resolve("out"));
        Files.deleteIfExists(lane1.resolve("Hello.java"));
        Files.deleteIfExists(lane1.resolve("Net.java"));
    }

    @Test
    void theEnvironmentCarriesNoSynesisIdentityOrAgentSocket() {
        ExecutionResult result = executor.execute(new ExecutionRequest(List.of("/bin/sh", "-c", "env"), lane1, ".", 30, control));
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), shown(result));
        assertFalse(result.stdout().contains("SYNESIS_"), shown(result));
        assertFalse(result.stdout().contains("SSH_AUTH_SOCK"), shown(result));
        assertFalse(result.stdout().contains("XDG_RUNTIME_DIR"), shown(result));
        assertTrue(result.stdout().contains("GIT_ALTERNATE_OBJECT_DIRECTORIES=[PROJECT_ROOT]/.git/objects"), shown(result));
    }

    /**
     * The control experiment: the same kind of attack, run with a stand-in that confines nothing, does succeed, so
     * the BREACH marker and the checks from outside can fail.
     */
    @Test
    void theSameAttackSucceedsWithoutASandbox() throws Exception {
        Path canary = control.resolve(".synesis/canary.txt");
        ProjectProcessExecutor unconfined = new ProjectProcessExecutor(new Sandbox() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public SandboxAvailability probe() {
                return SandboxAvailability.ready();
            }

            @Override
            public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) {
                return new WrappedCommand(argv, environment);
            }
        }, UnsandboxedAllowance.DENY);
        ExecutionResult result = unconfined.execute(new ExecutionRequest(List.of("/bin/sh", "-c", "( echo x > '" + canary + "' ) && echo BREACH"), lane1, ".", 30, control));
        assertTrue(result.stdout().contains("BREACH"), shown(result));
        assertTrue(Files.exists(canary));
        Files.delete(canary);
    }

    @Test
    void aTimedOutCommandThatForkedIsKilled() {
        long started = System.nanoTime();
        ExecutionResult result = run("sleep 300 & sleep 300; echo never", 3);
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_TIMED_OUT, result.outcome(), shown(result));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 60);
    }
}
