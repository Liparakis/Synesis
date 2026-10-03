package org.synesis.workspace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectInitResult;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.test.TestGit;

/**
 * Proves portable identity material across two process-isolated local replicas.
 */
final class ProjectReplicaProcessTest {

    @Test
    @Timeout(60)
    void processIsolatedReplicasPreserveIdentityAcrossPathsAndGitHeads() throws Exception {
        Path root = Files.createTempDirectory("synesis-replica-process-");
        try {
            Path source = Files.createDirectories(root.resolve("source"));
            TestGit.run(source, "init");
            ProjectApplicationService service = new ProjectApplicationService();
            ProjectInitResult original = service.init(source, false);

            Path replica = Files.createDirectories(root.resolve("replica"));
            Files.writeString(replica.resolve("replica.txt"), "different Git content\n", StandardCharsets.UTF_8);
            TestGit.run(replica, "init");
            TestGit.run(replica, "add", ".");
            TestGit.run(replica, "commit", "-m", "replica content");
            service.initReplica(replica, original.location().identity(), false);

            Map<String, String> sourceFacts = probe(source);
            Map<String, String> replicaFacts = probe(replica);
            assertEquals(sourceFacts.get("PROJECT_ID"), replicaFacts.get("PROJECT_ID"));
            assertEquals(sourceFacts.get("CREATED_AT"), replicaFacts.get("CREATED_AT"));
            assertNotEquals(sourceFacts.get("NODE_ID"), replicaFacts.get("NODE_ID"));
            assertNotEquals(sourceFacts.get("ROOT"), replicaFacts.get("ROOT"));
            assertNotEquals(TestGit.output(source, "rev-parse", "HEAD").trim(), TestGit.output(replica, "rev-parse", "HEAD").trim());

            Path independent = Files.createDirectories(root.resolve("independent"));
            service.init(independent, false);
            assertNotEquals(original.location().projectId(), service.locate(independent).projectId());
        } finally {
            delete(root);
        }
    }

    private static Map<String, String> probe(Path root) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), ProjectReplicaProcessTest.Probe.class.getName(), root.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output.lines().filter(line -> line.contains("=")).map(line -> line.split("=", 2)).collect(Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1]));
    }

    private static void delete(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.setAttribute(path, "dos:readonly", false);
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /**
     * Child process probe used to avoid sharing the parent service instance.
     */
    public static final class Probe {

        private Probe() {
        }

        /**
         * Reads one replica through the ordinary project service.
         *
         * @param arguments one project root
         * @throws Exception when the replica cannot be read
         */
        public static void main(String[] arguments) throws Exception {
            ProjectLocation location = new ProjectApplicationService().locate(Path.of(arguments[0]));
            System.out.println("PROJECT_ID=" + location.projectId());
            System.out.println("CREATED_AT=" + location.createdAt());
            System.out.println("NODE_ID=" + new org.synesis.link.identity.IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity().nodeId());
            System.out.println("ROOT=" + location.root());
        }
    }
}
