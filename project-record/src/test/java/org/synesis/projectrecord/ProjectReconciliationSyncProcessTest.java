package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Tests project-wide reconciliation protocol PRP1 across two JVMs. */
final class ProjectReconciliationSyncProcessTest {

    @Test
    void normalSyncConvergesSuccessfully() throws Exception {
        Path directory = Files.createTempDirectory("synesis-project-reconcile-normal-");
        try {
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
            String project = "cccccccc-cccc-cccc-cccc-cccccccccccc";

            Process join = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    ProjectReconciliationPeerProcess.class.getName(), "join", joinProfile.toString(), project,
                    joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "normal")
                    .redirectErrorStream(true).start();

            Process host = null;
            try {
                waitFor(joinId);
                host = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                        ProjectReconciliationPeerProcess.class.getName(), "host", hostProfile.toString(), project,
                        joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "normal")
                        .redirectErrorStream(true).start();

                assertTrue(join.waitFor(45, TimeUnit.SECONDS), "join process did not exit");
                assertTrue(host.waitFor(45, TimeUnit.SECONDS), "host process did not exit");
                assertEquals(0, join.exitValue());
                assertEquals(0, host.exitValue());

                // Assert Joiner output: success, reconciledCount, addedLocal, addedRemote, corruptLocalCount
                String output = Files.readString(outcomes);
                // "true,5,2,3,0" -> success=true, 5 reconciled revisions, 2 addedLocal (Record B rev 1 & 2), 3 addedRemote (Record A rev 1 & 2, Record C rev 1), 0 corrupt
                assertEquals("true,5,2,3,0", output);

                // Verify both sides have matching heads
                DecisionStore hostStore = new DecisionStore(hostProfile.resolve("records"), UUID.fromString(project));
                DecisionStore joinStore = new DecisionStore(joinProfile.resolve("records"), UUID.fromString(project));

                assertEquals(2, hostStore.head(UUID.fromString("11111111-1111-1111-1111-111111111111")).orElseThrow().revision());
                assertEquals(2, joinStore.head(UUID.fromString("11111111-1111-1111-1111-111111111111")).orElseThrow().revision());

                assertEquals(2, hostStore.head(UUID.fromString("22222222-2222-2222-2222-222222222222")).orElseThrow().revision());
                assertEquals(2, joinStore.head(UUID.fromString("22222222-2222-2222-2222-222222222222")).orElseThrow().revision());

                // Local-only Record C was preserved and synchronized to remote without deletion
                assertEquals(1, hostStore.head(UUID.fromString("33333333-3333-3333-3333-333333333333")).orElseThrow().revision());
                assertEquals(1, joinStore.head(UUID.fromString("33333333-3333-3333-3333-333333333333")).orElseThrow().revision());

            } finally {
                if (join.isAlive()) join.destroyForcibly();
                if (host != null && host.isAlive()) host.destroyForcibly();
            }
        } finally {
            cleanup(directory);
        }
    }

    @Test
    void divergentConflictQuarantinesAndPreventsSuccess() throws Exception {
        Path directory = Files.createTempDirectory("synesis-project-reconcile-conflict-");
        try {
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
            String project = "cccccccc-cccc-cccc-cccc-cccccccccccc";

            Process join = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    ProjectReconciliationPeerProcess.class.getName(), "join", joinProfile.toString(), project,
                    joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "conflict")
                    .redirectErrorStream(true).start();

            Process host = null;
            try {
                waitFor(joinId);
                host = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                        ProjectReconciliationPeerProcess.class.getName(), "host", hostProfile.toString(), project,
                        joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "conflict")
                        .redirectErrorStream(true).start();

                assertTrue(join.waitFor(45, TimeUnit.SECONDS), "join process did not exit");
                assertTrue(host.waitFor(45, TimeUnit.SECONDS), "host process did not exit");
                assertEquals(0, join.exitValue());
                assertEquals(0, host.exitValue());

                // Outcomes: success=false, reconciledCount=1 (RECORD_A conflict), addedLocal=0, addedRemote=0, corruptLocalCount=0
                String output = Files.readString(outcomes);
                assertEquals("false,1,0,0,0", output);

                // Both sides must have quarantined the conflicting revision
                assertTrue(Files.exists(hostProfile.resolve("records/conflicts/11111111-1111-1111-1111-111111111111")));
                assertTrue(Files.exists(joinProfile.resolve("records/conflicts/11111111-1111-1111-1111-111111111111")));

            } finally {
                if (join.isAlive()) join.destroyForcibly();
                if (host != null && host.isAlive()) host.destroyForcibly();
            }
        } finally {
            cleanup(directory);
        }
    }

    @Test
    void corruptLocalRecordPreventsSuccess() throws Exception {
        Path directory = Files.createTempDirectory("synesis-project-reconcile-corrupt-");
        try {
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
            String project = "cccccccc-cccc-cccc-cccc-cccccccccccc";

            Process join = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    ProjectReconciliationPeerProcess.class.getName(), "join", joinProfile.toString(), project,
                    joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "corrupt")
                    .redirectErrorStream(true).start();

            Process host = null;
            try {
                waitFor(joinId);
                host = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                        ProjectReconciliationPeerProcess.class.getName(), "host", hostProfile.toString(), project,
                        joinId.toString(), hostId.toString(), invitation.toString(), marker.toString(), outcomes.toString(), "corrupt")
                        .redirectErrorStream(true).start();

                assertTrue(join.waitFor(45, TimeUnit.SECONDS), "join process did not exit");
                assertTrue(host.waitFor(45, TimeUnit.SECONDS), "host process did not exit");
                assertEquals(0, join.exitValue());
                assertEquals(0, host.exitValue());

                // Outcomes: success=false, reconciledCount=0 (aborted/corrupt), addedLocal=0, addedRemote=0, corruptLocalCount=1
                String output = Files.readString(outcomes);
                assertTrue(output.startsWith("false,"), "Expected success=false due to corruption. Got: " + output);
                assertTrue(output.endsWith(",1"), "Expected corrupt count = 1. Got: " + output);

            } finally {
                if (join.isAlive()) join.destroyForcibly();
                if (host != null && host.isAlive()) host.destroyForcibly();
            }
        } finally {
            cleanup(directory);
        }
    }

    private static void waitFor(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(Files.exists(path), "timed out waiting for " + path);
    }

    private static void cleanup(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
            });
        } catch (java.io.IOException ignored) { }
    }
}
