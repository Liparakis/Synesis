package org.synesis.workspace.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.provider.claude.ClaudeCodeProviderIntegration;
import org.synesis.workspace.provider.codex.CodexProviderIntegration;

/**
 * Verifies direct, shell-free provider process supervision.
 */
final class ProviderProcessSupervisorTest {

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable)
                .toString();
    }

    /**
     * A directly launched process can be observed and cleanly interrupted.
     */
    @Test
    void startsObservesAndInterruptsOneLane() throws Exception {
        try (ProviderProcessSupervisor supervisor = new ProviderProcessSupervisor()) {
            var request = new ProviderProcessSupervisor.StartRequest(
                    "lane-a", "test", Path.of("."),
                    List.of(javaExecutable(), "-version"));
            var started = supervisor.start(request);
            assertEquals("lane-a", started.laneId());
            assertEquals(started.generation(), supervisor.generation("lane-a"));
            var observation = supervisor.observe("lane-a", "test");
            assertEquals(ProviderProcessSupervisor.State.RUNNING, observation.state());
            CountDownLatch exited = new CountDownLatch(1);
            supervisor.onExit("lane-a", "test", _ -> exited.countDown());
            supervisor.interrupt("lane-a", Duration.ofSeconds(2));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    /**
     * A lane restart receives a distinct generation rather than reusing stale process identity.
     */
    @Test
    void laneRestartUsesDistinctGeneration() throws Exception {
        try (ProviderProcessSupervisor supervisor = new ProviderProcessSupervisor()) {
            Path currentDirectory = Path.of(".");
            var first = supervisor.start(new ProviderProcessSupervisor.StartRequest(
                    "lane-generation", "test", currentDirectory, List.of(javaExecutable(), "-version")));
            for (int i = 0; i < 200 && supervisor.observe("lane-generation", "test")
                    .state()
                    == ProviderProcessSupervisor.State.RUNNING; i++) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10L));
            }
            var second = supervisor.start(new ProviderProcessSupervisor.StartRequest(
                    "lane-generation", "test", currentDirectory, List.of(javaExecutable(), "-version")));
            org.junit.jupiter.api.Assertions.assertTrue(second.generation() > first.generation());
        }
    }

    /**
     * Shell wrappers are rejected so provider supervision preserves argv semantics.
     */
    @Test
    void rejectsShellWrappers() {
        try (ProviderProcessSupervisor supervisor = new ProviderProcessSupervisor()) {
            var request = new ProviderProcessSupervisor.StartRequest(
                    "lane-a", "test", Path.of("."), List.of("cmd.exe", "/c", "echo", "unsafe"));
            assertThrows(IllegalArgumentException.class, () -> supervisor.start(request));
        }
    }

    /**
     * Provider integrations expose only documented noninteractive argv.
     */
    @Test
    void exposesDeclaredProviderDrivers() {
        Path worktree = Path.of("C:\\lane");
        assertEquals("codex",
                new CodexProviderIntegration().autonomousCommand(worktree, "probe")
                        .orElseThrow()
                        .getFirst());
        assertEquals("claude",
                new ClaudeCodeProviderIntegration().autonomousCommand(worktree, "probe")
                        .orElseThrow()
                        .getFirst());
    }
}
