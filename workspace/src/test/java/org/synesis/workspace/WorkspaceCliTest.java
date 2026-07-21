package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.projectrecord.DecisionStore;
import org.synesis.projectrecord.ProjectConfig;

/** Focused CP-W1 tests for isolated bootstrap and local signed decisions. */
final class WorkspaceCliTest {
    @Test
    void identitiesAreStableAndProfilesAreIsolated() throws Exception {
        Path root = Files.createTempDirectory("workspace-identities-");
        Path first = root.resolve("a");
        Path second = root.resolve("b");
        Invocation a1 = invoke("--profile", first.toString(), "identity", "show");
        Invocation a2 = invoke("--profile", first.toString(), "identity", "show");
        Invocation b = invoke("--profile", second.toString(), "identity", "show");

        assertEquals(0, a1.code);
        assertEquals(0, a2.code);
        assertEquals(0, b.code);
        assertEquals(value(a1.stdout, "NODE_ID"), value(a2.stdout, "NODE_ID"));
        assertNotEquals(value(a1.stdout, "NODE_ID"), value(b.stdout, "NODE_ID"));
        assertTrue(Files.exists(first.resolve("link/identity.bin")));
        assertTrue(Files.exists(second.resolve("link/identity.bin")));
        assertFalse(a1.stdout.contains(root.toString()));
    }

    @Test
    void projectCreationIsAtomicAndRefusesOverwriteOrMismatch() throws Exception {
        Path root = Files.createTempDirectory("workspace-project-");
        Path profile = root.resolve("a");
        String peer = value(invoke("--profile", root.resolve("b").toString(), "identity", "show").stdout, "NODE_ID");
        Invocation created = invoke("--profile", profile.toString(), "project", "create", "--peer", peer);
        assertEquals(0, created.code);
        UUID project = UUID.fromString(value(created.stdout, "PROJECT_ID"));
        ProjectConfig config = ProjectConfig.load(profile.resolve("project.conf"));
        assertEquals(project, config.projectId());
        assertEquals(java.util.Set.of(peer), config.peerNodeIds());

        Invocation duplicate = invoke("--profile", profile.toString(), "project", "create", "--peer", peer);
        assertEquals(10, duplicate.code);
        assertEquals("ERROR=PROJECT_EXISTS", duplicate.stderr.trim());

        String otherPeer = value(invoke("--profile", root.resolve("c").toString(), "identity", "show").stdout, "NODE_ID");
        Invocation mismatch = invoke("--profile", profile.toString(), "project", "create", "--peer", otherPeer);
        assertEquals(10, mismatch.code);
        assertEquals("ERROR=PROJECT_MISMATCH", mismatch.stderr.trim());
        assertEquals(project, ProjectConfig.load(profile.resolve("project.conf")).projectId());
    }

    @Test
    void decisionCreationPersistsVerifiedRevisionAndSurvivesRestart() throws Exception {
        Path root = Files.createTempDirectory("workspace-decision-");
        Path profile = root.resolve("a");
        String peer = value(invoke("--profile", root.resolve("b").toString(), "identity", "show").stdout, "NODE_ID");
        Invocation project = invoke("--profile", profile.toString(), "project", "create", "--peer", peer);
        String projectId = value(project.stdout, "PROJECT_ID");
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Invocation decision = invoke("--profile", profile.toString(), "decision", "create",
                "--title", "A bounded decision", "--rationale", "A signed local record",
                "--evidence-kind", "text", "--evidence-ref", "demo-note", "--evidence-sha256", digest);
        assertEquals(0, decision.code);
        assertEquals(projectId, value(decision.stdout, "PROJECT_ID"));
        assertEquals("1", value(decision.stdout, "REVISION"));
        assertEquals("true", value(decision.stdout, "SIGNATURE_VALID"));
        UUID recordId = UUID.fromString(value(decision.stdout, "RECORD_ID"));
        DecisionStore first = new DecisionStore(profile.resolve("records"), UUID.fromString(projectId));
        var record = first.head(recordId).orElseThrow();
        assertEquals(1, record.revision());
        assertEquals(value(decision.stdout, "DIGEST"), record.digestHex());
        DecisionStore restarted = new DecisionStore(profile.resolve("records"), UUID.fromString(projectId));
        assertEquals(record.digestHex(), restarted.head(recordId).orElseThrow().digestHex());
    }

    @Test
    void boundsAndSensitiveOutputFailSafely() throws Exception {
        Path profile = Files.createTempDirectory("workspace-bounds-").resolve("a");
        String tooLong = "x".repeat(513);
        Invocation invalid = invoke("--profile", profile.toString(), "decision", "create",
                "--title", tooLong, "--rationale", "r", "--evidence-kind", "text",
                "--evidence-ref", "ref", "--evidence-sha256", "0".repeat(64));
        assertEquals(10, invalid.code);
        assertEquals("ERROR=RECORD_INVALID", invalid.stderr.trim());
        assertFalse(invalid.stderr.contains(profile.toString()));

        Invocation unknown = invoke("--profile", profile.toString(), "project", "create", "--unknown", "x");
        assertEquals(10, unknown.code);
        assertEquals("ERROR=USAGE", unknown.stderr.trim());
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            return new Invocation(WorkspaceCli.run(arguments), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    private static String value(String output, String key) {
        return output.lines().filter(line -> line.startsWith(key + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key + " in " + output))
                .substring(key.length() + 1);
    }

    private record Invocation(int code, String stdout, String stderr) { }
}
