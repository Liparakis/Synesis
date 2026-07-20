package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Proves CP-R4 outcomes across two isolated JVM profiles. */
final class ProjectRecordSyncProcessTest {
    @Test
    void exchangesInitialDuplicateSuccessorStaleAndConflict() throws Exception {
        Path directory = Files.createTempDirectory("synesis-project-record-process-");
        Path joinProfile = directory.resolve("join-profile");
        Path hostProfile = directory.resolve("host-profile");
        Path joinId = directory.resolve("join.id");
        Path hostId = directory.resolve("host.id");
        Path invitation = directory.resolve("invitation.txt");
        Path marker = directory.resolve("done");
        Path outcomes = directory.resolve("outcomes.txt");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        String project = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        Process join = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                DecisionRecordPeerProcess.class.getName(), "join", joinProfile.toString(), project,
                joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString())
                .redirectErrorStream(true).start();
        Process host = null;
        try {
            waitFor(joinId);
            host = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    DecisionRecordPeerProcess.class.getName(), "host", hostProfile.toString(), project,
                    joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString())
                    .redirectErrorStream(true).start();
            assertTrue(join.waitFor(45, TimeUnit.SECONDS), "join process did not exit: " + output(join));
            assertTrue(host.waitFor(45, TimeUnit.SECONDS), "host process did not exit: " + output(host));
            assertEquals(0, join.exitValue(), output(join));
            assertEquals(0, host.exitValue(), output(host));
            assertEquals("APPLIED,DUPLICATE,APPLIED,REMOTE_STALE,CONFLICT", Files.readString(outcomes));
            assertTrue(Files.exists(hostProfile.resolve("records/conflicts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
            DecisionStore hostStore = new DecisionStore(hostProfile.resolve("records"), UUID.fromString(project));
            assertEquals(2, hostStore.head(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).orElseThrow().revision());
        } finally {
            if (join.isAlive()) join.destroyForcibly();
            if (host != null && host.isAlive()) host.destroyForcibly();
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    private static void waitFor(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(Files.exists(path), "timed out waiting for " + path);
    }

    private static String output(Process process) throws java.io.IOException {
        return new String(process.getInputStream().readAllBytes());
    }
}
