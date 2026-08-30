package org.synesis.cli.workspace;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.SynesisCli;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;

/** Verifies the CLI mutation boundary rejects unsafe workspace operations. */
class FullMutationSafetyTest {

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

    private static Map<Path, String> captureDirectoryState(Path root) throws Exception {
        Map<Path, String> state = new HashMap<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .toList()) {
                byte[] bytes = Files.readAllBytes(path);
                String hash = HexFormat.of()
                        .formatHex(md.digest(bytes));
                state.put(root.relativize(path), hash);
            }
        }
        return state;
    }

    @Test
    void provesZeroRuntimeMutationDuringDryRun(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("fixture-project");
        Files.createDirectories(projectRoot);
        new ProjectApplicationService().init(projectRoot);

        // Add dummy sessions and snapshots to fixture
        Path sessionsDir = projectRoot.resolve(".synesis/local/sessions");
        Files.createDirectories(sessionsDir);
        Path sessionFile = sessionsDir.resolve("codex-dummy.json");
        Files.writeString(sessionFile,
                "{\"schemaVersion\":2,\"sessionId\":\"session-123\",\"projectId\":\"p1\",\"nodeId\":\"n1\",\"provider\":\"codex\",\"providerInstanceFingerprint\":\"fp1\",\"supervisorId\":\"s1\",\"workerId\":\"w1\",\"controlCheckoutPath\":\""
                        + projectRoot.toString()
                        .replace("\\", "\\\\")
                        + "\",\"baseCommit\":\"0123456789012345678901234567890123456789\",\"status\":\"COMPLETED\",\"createdAtEpochMillis\":1000,\"lastSeenEpochMillis\":2000,\"lastVerifiedProjectSequence\":1,\"bindingVersion\":1}");

        Path snapshotsDir = projectRoot.resolve(".synesis/local/snapshots");
        Files.createDirectories(snapshotsDir);
        Path snapshotFile = snapshotsDir.resolve("task-123.json");
        Files.writeString(snapshotFile, "{\"taskId\":\"task-123\"}");

        // Snapshot initial filesystem state and SHA-256 file hashes
        Map<Path, String> beforeState = captureDirectoryState(projectRoot);
        int initialFileCount = beforeState.size();

        Invocation invocation = createInvocation(tempDir);

        // Execute dry-run cleanup
        int exitCode = SynesisCli.execute(new String[]{"cleanup", "--dry-run", "--project", projectRoot.toString()},
                invocation.runtime());

        assertEquals(ExitCodes.OK, exitCode);

        // Snapshot state after dry-run
        Map<Path, String> afterState = captureDirectoryState(projectRoot);

        // Assert zero runtime mutations
        assertEquals(initialFileCount, afterState.size(), "FILES_DELETED must be 0");
        assertEquals(beforeState, afterState, "Filesystem state modified during dry-run");
        assertTrue(invocation.output()
                .contains("MUTATIONS_PERFORMED=0"));
    }

    /** Holds isolated streams and runtime for one safety-boundary invocation. */
    private record Invocation(CliRuntime runtime, ByteArrayOutputStream out, ByteArrayOutputStream err) {

        private String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
