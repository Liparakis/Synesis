package org.synesis.workspace.application.project.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.project.execution.sandbox.Sandbox;
import org.synesis.workspace.application.project.execution.sandbox.SandboxAvailability;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.application.project.execution.sandbox.SandboxSetupException;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;
import org.synesis.workspace.test.PortableTestCommand;

/**
 * Tests the executor's sandbox choke point with stand-in sandboxes, so it runs on every platform: an unavailable
 * sandbox refuses, a failed setup never falls back to running unconfined, the signed allowance is the only way
 * around an unavailable sandbox, and the policy comes from the control side of the lane binding.
 */
class ProjectProcessExecutorSandboxTest {

    private static Sandbox sandbox(SandboxAvailability availability, AtomicReference<SandboxPolicy> seen, boolean failSetup) {
        return new Sandbox() {
            @Override
            public String name() {
                return "stand-in";
            }

            @Override
            public SandboxAvailability probe() {
                return availability;
            }

            @Override
            public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
                seen.set(policy);
                if (failSetup) {
                    throw new SandboxSetupException("mount refused");
                }
                return new WrappedCommand(argv, environment);
            }
        };
    }

    private static ExecutionRequest marker(Path root, Path marker) throws IOException {
        Path release = Files.writeString(root.resolve("release-" + marker.getFileName()), "go");
        List<String> argv = new ArrayList<>(PortableTestCommand.fixture("gate", marker.toString(), release.toString()));
        return new ExecutionRequest(argv, root, ".", 20, root);
    }

    @Test
    void unavailableSandboxRefusesWithErrorMessageAndFixAndStartsNothing() throws Exception {
        Path root = Files.createTempDirectory("synesis-sbx-refuse-");
        Path marker = root.resolve("started.txt");
        Sandbox none = sandbox(SandboxAvailability.unavailable("no bwrap", "install bubblewrap"), new AtomicReference<>(), false);

        ExecutionResult result = new ProjectProcessExecutor(none, UnsandboxedAllowance.DENY).execute(marker(root, marker));

        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_SANDBOX_UNAVAILABLE, result.outcome());
        assertTrue(result.stderr().contains("ERROR=SANDBOX_UNAVAILABLE"), result.stderr());
        assertTrue(result.stderr().contains("MESSAGE=Commands asked for by agents run in a sandbox on this computer, and none is available: no bwrap."), result.stderr());
        assertTrue(result.stderr().contains("FIX=install bubblewrap"), result.stderr());
        assertEquals("NONE", result.sandbox());
        assertEquals("NONE", result.toMap().get("sandbox"));
        assertFalse(Files.exists(marker));
        assertEquals(org.synesis.workspace.agent.AgentStatus.BLOCKED, result.toAgentResponse(Map.of()).status());
        assertEquals(org.synesis.workspace.agent.AgentReason.SANDBOX_UNAVAILABLE, result.toAgentResponse(Map.of()).reason());
    }

    @Test
    void theSignedAllowanceLetsACommandRunOnlyWhenNoSandboxIsAvailable() throws Exception {
        Path root = Files.createTempDirectory("synesis-sbx-allow-");
        Path marker = root.resolve("started.txt");
        AtomicReference<Path> asked = new AtomicReference<>();
        Sandbox none = sandbox(SandboxAvailability.unavailable("no bwrap", "install bubblewrap"), new AtomicReference<>(), false);

        ExecutionResult result = new ProjectProcessExecutor(none, controlRoot -> {
            asked.set(controlRoot);
            return true;
        }).execute(marker(root, marker));

        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome(), result.stderr());
        assertEquals("NONE_ALLOWED_BY_DECISION", result.sandbox());
        assertEquals("NONE_ALLOWED_BY_DECISION", result.toMap().get("sandbox"));
        assertEquals(root, asked.get());
        assertTrue(Files.exists(marker));

        // An allowance never lets a computer that can sandbox skip the sandbox: it is not even asked.
        Path second = root.resolve("second.txt");
        AtomicReference<SandboxPolicy> seen = new AtomicReference<>();
        ExecutionResult confined = new ProjectProcessExecutor(sandbox(SandboxAvailability.ready(), seen, false), controlRoot -> {
            throw new AssertionError("asked although a sandbox is available");
        }).execute(marker(root, second));
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, confined.outcome(), confined.stderr());
        assertEquals("stand-in", confined.sandbox());
        assertEquals("stand-in", confined.toMap().get("sandbox"));
    }

    @Test
    void aFailedSetupIsARefusalAndIsNeverRetriedUnconfined() throws Exception {
        Path root = Files.createTempDirectory("synesis-sbx-setup-");
        Path marker = root.resolve("started.txt");
        AtomicReference<SandboxPolicy> seen = new AtomicReference<>();

        ExecutionResult result = new ProjectProcessExecutor(sandbox(SandboxAvailability.ready(), seen, true), controlRoot -> true).execute(marker(root, marker));

        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_SANDBOX_SETUP_FAILED, result.outcome());
        assertTrue(result.stderr().contains("ERROR=SANDBOX_SETUP_FAILED"), result.stderr());
        assertTrue(result.stderr().contains("mount refused"), result.stderr());
        assertFalse(Files.exists(marker), "the command must not start unconfined after a failed setup");
    }

    @Test
    void theDefaultExecutorFailsClosedWhenTheSandboxIsMissing() {
        assertFalse(UnsandboxedAllowance.DENY.allowedFor(Path.of("anything")));
        assertFalse(UnsandboxedAllowance.DENY.allowedFor(null));
    }

    @Test
    void theEnvironmentHandedToTheSandboxIsScrubbed() throws Exception {
        Path root = Files.createTempDirectory("synesis-sbx-env-");
        Map<String, String> environment = new java.util.HashMap<>(Map.of("SYNESIS_CONNECTION", "x", "SSH_AUTH_SOCK", "/s", "DBUS_SESSION_BUS_ADDRESS", "d", "AWS_PROFILE", "a", "GPG_AGENT_INFO", "g",
                "XDG_RUNTIME_DIR", "/run/user/1", "MY_PASSWORD_KEY", "k", "PATH", "/bin"));
        ProjectProcessExecutor.filterEnvironment(environment);
        assertEquals(Map.of("PATH", "/bin"), environment);
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void policyFindsTheLaneAdministrativeDirectoryFromTheControlSideNotFromTheWorktreeFile() throws Exception {
        Path base = Files.createTempDirectory("synesis-sbx-policy-").toRealPath();
        Path control = base.resolve("control");
        git(base, "init", "-q", "-b", "main", control.toString());
        Files.writeString(control.resolve("f.txt"), "x");
        git(control, "add", "f.txt");
        git(control, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "first");
        Path lane = base.resolve("lane");
        git(control, "worktree", "add", "-q", "--detach", lane.toString());
        Path administrative = control.resolve(".git/worktrees/lane").toRealPath();
        // The worktree's .git file is the agent's to rewrite: the policy must not believe it.
        Path hostile = Files.createDirectories(base.resolve("hostile/.git/worktrees/lane"));
        Files.writeString(lane.resolve(".git"), "gitdir: " + hostile + "\n");
        Path temp = Files.createDirectories(base.resolve("temp"));

        SandboxPolicy policy = LaneSandboxPolicy.derive(lane, control, temp);

        assertTrue(policy.writable().contains(administrative), policy.toString());
        assertFalse(policy.writable().contains(hostile));
        assertTrue(policy.writable().contains(lane.toRealPath()));
        assertTrue(policy.writable().contains(temp.toRealPath()));
        assertTrue(policy.readOnly().contains(control.resolve(".git").toRealPath()));
        assertTrue(policy.readOnlyWithin().contains(administrative.resolve("gitdir")));
        assertTrue(policy.readOnlyWithin().contains(administrative.resolve("commondir")));
        assertTrue(policy.readOnlyWithin().contains(lane.toRealPath().resolve(".git")));
        assertTrue(policy.readOnlyWithin().contains(lane.toRealPath().resolve(".mcp.json")));
        assertTrue(policy.emptyReadOnlyFiles().contains(administrative.resolve("config.worktree")));
        assertTrue(policy.hidden().contains(control.toRealPath()));
        assertTrue(policy.hidden().contains(base));
        assertEquals(controlObjects(control), policy.environment().get("GIT_ALTERNATE_OBJECT_DIRECTORIES"));
        assertEquals(temp.toRealPath().resolve("objects").toString(), policy.environment().get("GIT_OBJECT_DIRECTORY"));
        assertEquals(temp.toRealPath().resolve("home").toString(), policy.environment().get("HOME"));
    }

    @Test
    void policyWithoutAControlRootNeverMountsOrExposesAGitDirectory() throws Exception {
        Path root = Files.createTempDirectory("synesis-sbx-nocontrol-").toRealPath();
        Path temp = Files.createDirectories(root.resolve("temp"));
        SandboxPolicy policy = LaneSandboxPolicy.derive(root, null, temp);
        assertTrue(policy.readOnly().stream().noneMatch(path -> path.endsWith(".git")));
        assertFalse(policy.environment().containsKey("GIT_ALTERNATE_OBJECT_DIRECTORIES"));
    }

    private static String controlObjects(Path control) throws IOException {
        return control.resolve(".git/objects").toRealPath().toString();
    }

    private static void git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), command + " -> " + output);
    }
}
