package org.synesis.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.synesis.projectrecord.ProjectConfig;
import org.synesis.projectrecord.ProjectConstraint;
import org.synesis.workspace.application.ConstraintApplicationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderSessionBindingService;
import org.synesis.workspace.application.WorkspaceMutationBroker;
import org.synesis.workspace.application.WorkspaceMutationBroker.Decision;
import org.synesis.workspace.application.WorkspaceMutationBroker.MutationRequest;
import org.synesis.workspace.application.WorkspaceMutationBroker.MutationResult;
import org.synesis.workspace.provider.ProviderJson;

class WorkspaceMutationBrokerTest {

    private Path tempDir;
    private ProjectApplicationService.ProjectLocation location;
    private ProviderSessionBindingService bindingService;
    private WorkspaceMutationBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("synesis-broker-test-");
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.name", "Test User");
        runGit(tempDir, "config", "user.email", "test@example.com");
        Files.writeString(tempDir.resolve("README.md"), "# Test\n");
        runGit(tempDir, "add", "README.md");
        runGit(tempDir, "commit", "-m", "initial commit");

        ProjectApplicationService projectService = new ProjectApplicationService();
        location = projectService.init(tempDir).location();
        UUID projectId = location.projectId();
        new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64)))
                .save(location.profile().resolve("project.conf"));

        bindingService = new ProviderSessionBindingService();
        bindingService.ensure(location, "codex", null);
        broker = new WorkspaceMutationBroker();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void test1WorkspaceUnverifiedCannotMutate() {
        // Default binding providerTrustState is WORKSPACE_UNVERIFIED
        MutationRequest request = new MutationRequest(location, "codex", "src/file1.txt", "write_file", "content1", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.WORKSPACE_UNVERIFIED, result.decision());
        assertTrue(result.controlCheckoutUnchanged());
        assertFalse(Files.exists(location.root().resolve("src/file1.txt")));
    }

    @Test
    void test2MissingInterceptionCannotMutate() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/file2.txt", "write_file", "content2", false, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.INTERCEPTION_MISSING, result.decision());
        assertTrue(result.controlCheckoutUnchanged());
        assertFalse(Files.exists(location.root().resolve("src/file2.txt")));
    }

    @Test
    void test3UnknownOrBlockedDecisionCannotMutate() throws Exception {
        setVerifiedTrustState();

        ConstraintApplicationService constraintService = new ConstraintApplicationService();
        constraintService.create(location, "Protected file", "No edits", "src/protected.txt", ProjectConstraint.Effect.BLOCK);

        MutationRequest request = new MutationRequest(location, "codex", "src/protected.txt", "write_file", "content3", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.BLOCKED, result.decision());
        assertTrue(result.controlCheckoutUnchanged());
        assertFalse(Files.exists(location.root().resolve("src/protected.txt")));
    }

    @Test
    void test4OnlyAllowPermitsExactMutation() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/allowed.txt", "write_file", "allowed content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success());
        assertEquals(Decision.ALLOW, result.decision());
        assertNotNull(result.mutatedPath());
        assertTrue(Files.exists(result.mutatedPath()));
        assertEquals("allowed content", Files.readString(result.mutatedPath()));
    }

    @Test
    void test5SuccessfulMutationRecordsEvidenceAndDecisionId() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/evidence.txt", "write_file", "evidence payload", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success());
        assertNotNull(result.decisionId());
        assertTrue(result.decisionId().startsWith("dec-"));
        assertNotNull(result.interceptionEvidence());
        assertEquals(64, result.interceptionEvidence().length());

        Path evidenceFile = location.synesisDirectory().resolve("local/evidence/codex/" + result.decisionId() + ".json");
        assertTrue(Files.exists(evidenceFile));
        String json = Files.readString(evidenceFile);
        assertTrue(json.contains("\"decision\":\"ALLOW\""));
        assertTrue(json.contains("\"hookIntercepted\":true"));
    }

    @Test
    void test6ControlCheckoutRemainsUnchanged() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/worktree_only.txt", "write_file", "worktree content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success());
        assertTrue(result.controlCheckoutUnchanged());
        assertFalse(Files.exists(location.root().resolve("src/worktree_only.txt")));
    }

    @Test
    void test7SyntheticHookExecutionDoesNotCountAsRealInterception() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/synthetic.txt", "write_file", "synthetic content", true, true);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.INTERCEPTION_MISSING, result.decision());
        assertFalse(Files.exists(location.root().resolve("src/synthetic.txt")));
    }

    @SuppressWarnings("unchecked")
    private void setVerifiedTrustState() throws Exception {
        Path sessionDir = location.synesisDirectory().resolve("local/sessions");
        try (var paths = Files.list(sessionDir)) {
            for (Path p : paths.filter(item -> item.getFileName().toString().startsWith("codex-")).toList()) {
                Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(Files.readString(p));
                map.put("providerTrustState", "VERIFIED");
                Files.writeString(p, ProviderJson.write(map) + System.lineSeparator());
            }
        }
    }

    private static void runGit(Path root, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = root.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }
}
