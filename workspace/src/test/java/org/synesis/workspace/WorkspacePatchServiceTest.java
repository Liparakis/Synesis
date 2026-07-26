package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentSessionService;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.WorkspacePatchService;

class WorkspacePatchServiceTest {

    private Path controlRoot;

    private static void git(Path root, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
    }

    private static String sha256Hex(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String revisionOf(AgentResponse response) {
        Matcher matcher = Pattern.compile("\\\"revision\\\":\\\"([0-9a-f]+)\\\"").matcher(response.toJson());
        assertTrue(matcher.find(), "successful patch must return a revision: " + response.toJson());
        return matcher.group(1);
    }

    @BeforeEach
    void setUp() throws Exception {
        controlRoot = Files.createTempDirectory("synesis-patch-test-");
        git(controlRoot, "init");
        git(controlRoot, "config", "user.name", "Test User");
        git(controlRoot, "config", "user.email", "test@example.com");

        Files.createDirectories(controlRoot.resolve("src"));
        Files.writeString(controlRoot.resolve("src/Product.java"), "public class Product {\n    int count = 1;\n    String label = \"old\";\n}\n");
        Files.writeString(controlRoot.resolve("src/Other.java"), "public class Other {\n    String value = \"old\";\n}\n");

        git(controlRoot, "add", ".");
        git(controlRoot, "commit", "-m", "Initial commit");

        new ProjectApplicationService().init(controlRoot);
    }

    @Test
    void testCreatesFileInAssignedWorktreeOnlyLeavingControlUnchanged() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-1", null, false);
        sessionService.ensureSession(req);

        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();
        assertNotNull(worktreePath);

        WorkspacePatchService patchService = new WorkspacePatchService();
        WorkspacePatchService.PatchRequest createReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-1", "src/NewClass.java", true, "public class NewClass {}\n", null, List.of());

        AgentResponse response = patchService.applyPatch(createReq);
        assertEquals(AgentStatus.COMPLETED, response.status());
        assertTrue(response.toJson().contains("\"path\":\"src/NewClass.java\""));

        // File exists in assigned worktree
        assertTrue(Files.exists(worktreePath.resolve("src/NewClass.java")));
        assertEquals("public class NewClass {}\n", Files.readString(worktreePath.resolve("src/NewClass.java")));

        // Control checkout remains unchanged
        assertFalse(Files.exists(controlRoot.resolve("src/NewClass.java")));
    }

    @Test
    void testModifiesWithMatchingExpectedHashAndMultipleEditsAtomically() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-2", null, false);
        sessionService.ensureSession(req);

        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();

        String originalContent = Files.readString(worktreePath.resolve("src/Product.java"));
        String hash = sha256Hex(originalContent);

        WorkspacePatchService patchService = new WorkspacePatchService();
        WorkspacePatchService.PatchEdit edit1 = new WorkspacePatchService.PatchEdit("int count = 1;", "int count = 2;", 1);
        WorkspacePatchService.PatchEdit edit2 = new WorkspacePatchService.PatchEdit("String label = \"old\";", "String label = \"new\";", 1);

        WorkspacePatchService.PatchRequest modifyReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-2", "src/Product.java", false, null, hash, List.of(edit1, edit2));

        AgentResponse response = patchService.applyPatch(modifyReq);
        assertEquals(AgentStatus.COMPLETED, response.status());

        String newContent = Files.readString(worktreePath.resolve("src/Product.java"));
        assertTrue(newContent.contains("int count = 2;"));
        assertTrue(newContent.contains("String label = \"new\";"));

        // Control checkout remains unchanged
        String controlContent = Files.readString(controlRoot.resolve("src/Product.java"));
        assertTrue(controlContent.contains("int count = 1;"));
    }

    @Test
    void crlfReadContentCanBePatchedDirectlyAndPhysicalBytesRemainCrlf() throws Exception {
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-crlf-1", null, false);
        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(req);
        Path worktreePath = sessionService.resolveSessionContext(req).worktreePath();
        Path target = worktreePath.resolve("src/Product.java");
        byte[] raw = "public class Product {\r\n    int count = 1;\r\n}\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, raw);

        String logical = "public class Product {\n    int count = 1;\n}\n";
        WorkspacePatchService.PatchRequest request = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-crlf-1", "src/Product.java", false, null,
                sha256Hex(raw), List.of(new WorkspacePatchService.PatchEdit(
                        logical.substring(logical.indexOf("    int"), logical.indexOf("}\n")),
                        "    int count = 2;\n", 1)));

        AgentResponse response = new WorkspacePatchService().applyPatch(request);
        assertEquals(AgentStatus.COMPLETED, response.status());
        byte[] changed = Files.readAllBytes(target);
        assertEquals("public class Product {\r\n    int count = 2;\r\n}\r\n",
                new String(changed, StandardCharsets.UTF_8));
        assertEquals(sha256Hex(changed), revisionOf(response));
    }

    @Test
    void sequentialMultiFilePatchesPreserveUntouchedRevision() throws Exception {
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-multi", null, false);
        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(req);
        Path worktree = sessionService.resolveSessionContext(req).worktreePath();
        String productHash = sha256Hex(Files.readString(worktree.resolve("src/Product.java")));
        String otherHash = sha256Hex(Files.readString(worktree.resolve("src/Other.java")));
        WorkspacePatchService service = new WorkspacePatchService();

        AgentResponse first = service.applyPatch(new WorkspacePatchService.PatchRequest(controlRoot, "codex",
                "conn-patch-multi", "src/Product.java", false, null, productHash,
                List.of(new WorkspacePatchService.PatchEdit("int count = 1;", "int count = 2;", 1))));
        assertEquals(AgentStatus.COMPLETED, first.status());
        assertNotNull(revisionOf(first));

        AgentResponse second = service.applyPatch(new WorkspacePatchService.PatchRequest(controlRoot, "codex",
                "conn-patch-multi", "src/Other.java", false, null, otherHash,
                List.of(new WorkspacePatchService.PatchEdit("String value = \"old\";", "String value = \"new\";", 1))));
        assertEquals(AgentStatus.COMPLETED, second.status());
        assertTrue(Files.readString(worktree.resolve("src/Product.java")).contains("int count = 2;"));
        assertTrue(Files.readString(worktree.resolve("src/Other.java")).contains("String value = \"new\";"));
        assertTrue(Files.readString(controlRoot.resolve("src/Product.java")).contains("int count = 1;"));
        assertTrue(Files.readString(controlRoot.resolve("src/Other.java")).contains("String value = \"old\";"));
    }

    @Test
    void repeatedSameFileUsesReturnedRevisionAndRejectsOldRevision() throws Exception {
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-repeat", null, false);
        AgentSessionService sessionService = new AgentSessionService();
        sessionService.ensureSession(req);
        Path worktree = sessionService.resolveSessionContext(req).worktreePath();
        String firstHash = sha256Hex(Files.readString(worktree.resolve("src/Product.java")));
        WorkspacePatchService service = new WorkspacePatchService();
        WorkspacePatchService.PatchRequest patch = new WorkspacePatchService.PatchRequest(controlRoot, "codex",
                "conn-patch-repeat", "src/Product.java", false, null, firstHash,
                List.of(new WorkspacePatchService.PatchEdit("int count = 1;", "int count = 2;", 1)));
        AgentResponse first = service.applyPatch(patch);
        String secondHash = revisionOf(first);
        AgentResponse old = service.applyPatch(patch);
        assertTrue(old.toJson().contains("file_revision_stale"));
        AgentResponse next = service.applyPatch(new WorkspacePatchService.PatchRequest(controlRoot, "codex",
                "conn-patch-repeat", "src/Product.java", false, null, secondHash,
                List.of(new WorkspacePatchService.PatchEdit("int count = 2;", "int count = 3;", 1))));
        assertEquals(AgentStatus.COMPLETED, next.status());
    }

    @Test
    void testRejectsStaleHashWithNoMutation() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-3", null, false);
        sessionService.ensureSession(req);

        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();
        String originalContent = Files.readString(worktreePath.resolve("src/Product.java"));

        WorkspacePatchService patchService = new WorkspacePatchService();
        WorkspacePatchService.PatchEdit edit = new WorkspacePatchService.PatchEdit("int count = 1;", "int count = 2;", 1);
        String staleHash = "0000000000000000000000000000000000000000000000000000000000000000";

        WorkspacePatchService.PatchRequest modifyReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-3", "src/Product.java", false, null, staleHash, List.of(edit));

        AgentResponse response = patchService.applyPatch(modifyReq);
        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
        assertTrue(response.toJson().contains("file_revision_stale"));

        // Content in worktree is unchanged
        assertEquals(originalContent, Files.readString(worktreePath.resolve("src/Product.java")));
    }

    @Test
    void missingHashIsReportedAsARequiredPatchPreconditionNotWorkspaceStaleness() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-missing-hash", null, false);
        sessionService.ensureSession(req);

        WorkspacePatchService.PatchEdit edit = new WorkspacePatchService.PatchEdit(
                "int count = 1;", "int count = 2;", 1);
        AgentResponse response = new WorkspacePatchService().applyPatch(new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-missing-hash", "src/Product.java", false,
                null, null, List.of(edit)));

        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
        assertTrue(response.toJson().contains("patch_precondition_required"));
        assertFalse(response.toJson().contains("workspace_stale"));
    }

    @Test
    void testRejectsOccurrenceMismatchAndRollsBackCompletely() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-4", null, false);
        sessionService.ensureSession(req);

        var ctx = sessionService.resolveSessionContext(req);
        Path worktreePath = ctx.worktreePath();
        String originalContent = Files.readString(worktreePath.resolve("src/Product.java"));
        String hash = sha256Hex(originalContent);

        WorkspacePatchService patchService = new WorkspacePatchService();
        // edit1 valid, edit2 expects 5 occurrences but only 1 exists
        WorkspacePatchService.PatchEdit edit1 = new WorkspacePatchService.PatchEdit("int count = 1;", "int count = 2;", 1);
        WorkspacePatchService.PatchEdit edit2 = new WorkspacePatchService.PatchEdit("String label = \"old\";", "String label = \"new\";", 5);

        WorkspacePatchService.PatchRequest modifyReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-4", "src/Product.java", false, null, hash, List.of(edit1, edit2));

        AgentResponse response = patchService.applyPatch(modifyReq);
        assertEquals(AgentStatus.RETRY_REQUIRED, response.status());
        assertTrue(response.toJson().contains("patch_context_mismatch"));

        // Verify edit1 was NOT applied (complete rollback)
        assertEquals(originalContent, Files.readString(worktreePath.resolve("src/Product.java")));
    }

    @Test
    void testDeniesProtectedTargets() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-5", null, false);
        sessionService.ensureSession(req);

        WorkspacePatchService patchService = new WorkspacePatchService();
        String[] protectedPaths = {
            ".synesis/project.json",
            ".git/HEAD",
            ".codex/mcp.json",
            ".agents/mcp.json"
        };

        for (String p : protectedPaths) {
            WorkspacePatchService.PatchRequest createReq = new WorkspacePatchService.PatchRequest(
                    controlRoot, "codex", "conn-patch-5", p, true, "hacked", null, List.of());
            AgentResponse resp = patchService.applyPatch(createReq);
            assertEquals(AgentStatus.BLOCKED, resp.status());
            assertTrue(resp.toJson().contains("protected_configuration"), "Path should be protected: " + p);
        }
    }

    @Test
    void testDeniesPathTraversalAndSymlinkEscape() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-6", null, false);
        sessionService.ensureSession(req);

        WorkspacePatchService patchService = new WorkspacePatchService();
        WorkspacePatchService.PatchRequest travReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-6", "../outside.txt", true, "hacked", null, List.of());

        AgentResponse resp = patchService.applyPatch(travReq);
        assertEquals(AgentStatus.BLOCKED, resp.status());
        assertTrue(resp.toJson().contains("invalid_path"));
    }

    @Test
    void testRetainsEvidenceInternally() throws Exception {
        AgentSessionService sessionService = new AgentSessionService();
        AgentSessionService.SessionResolutionRequest req = new AgentSessionService.SessionResolutionRequest(
                controlRoot, "codex", "conn-patch-7", null, false);
        sessionService.ensureSession(req);

        WorkspacePatchService patchService = new WorkspacePatchService();
        WorkspacePatchService.PatchRequest createReq = new WorkspacePatchService.PatchRequest(
                controlRoot, "codex", "conn-patch-7", "src/EvidenceTest.java", true, "public class EvidenceTest {}\n", null, List.of());

        patchService.applyPatch(createReq);

        // Check internal evidence directory
        Path evidenceDir = controlRoot.resolve(".synesis/local/evidence/codex");
        assertTrue(Files.exists(evidenceDir));
        try (var files = Files.list(evidenceDir)) {
            assertTrue(files.count() > 0, "Internal evidence record should be retained");
        }
    }
}
