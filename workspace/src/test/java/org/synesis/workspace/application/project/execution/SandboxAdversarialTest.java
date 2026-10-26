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
import java.util.Locale;
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
import org.synesis.workspace.application.project.execution.sandbox.WindowsLowIntegritySandbox;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;

/**
 * The adversarial test of ADR-0104 stages 2 and 3: one agent command per attack, run through the real
 * {@link ProjectProcessExecutor} with the real platform sandbox (bubblewrap on Linux, a low-integrity process on
 * Windows). Every attack must fail, and the state afterwards is checked from outside the sandbox. The ordinary work an
 * agent needs (Git in its lane, a build, the network) must still succeed.
 *
 * <p>The attacks are the same on both systems. Each is written once as a small script fragment ({@link #write},
 * {@link #remove}, {@link #git} and so on) that becomes a POSIX shell command on Linux and a Windows PowerShell
 * command on Windows. Git for Windows' own shell is not used: it is an MSYS program and cannot start at the Low
 * integrity level (it fails creating its object directory), which the Windows sandbox documents as a limit.</p>
 *
 * <p>On a host with no working sandbox the tests are skipped with the explicit reason {@code SKIPPED, no sandbox}.
 * A host that must have one sets {@code SYNESIS_REQUIRE_SANDBOX=true}, and then the skip is a failure.</p>
 */
class SandboxAdversarialTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String KEY_BYTES = "KEYBYTES-0f3a9c-do-not-print";
    private static final String FORK_MARKER = "synesis-fork-marker-";

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
        Sandbox sandbox = WINDOWS ? new WindowsLowIntegritySandbox() : new LinuxBubblewrapSandbox();
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

    // ---- the script dialects: one fragment per kind of attack, a shell command or a PowerShell command ----

    private static String quoted(Path path) {
        return "'" + path + "'";
    }

    private static String write(Path path, String text) {
        return WINDOWS ? "Set-Content -LiteralPath " + quoted(path) + " -Value '" + text + "' -ErrorAction Stop" : "echo '" + text + "' > " + quoted(path);
    }

    private static String append(Path path, String text) {
        return WINDOWS ? "Add-Content -LiteralPath " + quoted(path) + " -Value '" + text + "' -ErrorAction Stop" : "echo '" + text + "' >> " + quoted(path);
    }

    private static String remove(Path path) {
        return WINDOWS ? "Remove-Item -LiteralPath " + quoted(path) + " -Force -ErrorAction Stop" : "rm " + quoted(path);
    }

    /**
     * Like {@link #remove}, but on Linux also overrides a write-protected file (an object file is read-only), and
     * then a path that does not exist is a success: use it only on a file that exists where the command runs.
     */
    private static String removeProtected(Path path) {
        return WINDOWS ? remove(path) : "rm -f " + quoted(path);
    }

    private static String removeTree(Path path) {
        return WINDOWS ? "Remove-Item -LiteralPath " + quoted(path) + " -Recurse -Force -ErrorAction Stop" : "rm -rf " + quoted(path);
    }

    private static String move(Path from, Path to) {
        return WINDOWS ? "Move-Item -LiteralPath " + quoted(from) + " -Destination " + quoted(to) + " -ErrorAction Stop" : "mv " + quoted(from) + " " + quoted(to);
    }

    private static String read(Path path) {
        return WINDOWS ? "Get-Content -LiteralPath " + quoted(path) + " -ErrorAction Stop" : "cat " + quoted(path);
    }

    private static String list(Path path) {
        return WINDOWS ? "Get-ChildItem -LiteralPath " + quoted(path) + " -Force -Name" : "ls " + quoted(path);
    }

    private static String listShowing(Path path, String name) {
        return WINDOWS ? "if (-not (Get-ChildItem -LiteralPath " + quoted(path) + " -Name -ErrorAction Stop | Select-String " + name + ")) { throw 'no' }"
                : "ls " + quoted(path) + " | grep -q " + name;
    }

    private static String makeDirectory(Path path) {
        return WINDOWS ? "New-Item -ItemType Directory -Force -Path " + quoted(path) + " -ErrorAction Stop | Out-Null" : "mkdir -p " + quoted(path);
    }

    private static String git(String arguments) {
        return WINDOWS ? "git " + arguments + "; if ($LASTEXITCODE -ne 0) { throw 'git failed' }" : "git " + arguments;
    }

    private static String then(String... steps) {
        return String.join(WINDOWS ? "; " : " && ", steps);
    }

    private static List<String> command(String script) {
        if (WINDOWS) {
            return List.of(Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
        }
        return List.of("/bin/sh", "-c", script);
    }

    /**
     * Runs one script as an agent's command in lane 1 and returns its result.
     */
    private static ExecutionResult run(String script) {
        return run(script, 60);
    }

    private static ExecutionResult run(String script, int timeoutSeconds) {
        return executor.execute(new ExecutionRequest(command(script), lane1, ".", timeoutSeconds, control));
    }

    private static String shown(ExecutionResult result) {
        return result.outcome() + " stdout=" + result.stdout() + " stderr=" + result.stderr();
    }

    /**
     * An attack script: the attack runs in a subshell (a try block on Windows); success of the attack prints BREACH.
     */
    private static void assertAttackFails(String attack) {
        String script = WINDOWS
                ? "$ErrorActionPreference = 'Stop'; $breach = $false; try { " + attack + "; $breach = $true } catch { }; if ($breach) { 'BREACH' }; 'DONE'"
                : "( " + attack + " ) >/dev/null 2>&1 && echo BREACH; echo DONE";
        ExecutionResult result = run(script);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), shown(result));
        assertTrue(result.stdout().contains("DONE"), shown(result));
        assertFalse(result.stdout().contains("BREACH"), "attack succeeded: " + attack + " " + shown(result));
        assertFalse((result.stdout() + result.stderr()).contains(KEY_BYTES), shown(result));
    }

    @Test
    void policyFileInTheControlCheckoutIsNotWritable() throws Exception {
        String before = Files.readString(control.resolve(".synesis/project.json"));
        assertAttackFails(write(control.resolve(".synesis/project.json"), "{}"));
        assertAttackFails(remove(control.resolve(".synesis/project.json")));
        assertAttackFails(write(control.resolve("planted.txt"), "x"));
        assertEquals(before, Files.readString(control.resolve(".synesis/project.json")));
        assertFalse(Files.exists(control.resolve("planted.txt")));
    }

    @Test
    void mainCannotBeMovedByGit() throws Exception {
        String other = git(control, "rev-parse", "refs/heads/main~1");
        assertAttackFails(git("-C '" + control + "' update-ref refs/heads/main " + other));
        assertAttackFails(git("update-ref refs/heads/main " + other));
        assertAttackFails(git("update-ref refs/synesis/planted " + other));
        assertAttackFails(git("-c user.name=t -c user.email=t@t commit --allow-empty -q -m planted"));
        assertAttackFails(git("branch planted"));
        assertEquals(mainHead, git(control, "rev-parse", "refs/heads/main"));
        assertEquals("", git(control, "for-each-ref", "refs/synesis/", "refs/heads/planted"));
    }

    @Test
    void sharedGitConfigHooksAndWorktreeConfigAreNotWritable() throws Exception {
        String configBefore = Files.readString(controlGit.resolve("config"));
        assertAttackFails(git("config core.fsmonitor /tmp/pwn"));
        assertAttackFails(git("config --local core.hooksPath /tmp"));
        assertAttackFails(append(controlGit.resolve("config"), "[core]"));
        assertAttackFails(write(controlGit.resolve("hooks/pre-commit"), "#!/bin/sh"));
        assertAttackFails(write(adminDirectory.resolve("config.worktree"), "[core] fsmonitor = /tmp/pwn"));
        assertAttackFails(git("config --worktree core.fsmonitor /tmp/pwn"));
        assertEquals(configBefore, Files.readString(controlGit.resolve("config")));
        assertFalse(Files.exists(controlGit.resolve("hooks/pre-commit")));
        assertEquals("", Files.readString(adminDirectory.resolve("config.worktree")));
    }

    @Test
    void gitDirectoryPointersOfTheLaneCannotBeRewritten() throws Exception {
        String pointer = Files.readString(lane1.resolve(".git"));
        String commondir = Files.readString(adminDirectory.resolve("commondir"));
        String gitdir = Files.readString(adminDirectory.resolve("gitdir"));
        assertAttackFails(write(lane1.resolve(".git"), "gitdir: /tmp/hostile"));
        assertAttackFails(removeProtected(lane1.resolve(".git")));
        assertAttackFails(write(adminDirectory.resolve("commondir"), "/tmp/hostile"));
        assertAttackFails(write(adminDirectory.resolve("gitdir"), "/tmp/hostile/.git"));
        assertAttackFails(remove(adminDirectory.resolve("commondir")));
        assertAttackFails(move(adminDirectory.resolve("gitdir"), adminDirectory.resolve("gitdir.moved")));
        assertEquals(pointer, Files.readString(lane1.resolve(".git")));
        assertEquals(commondir, Files.readString(adminDirectory.resolve("commondir")));
        assertEquals(gitdir, Files.readString(adminDirectory.resolve("gitdir")));
    }

    @Test
    void keysAndApplicationStateAreNotReadable() {
        Path stateRoot = stateDirectory.getParent().getParent().getParent();
        String script = WINDOWS
                ? "$ErrorActionPreference = 'SilentlyContinue'; try { " + read(control.resolve(".synesis/local/profile/link/identity.bin")) + " } catch { }; try { " + read(daemonEndpoint)
                        + " } catch { }; " + list(control.resolve(".synesis")) + "; "
                        + "Get-ChildItem -Recurse -Force -Path " + quoted(stateRoot) + ", " + quoted(control) + " -Include identity.bin, daemon.endpoint | ForEach-Object { $_.Name }; "
                        + "Get-ChildItem -Recurse -Force -File -Path " + quoted(stateRoot) + ", " + quoted(control) + " | Select-String -Pattern " + KEY_BYTES + "; 'DONE'"
                : "cat '" + control.resolve(".synesis/local/profile/link/identity.bin") + "'; cat '" + daemonEndpoint + "'; ls '" + control.resolve(".synesis") + "'; "
                        + "find / -xdev \\( -name identity.bin -o -name daemon.endpoint \\) 2>/dev/null; "
                        + "grep -r " + KEY_BYTES + " /tmp \"$HOME\" 2>/dev/null; echo DONE";
        ExecutionResult result = run(script);
        assertTrue(result.stdout().contains("DONE"), shown(result));
        assertFalse((result.stdout() + result.stderr()).contains(KEY_BYTES), shown(result));
        assertFalse(result.stdout().contains("identity.bin"), shown(result));
        // On Windows the entries of a folder on the way to the lane stay listable (Git and PowerShell fail on a parent
        // that cannot be read), so a file directly in the state folder shows its name but not its content.
        assertTrue(WINDOWS || !result.stdout().contains("daemon.endpoint"), shown(result));
        assertFalse(result.stdout().contains("project.json"), shown(result));
    }

    @Test
    void otherLanesAndTheIntegrationWorktreeAreNotWritableOrReadable() throws Exception {
        assertAttackFails(write(lane2.resolve("planted.txt"), "x"));
        assertAttackFails(write(lane2.resolve("README.md"), "x"));
        assertAttackFails(write(integration.resolve("planted.txt"), "x"));
        assertAttackFails(listShowing(lane2, "README"));
        assertAttackFails(read(integration.resolve("README.md")));
        assertFalse(Files.exists(lane2.resolve("planted.txt")));
        assertFalse(Files.exists(integration.resolve("planted.txt")));
        assertEquals("control\n", Files.readString(lane2.resolve("README.md")).replace("\r\n", "\n"));
    }

    @Test
    void localReplicationAndDaemonFilesAreNotWritable() throws Exception {
        String before = Files.readString(control.resolve(".synesis/coordination/replication.properties"));
        assertAttackFails(write(control.resolve(".synesis/coordination/replication.properties"), "code.remote=evil"));
        assertAttackFails(write(daemonEndpoint, "port=2"));
        assertAttackFails(then(makeDirectory(daemonEndpoint.getParent().resolve("link")), write(daemonEndpoint.getParent().resolve("link/identity.bin"), "x")));
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
        assertAttackFails(WINDOWS ? then("Set-ItemProperty -LiteralPath " + quoted(object) + " -Name IsReadOnly -Value $false", write(object, "evil"))
                : "chmod u+w '" + object + "'; echo evil > '" + object + "'");
        assertAttackFails(removeProtected(object));
        assertAttackFails(write(controlGit.resolve("objects/ev"), "evil"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(object)));
        git(control, "fsck", "--no-dangling");
    }

    @Test
    void providerConfigurationOfTheLaneIsNotWritable() throws Exception {
        assertAttackFails(write(lane1.resolve(".claude/settings.json"), "{\"hooks\":{}}"));
        assertAttackFails(write(lane1.resolve(".mcp.json"), "{\"mcpServers\":{}}"));
        assertAttackFails(removeTree(lane1.resolve(".claude")));
        assertAttackFails(move(lane1.resolve(".claude"), lane1.resolve("moved")));
        assertAttackFails(remove(lane1.resolve(".mcp.json")));
        assertEquals("{}", Files.readString(lane1.resolve(".claude/settings.json")).strip());
        assertEquals("{}", Files.readString(lane1.resolve(".mcp.json")).strip());
    }

    /**
     * The other control experiment: every kind of fragment the attacks are made of does succeed where the lane may
     * write, so an attack that fails fails because of the sandbox and not because of a mistake in its script.
     */
    @Test
    void theAttackFragmentsSucceedWhereTheLaneMayWrite() {
        Path scratch = lane1.resolve("fragments");
        Path file = scratch.resolve("a.txt");
        Path renamed = scratch.resolve("b.txt");
        ExecutionResult result = run(then(makeDirectory(scratch), write(file, "x"), append(file, "y"), read(file), listShowing(scratch, "a.txt"), move(file, renamed),
                remove(renamed), makeDirectory(scratch.resolve("tree")), removeTree(scratch), git("status --porcelain"), "echo FRAGMENTS_OK"));
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), shown(result));
        assertTrue(result.stdout().contains("FRAGMENTS_OK"), shown(result));
        assertFalse(Files.exists(scratch));
    }

    @Test
    void symlinkInTheWorktreeToTheControlCheckoutIsDangling() throws Exception {
        assumeTrue(!WINDOWS, "on Windows the labels protect the target, not a mount view; a symlink there needs a privilege");
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
        ExecutionResult write = run(WINDOWS ? then(write(lane1.resolve("work.txt"), "hello"), read(lane1.resolve("work.txt"))) : "echo hello > work.txt && cat work.txt");
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, write.outcome(), shown(write));
        assertEquals("hello", write.stdout().strip());

        ExecutionResult status = run(WINDOWS
                ? then(git("status --porcelain"), git("diff --stat"), git("log --oneline -3"), git("add work.txt"), git("status --porcelain"))
                : "git status --porcelain && git diff --stat && git log --oneline | head -3 && git add work.txt && git status --porcelain");
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
        String javac = java.resolve(WINDOWS ? "javac.exe" : "javac").toString();
        String javaCommand = java.resolve(WINDOWS ? "java.exe" : "java").toString();
        Files.writeString(lane1.resolve("Hello.java"), "public class Hello { public static void main(String[] a) { System.out.println(\"built \" + (1 + 2)); } }");
        ExecutionResult build = run(WINDOWS ? "& '" + javac + "' -d out Hello.java; if ($LASTEXITCODE -eq 0) { & '" + javaCommand + "' -cp out Hello }"
                : "'" + javac + "' -d out Hello.java && '" + javaCommand + "' -cp out Hello", 120);
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
            ExecutionResult network = run((WINDOWS ? "& '" : "'") + javaCommand + "' Net.java " + server.getLocalPort(), 120);
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
        ExecutionResult result = executor.execute(new ExecutionRequest(command(WINDOWS ? "Get-ChildItem Env: | ForEach-Object { $_.Name + '=' + $_.Value }" : "env"), lane1, ".", 30, control));
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), shown(result));
        assertFalse(result.stdout().contains("SYNESIS_"), shown(result));
        assertFalse(result.stdout().contains("SSH_AUTH_SOCK"), shown(result));
        assertFalse(result.stdout().contains("XDG_RUNTIME_DIR"), shown(result));
        assertTrue(result.stdout().contains(WINDOWS ? "GIT_ALTERNATE_OBJECT_DIRECTORIES=[PROJECT_ROOT]" : "GIT_ALTERNATE_OBJECT_DIRECTORIES=[PROJECT_ROOT]/.git/objects"), shown(result));
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
        ExecutionResult result = unconfined.execute(new ExecutionRequest(command(then(write(canary, "x"), "echo BREACH")), lane1, ".", 30, control));
        assertTrue(result.stdout().contains("BREACH"), shown(result));
        assertTrue(Files.exists(canary));
        Files.delete(canary);
    }

    /**
     * A lane that has run is writable only while it runs: a command in lane 1 afterwards cannot write lane 2, whose
     * own command ran first (on Windows lane 2 was labelled Low for that run and must be restored).
     */
    @Test
    void aLaneThatHasRunIsNotWritableByAnotherLanesCommandAfterwards() throws Exception {
        ExecutionResult own = executor.execute(new ExecutionRequest(command(then(write(Path.of("lane2-own.txt"), "mine"), "echo ok")), lane2, ".", 60, control));
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, own.outcome(), shown(own));
        assertTrue(Files.isRegularFile(lane2.resolve("lane2-own.txt")));
        assertAttackFails(append(lane2.resolve("lane2-own.txt"), "x"));
        assertAttackFails(write(lane2.resolve("planted-after.txt"), "x"));
        assertEquals("mine", Files.readString(lane2.resolve("lane2-own.txt")).strip());
        assertFalse(Files.exists(lane2.resolve("planted-after.txt")));
        Files.delete(lane2.resolve("lane2-own.txt"));
    }

    @Test
    void aTimedOutCommandThatForkedIsKilledWithItsChildren() throws Exception {
        long started = System.nanoTime();
        String marker = FORK_MARKER + UUID.randomUUID();
        ExecutionResult result = run(WINDOWS
                ? "Start-Process -FilePath powershell -ArgumentList '-NoProfile','-Command','Start-Sleep 300 # " + marker + "'; Start-Sleep 300"
                : "sleep 300 & sleep 300; echo never", 3);
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_TIMED_OUT, result.outcome(), shown(result));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 60);
        if (WINDOWS) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (forkedChildAlive(marker) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertFalse(forkedChildAlive(marker), "the child the command forked is killed with the job");
        }
    }

    private static boolean forkedChildAlive(String marker) {
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.info().commandLine().orElse("").contains(marker));
    }
}
