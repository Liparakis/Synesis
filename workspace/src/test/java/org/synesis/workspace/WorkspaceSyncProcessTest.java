package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves CP-W2 generated-launcher host/join and one-shot sync behavior. */
@Timeout(180)
final class WorkspaceSyncProcessTest {
    @Test
    void initialAndDuplicateSyncUseTwoFreshAuthenticatedSessions() throws Exception {
        DemoState state = prepare();
        SyncRun first = sync(state, state.recordId, state.projectId, state.hostNodeId);
        assertEquals(0, first.joinExit);
        assertEquals(0, first.hostExit);
        assertTrue(first.joinOutput.contains("AUTHENTICATED_REMOTE=" + state.hostNodeId));
        assertTrue(first.joinOutput.contains("SYNC_RESULT=APPLIED"));
        assertTrue(Files.exists(state.join.resolve("project.conf")));

        SyncRun duplicate = sync(state, state.recordId, state.projectId, state.hostNodeId);
        assertEquals(0, duplicate.joinExit);
        assertEquals(0, duplicate.hostExit);
        assertTrue(duplicate.joinOutput.contains("SYNC_RESULT=DUPLICATE"));
    }

    @Test
    void wrongHostMalformedInvitationAndProjectMismatchDoNotOverwriteConfig() throws Exception {
        DemoState state = prepare();
        Path wrongJoin = state.join;
        Process wrongHost = start(state.host, "sync", "host");
        HostCapture wrongCapture = captureInvitation(wrongHost);
        Process wrong = start(wrongJoin, "sync", "join", "--project", state.projectId,
                "--record", state.recordId, "--expect-host", "sl1-" + "0".repeat(64), wrongCapture.invitation);
        assertTrue(wrong.waitFor(45, TimeUnit.SECONDS));
        finishHost(wrongHost, wrongCapture.reader);
        assertEquals(10, wrong.exitValue());
        String wrongOutput = output(wrong);
        assertTrue(wrongOutput.contains("ERROR=AUTH_FAILED"), wrongOutput);
        assertFalse(Files.exists(wrongJoin.resolve("project.conf")));

        Path malformed = state.root.resolve("malformed");
        Process invalid = start(malformed, "sync", "join", "--project", state.projectId,
                "--record", state.recordId, "--expect-host", state.hostNodeId, "not-an-invitation");
        assertTrue(invalid.waitFor(20, TimeUnit.SECONDS));
        assertEquals(10, invalid.exitValue());
        assertTrue(output(invalid).contains("ERROR=INVITE_INVALID"));
        assertFalse(Files.exists(malformed.resolve("project.conf")));

        Path mismatchJoin = state.root.resolve("mismatch");
        Process mismatchIdentity = start(mismatchJoin, "identity", "show");
        assertTrue(mismatchIdentity.waitFor(20, TimeUnit.SECONDS));
        String mismatchJoinNode = value(output(mismatchIdentity), "NODE_ID");
        Path mismatchHostProfile = state.root.resolve("mismatch-host");
        CommandResult mismatchProject = run(mismatchHostProfile, "project", "create", "--peer", mismatchJoinNode);
        String mismatchHostNode = value(mismatchProject.stdout, "NODE_ID");
        String mismatchProjectId = value(mismatchProject.stdout, "PROJECT_ID");
        Process configureMismatch = start(mismatchJoin, "project", "create", "--peer", mismatchHostNode);
        assertTrue(configureMismatch.waitFor(20, TimeUnit.SECONDS));
        String before = Files.readString(mismatchJoin.resolve("project.conf"));
        Process mismatchHost = start(mismatchHostProfile, "sync", "host");
        HostCapture mismatchCapture = captureInvitation(mismatchHost);
        Process mismatch = start(mismatchJoin, "sync", "join", "--project", state.projectId,
                "--record", state.recordId, "--expect-host", mismatchHostNode, mismatchCapture.invitation);
        assertTrue(mismatch.waitFor(45, TimeUnit.SECONDS));
        finishHost(mismatchHost, mismatchCapture.reader);
        assertEquals(10, mismatch.exitValue());
        String mismatchOutput = output(mismatch);
        assertTrue(mismatchOutput.contains("ERROR=PROJECT_MISMATCH"), mismatchOutput);
        assertEquals(before, Files.readString(mismatchJoin.resolve("project.conf")));
    }

    @Test
    void missingRecordIsRejectedAndClosingHostBeforeResultIsUnknown() throws Exception {
        DemoState state = prepare();
        UUID missing = UUID.randomUUID();
        SyncRun missingRun = sync(state, missing.toString(), state.projectId, state.hostNodeId);
        assertEquals(10, missingRun.joinExit);
        assertEquals(0, missingRun.hostExit);
        assertTrue(missingRun.joinOutput.contains("SYNC_RESULT=REJECTED"));

        Process host = startAbruptHost(state.host);
        HostCapture capture = captureInvitation(host);
        Process join = start(state.join, "sync", "join", "--project", state.projectId,
                "--record", state.recordId, "--expect-host", state.hostNodeId, capture.invitation);
        BufferedReader joinReader = new BufferedReader(new InputStreamReader(join.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder joinOutput = new StringBuilder();
        String line;
        while ((line = joinReader.readLine()) != null) {
            joinOutput.append(line).append('\n');
            if (line.startsWith("AUTHENTICATED_REMOTE=")) {
                host.destroyForcibly();
                break;
            }
        }
        while ((line = joinReader.readLine()) != null) joinOutput.append(line).append('\n');
        assertTrue(join.waitFor(45, TimeUnit.SECONDS));
        if (host.isAlive()) host.destroyForcibly();
        assertEquals(10, join.exitValue());
        assertTrue(joinOutput.toString().contains("SYNC_RESULT=UNKNOWN"), joinOutput.toString());
    }

    @Test
    void demoFlowAndRobustQueries() throws Exception {
        Path root = Files.createTempDirectory("workspace-demo-flow-");
        Path profileA = root.resolve("profileA");
        Path profileB = root.resolve("profileB");

        // 1. Identity bootstrap
        CommandResult bIdentity = run(profileB, "identity", "show");
        assertEquals(0, bIdentity.exit);
        String bNodeId = value(bIdentity.stdout, "NODE_ID");

        CommandResult aIdentity = run(profileA, "identity", "show");
        assertEquals(0, aIdentity.exit);
        String aNodeId = value(aIdentity.stdout, "NODE_ID");

        // 2. Project create
        CommandResult project = run(profileA, "project", "create", "--peer", bNodeId);
        assertEquals(0, project.exit);
        String projectId = value(project.stdout, "PROJECT_ID");

        // 3. Decision create X
        String digestX = "1111111111111111111111111111111111111111111111111111111111111111";
        CommandResult decX = run(profileA, "decision", "create",
                "--title", "Record X", "--rationale", "Rationale X",
                "--evidence-kind", "text", "--evidence-ref", "refX", "--evidence-sha256", digestX);
        assertEquals(0, decX.exit);
        String recordXId = value(decX.stdout, "RECORD_ID");

        // 4. Decision create Y
        String digestY = "2222222222222222222222222222222222222222222222222222222222222222";
        CommandResult decY = run(profileA, "decision", "create",
                "--title", "Record Y", "--rationale", "Rationale Y",
                "--evidence-kind", "text", "--evidence-ref", "refY", "--evidence-sha256", digestY);
        assertEquals(0, decY.exit);
        String recordYId = value(decY.stdout, "RECORD_ID");

        // 5. Sync X (APPLIED)
        DemoState state = new DemoState(root, profileA, profileB, aNodeId, bNodeId, projectId, recordXId);
        SyncRun firstSync = sync(state, recordXId, projectId, aNodeId);
        assertEquals(0, firstSync.joinExit);
        assertEquals(0, firstSync.hostExit);
        assertTrue(firstSync.joinOutput.contains("SYNC_RESULT=APPLIED"));

        // 6. Matching search/inspect results after APPLIED sync
        CommandResult searchA = run(profileA, "decision", "search");
        assertEquals(0, searchA.exit);
        assertTrue(searchA.stdout.contains("RECORD_ID=" + recordXId));
        assertTrue(searchA.stdout.contains("RECORD_ID=" + recordYId));

        CommandResult searchB = run(profileB, "decision", "search");
        assertEquals(0, searchB.exit);
        assertTrue(searchB.stdout.contains("RECORD_ID=" + recordXId));
        assertFalse(searchB.stdout.contains("RECORD_ID=" + recordYId)); // Y not synced yet

        CommandResult inspectXA = run(profileA, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectXA.exit);
        CommandResult inspectXB = run(profileB, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectXB.exit);
        assertEquals(inspectXA.stdout.trim(), inspectXB.stdout.trim()); // byte-stable match

        // 7. DUPLICATE sync preserves identical output
        SyncRun secondSync = sync(state, recordXId, projectId, aNodeId);
        assertEquals(0, secondSync.joinExit);
        assertEquals(0, secondSync.hostExit);
        assertTrue(secondSync.joinOutput.contains("SYNC_RESULT=DUPLICATE"));

        CommandResult inspectXBAfterDup = run(profileB, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectXBAfterDup.exit);
        assertEquals(inspectXA.stdout.trim(), inspectXBAfterDup.stdout.trim());

        // 8. Empty search
        CommandResult emptySearchB = run(profileB, "decision", "search", "--text", "nonexistent");
        assertEquals(0, emptySearchB.exit);
        assertTrue(emptySearchB.stdout.contains("RESULTS=0"));

        // 9. Malformed filters
        CommandResult malformedStatus = run(profileA, "decision", "search", "--status", "INVALID_STATUS");
        assertEquals(10, malformedStatus.exit);
        assertTrue(malformedStatus.stdout.contains("ERROR=USAGE"));

        CommandResult malformedLimit = run(profileA, "decision", "search", "--limit", "-5");
        assertEquals(10, malformedLimit.exit);
        assertTrue(malformedLimit.stdout.contains("ERROR=USAGE"));

        // 10. Missing record inspect
        CommandResult missingInspect = run(profileB, "decision", "inspect", "--record", recordYId);
        assertEquals(10, missingInspect.exit);
        assertTrue(missingInspect.stdout.contains("ERROR=RECORD_NOT_FOUND"));

        // 11. Corruption
        Path sdrYFile = profileA.resolve("records/decisions/" + recordYId + "/1.sdr");
        Files.writeString(sdrYFile, "corrupted bytes here");

        // Search fails closed because one record is corrupt
        CommandResult searchCorrupt = run(profileA, "decision", "search");
        assertEquals(10, searchCorrupt.exit);
        assertTrue(searchCorrupt.stdout.contains("ERROR=LOCAL_STATE_INVALID"));

        // Inspect Y fails
        CommandResult inspectYCorrupt = run(profileA, "decision", "inspect", "--record", recordYId);
        assertEquals(10, inspectYCorrupt.exit);
        assertTrue(inspectYCorrupt.stdout.contains("ERROR=LOCAL_STATE_INVALID"));

        // Inspect X still succeeds (isolation of inspection!)
        CommandResult inspectXAStillOk = run(profileA, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectXAStillOk.exit);
        assertEquals(inspectXA.stdout.trim(), inspectXAStillOk.stdout.trim());

        // 12. Conflicts & Stale revisions
        // Divergent files in conflicts/ are ignored by search/inspect
        Path conflictPath = profileA.resolve("records/conflicts/" + recordXId + "/1-divergent.sdr");
        Files.createDirectories(conflictPath.getParent());
        Files.writeString(conflictPath, "divergent bytes");

        CommandResult inspectWithConflict = run(profileA, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectWithConflict.exit); // conflict ignored

        // Temp files are ignored
        Path tempPath = profileA.resolve("records/decisions/" + recordXId + "/1.sdr.tmp-1234");
        Files.writeString(tempPath, "temp bytes");

        CommandResult inspectWithTemp = run(profileA, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectWithTemp.exit); // temp file ignored

        // Stale revision: Z is created on B, then made stale by deleting its revisions while keeping head
        CommandResult decZ = run(profileB, "decision", "create",
                "--title", "Record Z", "--rationale", "Rationale Z",
                "--evidence-kind", "text", "--evidence-ref", "refZ", "--evidence-sha256", digestX);
        assertEquals(0, decZ.exit);
        String recordZId = value(decZ.stdout, "RECORD_ID");

        Path sdrZDir = profileB.resolve("records/decisions/" + recordZId);
        try (var stream = Files.list(sdrZDir)) {
            for (Path p : stream.toList()) Files.delete(p);
        }
        Files.delete(sdrZDir);

        // Inspect Z fails
        CommandResult inspectZStale = run(profileB, "decision", "inspect", "--record", recordZId);
        assertEquals(10, inspectZStale.exit);
        assertTrue(inspectZStale.stdout.contains("ERROR=LOCAL_STATE_INVALID"));

        // Search fails
        CommandResult searchStale = run(profileB, "decision", "search");
        assertEquals(10, searchStale.exit);
        assertTrue(searchStale.stdout.contains("ERROR=LOCAL_STATE_INVALID"));

        // Inspect X still succeeds!
        CommandResult inspectXStaleStore = run(profileB, "decision", "inspect", "--record", recordXId);
        assertEquals(0, inspectXStaleStore.exit);
    }

    private static void writeHeadFile(Path path, long revision, byte[] digest) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
            output.writeInt(0x53444831); // HEAD_MAGIC
            output.writeByte(1); // HEAD_VERSION
            output.writeLong(revision);
            output.write(digest);
        }
        Files.write(path, bytes.toByteArray());
    }

    private static DemoState prepare() throws Exception {
        Path root = Files.createTempDirectory("workspace-sync-");
        Path host = root.resolve("host");
        Path join = root.resolve("join");
        String joinNode = value(run(join, "identity", "show").stdout, "NODE_ID");
        CommandResult project = run(host, "project", "create", "--peer", joinNode);
        String hostNode = value(project.stdout, "NODE_ID");
        String projectId = value(project.stdout, "PROJECT_ID");
        String digest = "0".repeat(64);
        CommandResult decision = run(host, "decision", "create", "--title", "demo",
                "--rationale", "one-shot", "--evidence-kind", "text", "--evidence-ref", "demo",
                "--evidence-sha256", digest);
        return new DemoState(root, host, join, hostNode, joinNode, projectId, value(decision.stdout, "RECORD_ID"));
    }

    private static SyncRun sync(DemoState state, String recordId, String projectId, String expectedHost)
            throws Exception {
        Process host = start(state.host, "sync", "host");
        HostCapture capture = captureInvitation(host);
        Process join = start(state.join, "sync", "join", "--project", projectId, "--record", recordId,
                "--expect-host", expectedHost, capture.invitation);
        assertTrue(join.waitFor(60, TimeUnit.SECONDS));
        int joinExit = join.exitValue();
        String joinOutput = output(join);
        int hostExit = finishHost(host, capture.reader);
        return new SyncRun(joinExit, hostExit, joinOutput);
    }

    private static Process start(Path profile, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("cmd.exe");
        command.add("/c");
        command.add(launcher().toString());
        command.add("--profile");
        command.add(profile.toString());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static Process startAbruptHost(Path profile) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        return new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("java.class.path"), AbruptHostProcess.class.getName(),
                profile.toString()).redirectErrorStream(true).start();
    }

    private static CommandResult run(Path profile, String... arguments) throws Exception {
        Process process = start(profile, arguments);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new CommandResult(process.exitValue(), output(process));
    }

    private static HostCapture captureInvitation(Process host) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("INVITATION=")) return new HostCapture(line.substring("INVITATION=".length()), reader);
        }
        throw new AssertionError("host emitted no invitation: " + output(host));
    }

    private static int finishHost(Process host, BufferedReader reader) throws Exception {
        if (!host.waitFor(20, TimeUnit.SECONDS)) {
            host.destroyForcibly();
            assertTrue(host.waitFor(20, TimeUnit.SECONDS));
        }
        return host.exitValue();
    }

    private static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Path launcher() {
        Path local = Path.of("build", "install", "synesis-workspace", "bin", "synesis-workspace.bat");
        return (Files.exists(local) ? local : Path.of("workspace").resolve(local)).toAbsolutePath();
    }

    private static String value(String output, String key) {
        return output.lines().filter(line -> line.startsWith(key + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key + " in " + output))
                .substring(key.length() + 1);
    }

    private record DemoState(Path root, Path host, Path join, String hostNodeId, String joinNodeId,
            String projectId, String recordId) { }
    private record CommandResult(int exit, String stdout) { }
    private record SyncRun(int joinExit, int hostExit, String joinOutput) { }
    private record HostCapture(String invitation, BufferedReader reader) { }
}
