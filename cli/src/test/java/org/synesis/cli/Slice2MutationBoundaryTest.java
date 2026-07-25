package org.synesis.cli;

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
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.link.transport.Onboarding;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.cleanup.LifecyclePathVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Slice2MutationBoundaryTest {

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

    private record Invocation(CliRuntime runtime, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        private String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void provesControlCheckoutAndDurableStatePreservedDuringExecute(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("boundary-project");
        Files.createDirectories(projectRoot);
        new ProjectApplicationService().init(projectRoot);

        Path sessionsDir = projectRoot.resolve(".synesis/local/sessions");
        Files.createDirectories(sessionsDir);
        Path sessionFile = sessionsDir.resolve("codex-dummy.json");
        Files.writeString(sessionFile, "{\"sessionId\":\"s1\"}");

        Path snapshotsDir = projectRoot.resolve(".synesis/local/snapshots");
        Files.createDirectories(snapshotsDir);
        Path snapshotFile = snapshotsDir.resolve("task-1.json");
        Files.writeString(snapshotFile, "{\"taskId\":\"t1\"}");

        // Create an eligible disposable temp file under external workspace root
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(projectRoot);
        Path tempDirSub = workspaceRoot.resolve("tmp-sub");
        Files.createDirectories(tempDirSub);
        Path disposableTempFile = tempDirSub.resolve("expired.tmp-1");
        Files.writeString(disposableTempFile, "disposable content");

        // Snapshot control checkout state hashes
        Map<Path, String> beforeControlState = captureDirectoryState(projectRoot);

        Invocation prepInv = createInvocation(tempDir);
        int prepCode = SynesisCli.execute(new String[]{"cleanup", "--prepare", "--project", projectRoot.toString()}, prepInv.runtime());
        assertEquals(ExitCodes.OK, prepCode);
        String prepOut = prepInv.output();
        assertTrue(prepOut.contains("CLEANUP_RESULT=PLAN_PREPARED"));

        String planId = extractPlanId(prepOut);

        // Execute plan
        Invocation execInv = createInvocation(tempDir);
        int execCode = SynesisCli.execute(new String[]{"cleanup", "--execute", planId, "--project", projectRoot.toString()}, execInv.runtime());
        assertEquals(ExitCodes.OK, execCode);
        String execOut = execInv.output();
        assertTrue(execOut.contains("CLEANUP_RESULT=SUCCESS"));

        // Snapshot control checkout state after execution
        Map<Path, String> afterControlState = captureDirectoryState(projectRoot);

        // Assert CONTROL_CHECKOUT_MODIFIED=false
        assertEquals(beforeControlState, afterControlState, "CONTROL_CHECKOUT_MODIFIED must be false");
        assertTrue(execOut.contains("CONTROL_CHECKOUT_MODIFIED=false"));
        assertTrue(execOut.contains("EVENT_LOG_MODIFIED=false"));
    }

    private static String extractPlanId(String output) {
        for (String line : output.lines().toList()) {
            if (line.startsWith("PLAN=")) {
                return line.substring("PLAN=".length()).trim();
            }
        }
        return "";
    }

    private static Map<Path, String> captureDirectoryState(Path root) throws Exception {
        Map<Path, String> state = new HashMap<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                String hash = HexFormat.of().formatHex(md.digest(bytes));
                state.put(root.relativize(path), hash);
            }
        }
        return state;
    }
}
