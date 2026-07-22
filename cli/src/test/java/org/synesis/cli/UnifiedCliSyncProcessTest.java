package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Preserves valid workspace process coverage through the unified launcher. */
@Timeout(240)
final class UnifiedCliSyncProcessTest {
    @Test
    void initialAndDuplicateSyncUseTwoFreshAuthenticatedSessions() throws Exception {
        ProjectState state = prepare();

        SyncRun first = sync(state, state.recordId());
        assertEquals(0, first.joinExit(), first.joinOutput());
        assertEquals(0, first.hostExit(), first.joinOutput());
        assertTrue(first.joinOutput().contains("AUTHENTICATED_REMOTE=" + state.hostNodeId()));
        assertTrue(first.joinOutput().contains("SYNC_RESULT=APPLIED"));
        assertTrue(Files.exists(state.join().resolve(".synesis/local/profile/project.conf")));

        SyncRun duplicate = sync(state, state.recordId());
        assertEquals(0, duplicate.joinExit());
        assertEquals(0, duplicate.hostExit());
        assertTrue(duplicate.joinOutput().contains("SYNC_RESULT=DUPLICATE"));
    }

    @Test
    void projectReconciliationAndCheckActionWorkflow() throws Exception {
        ProjectState state = prepareForReconciliation();
        CommandResult constraint = run(state.host(), "constraint", "create", "--project", state.host().toString(),
                "--title", "Lock protocol wire format", "--rationale",
                "Protocol formats are frozen during compatibility testing.", "--scope", "src/protocol/**",
                "--effect", "block");
        assertEquals(0, constraint.exit(), constraint.output());

        Process host = start(state.host(), "sync", "host", "--project", state.host().toString());
        HostCapture capture = captureInvitation(host);
        Process join = start(state.join(), "sync", "join", "--project", state.join().toString(),
                "--expect-host", state.hostNodeId(), capture.invitation());
        assertTrue(join.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, join.exitValue());
        assertEquals(0, finishHost(host));
        String joinOutput = output(join);
        assertTrue(joinOutput.contains("SYNC_RESULT=SUCCESS"), joinOutput);
        assertTrue(joinOutput.contains("RECONCILED_COUNT="), joinOutput);

        CommandResult blocked = run(state.join(), "check-action", "--project", state.join().toString(),
                "--scope", "src/protocol/RecordMessage.java", "--action", "Modify wire format");
        assertEquals(10, blocked.exit());
        assertTrue(blocked.output().contains("ACTION_RESULT=BLOCKED"), blocked.output());
        assertTrue(blocked.output().contains("CONSTRAINT_TITLE=Lock protocol wire format"), blocked.output());

        CommandResult allowed = run(state.join(), "check-action", "--project", state.join().toString(),
                "--scope", "src/ui/Component.java", "--action", "Update padding");
        assertEquals(0, allowed.exit());
        assertTrue(allowed.output().contains("ACTION_RESULT=ALLOWED"), allowed.output());
    }

    @Test
    void wrongHostMalformedInvitationAndProjectMismatchDoNotOverwriteConfig() throws Exception {
        ProjectState state = prepare();
        Process wrongHost = start(state.host(), "sync", "host", "--project", state.host().toString(), "--record",
                state.recordId());
        HostCapture wrongCapture = captureInvitation(wrongHost);
        CommandResult wrong = run(state.join(), "sync", "join", "--project", state.join().toString(), "--record",
                state.recordId(), "--expect-host", "sl1-" + "0".repeat(64), wrongCapture.invitation());
        stopHost(wrongHost);
        assertEquals(10, wrong.exit());
        assertTrue(wrong.output().contains("ERROR=AUTH_FAILED"), wrong.output());
        assertFalse(Files.exists(state.join().resolve(".synesis/local/profile/project.conf")));

        Path malformed = initProject(state.root().resolve("malformed"));
        CommandResult invalid = run(malformed, "sync", "join", "--project", malformed.toString(),
                "synesis://join/SYN1-invalidbase64bytes");
        assertEquals(10, invalid.exit());
        assertTrue(invalid.output().contains("ERROR=INVITE_INVALID"), invalid.output());
        assertFalse(Files.exists(malformed.resolve(".synesis/local/profile/project.conf")));

        Path mismatchJoin = initProject(state.root().resolve("mismatch-join"));
        Path mismatchHost = initProject(state.root().resolve("mismatch-host"));
        String mismatchJoinNode = value(run(mismatchJoin, "identity", "show", "--project", mismatchJoin.toString()).output(),
                "NODE_ID");
        CommandResult mismatchProject = run(mismatchHost, "project", "create", "--project", mismatchHost.toString(),
                "--peer", mismatchJoinNode);
        String mismatchHostNode = value(mismatchProject.output(), "NODE_ID");
        run(mismatchJoin, "project", "create", "--project", mismatchJoin.toString(), "--peer", mismatchHostNode);
        String before = Files.readString(mismatchJoin.resolve(".synesis/local/profile/project.conf"));

        Process mismatchHostProcess = start(mismatchHost, "sync", "host", "--project", mismatchHost.toString());
        HostCapture mismatchCapture = captureInvitation(mismatchHostProcess);
        CommandResult mismatch = run(mismatchJoin, "sync", "join", "--project", mismatchJoin.toString(),
                "--expect-host", mismatchHostNode, mismatchCapture.invitation());
        stopHost(mismatchHostProcess);
        assertEquals(10, mismatch.exit());
        assertTrue(mismatch.output().contains("ERROR=PROJECT_MISMATCH"), mismatch.output());
        assertEquals(before, Files.readString(mismatchJoin.resolve(".synesis/local/profile/project.conf")));
    }

    @Test
    void missingRecordAndAbruptHostFailureRemainClassifiedSafely() throws Exception {
        ProjectState state = prepare();
        CommandResult missingHost = run(state.host(), "sync", "host", "--project", state.host().toString(), "--record",
                UUID.randomUUID().toString());
        assertEquals(10, missingHost.exit());
        assertTrue(missingHost.output().contains("ERROR=RECORD_NOT_FOUND"), missingHost.output());

        Process host = start(state.host(), "sync", "host", "--project", state.host().toString(), "--record",
                state.recordId());
        HostCapture capture = captureInvitation(host);
        stopHost(host);
        CommandResult join = run(state.join(), "sync", "join", "--project", state.join().toString(), "--record",
                state.recordId(), "--expect-host", state.hostNodeId(), capture.invitation());
        assertEquals(10, join.exit(), join.output());
        assertTrue(join.output().contains("ERROR=TRANSPORT_FAILED"), join.output());
    }

    @Test
    void guidedInvitationFlowKeepsValidationAndReplayBoundaries() throws Exception {
        ProjectState state = prepare();
        Process host = start(state.host(), "sync", "host", "--project", state.host().toString(), "--record",
                state.recordId());
        HostCapture capture = captureInvitation(host);
        String invitation = capture.invitation();
        assertTrue(invitation.contains("project=" + state.projectId()));
        assertTrue(invitation.contains("record=" + state.recordId()));
        assertTrue(invitation.contains("host=" + state.hostNodeId()));
        stopHost(host);

        CommandResult malformed = run(state.join(), "sync", "join", "--project", state.join().toString(),
                "synesis://join/SYN1-invalidbase64bytes");
        assertEquals(10, malformed.exit());
        assertTrue(malformed.output().contains("ERROR=INVITE_INVALID"), malformed.output());

        String tampered = invitation.replace("host=" + state.hostNodeId(),
                "host=sl1-" + "0".repeat(64));
        CommandResult tamperedResult = run(state.join(), "sync", "join", "--project", state.join().toString(), tampered);
        assertEquals(10, tamperedResult.exit());
        assertTrue(tamperedResult.output().contains("ERROR=AUTH_FAILED"), tamperedResult.output());

        SyncRun applied = sync(state, state.recordId());
        assertEquals(0, applied.joinExit());
        assertEquals(0, applied.hostExit());
        SyncRun duplicate = sync(state, state.recordId());
        assertEquals(0, duplicate.joinExit());
        assertEquals(0, duplicate.hostExit());
        assertTrue(duplicate.joinOutput().contains("SYNC_RESULT=DUPLICATE"));
    }

    private static ProjectState prepare() throws Exception {
        Path root = Files.createTempDirectory("synesis-unified-sync-");
        Path host = initProject(root.resolve("host"));
        Path join = initProject(root.resolve("join"));
        String joinNode = value(run(join, "identity", "show", "--project", join.toString()).output(), "NODE_ID");
        CommandResult project = run(host, "project", "create", "--project", host.toString(), "--peer", joinNode);
        assertEquals(0, project.exit());
        String hostNode = value(project.output(), "NODE_ID");
        String projectId = value(project.output(), "PROJECT_ID");
        CommandResult constraint = run(host, "constraint", "create", "--project", host.toString(), "--title", "demo",
                "--rationale", "one-shot", "--scope", "src/protocol/**", "--effect", "block");
        assertEquals(0, constraint.exit(), constraint.output());
        return new ProjectState(root, host, join, hostNode, projectId, value(constraint.output(), "RECORD_ID"));
    }

    private static ProjectState prepareForReconciliation() throws Exception {
        Path root = Files.createTempDirectory("synesis-unified-reconcile-");
        Path host = initProject(root.resolve("host"));
        Path join = initProject(root.resolve("join"));
        String joinNode = value(run(join, "identity", "show", "--project", join.toString()).output(), "NODE_ID");
        CommandResult project = run(host, "project", "create", "--project", host.toString(), "--peer", joinNode);
        assertEquals(0, project.exit(), project.output());
        return new ProjectState(root, host, join, value(project.output(), "NODE_ID"),
                value(project.output(), "PROJECT_ID"), null);
    }

    private static Path initProject(Path project) throws Exception {
        Files.createDirectories(project);
        CommandResult result = run(project, "init", "--project", project.toString());
        assertEquals(0, result.exit(), result.output());
        return project;
    }

    private static SyncRun sync(ProjectState state, String record) throws Exception {
        Process host = start(state.host(), "sync", "host", "--project", state.host().toString(), "--record", record);
        HostCapture capture = captureInvitation(host);
        Process join = start(state.join(), "sync", "join", "--project", state.join().toString(), "--record", record,
                "--expect-host", state.hostNodeId(), capture.invitation());
        assertTrue(join.waitFor(60, TimeUnit.SECONDS));
        String joinOutput = output(join);
        int hostExit = finishHost(host);
        return new SyncRun(join.exitValue(), hostExit, joinOutput);
    }

    private static Process start(Path project, String... arguments) throws IOException {
        return DistributionLauncherTest.start(DistributionLauncherTest.launcher(), project, arguments);
    }

    private static CommandResult run(Path project, String... arguments) throws Exception {
        Process process = start(project, arguments);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return new CommandResult(process.exitValue(), output(process));
    }

    private static HostCapture captureInvitation(Process host) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append('\n');
            if (line.startsWith("INVITATION=")) return new HostCapture(line.substring("INVITATION=".length()), reader);
        }
        throw new AssertionError("host emitted no invitation: " + output);
    }

    private static int finishHost(Process host) throws Exception {
        if (!host.waitFor(30, TimeUnit.SECONDS)) {
            stopHost(host);
        } else {
            host.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        }
        return host.exitValue();
    }

    private static void stopHost(Process host) throws Exception {
        host.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        if (host.isAlive()) host.destroyForcibly();
        if (host.isAlive()) assertTrue(host.waitFor(10, TimeUnit.SECONDS));
    }

    private static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String value(String output, String key) {
        return output.lines().filter(line -> line.startsWith(key + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key + " in " + output))
                .substring(key.length() + 1);
    }

    private record ProjectState(Path root, Path host, Path join, String hostNodeId, String projectId, String recordId) { }

    private record CommandResult(int exit, String output) { }

    private record SyncRun(int joinExit, int hostExit, String joinOutput) { }

    private record HostCapture(String invitation, BufferedReader reader) { }
}
