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
import org.synesis.workspace.application.ProviderSessionBindingService.WorkspaceVerificationResult;
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
    void test01RealRegisteredWorktreeCanTransitionToVerified() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktreePath = Path.of(binding.worktreePath());

        WorkspaceVerificationResult res = bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktreePath);

        assertTrue(res.verified());
        assertEquals("WORKSPACE_VERIFIED", res.code());
        assertNotNull(res.evidenceDigest());
        assertEquals("VERIFIED", res.binding().providerTrustState());
    }

    @Test
    void test02MatchingStringsWithoutValidGitEvidenceRemainUnverified() throws Exception {
        Path fakeWorktree = tempDir.getParent().resolve("fake_worktree_" + UUID.randomUUID());
        Files.createDirectories(fakeWorktree);

        try {
            // Fake session pointing to unregistered folder
            Path sessionDir = location.synesisDirectory().resolve("local/sessions");
            Map<String, Object> map = Map.ofEntries(
                    Map.entry("schemaVersion", 2), Map.entry("sessionId", "session-fake"), Map.entry("projectId", location.projectId().toString()),
                    Map.entry("nodeId", "node1"), Map.entry("provider", "codex"), Map.entry("providerInstanceFingerprint", "fakefingerprint"),
                    Map.entry("supervisorId", "sup1"), Map.entry("workerId", "work1"), Map.entry("worktreeId", "wt1"),
                    Map.entry("worktreePath", fakeWorktree.toString()), Map.entry("controlCheckoutPath", location.root().toString()),
                    Map.entry("branch", "synesis/codex/session-fake"), Map.entry("baseCommit", "37eaa5aad4bf2f192c76a8a3e001120eeeb603e4"),
                    Map.entry("creationState", "ALLOCATED"), Map.entry("verificationState", "UNVERIFIED"), Map.entry("lastSeenState", "UNVERIFIED"),
                    Map.entry("status", "BOUND"), Map.entry("createdAtEpochMillis", System.currentTimeMillis()),
                    Map.entry("lastSeenEpochMillis", System.currentTimeMillis()), Map.entry("lastVerifiedProjectSequence", 0L),
                    Map.entry("providerTrustState", "WORKSPACE_UNVERIFIED"), Map.entry("bindingVersion", 1)
            );
            Files.writeString(sessionDir.resolve("codex-fakefingerprint.json"), ProviderJson.write(map));

            WorkspaceVerificationResult res = bindingService.verifyWorkspaceTrust(location, "codex", "session-fake", fakeWorktree);

            assertFalse(res.verified());
            assertEquals("WORKTREE_NOT_REGISTERED", res.code());
        } finally {
            Files.deleteIfExists(fakeWorktree);
        }
    }

    @Test
    void test03ControlCheckoutCannotBecomeVerified() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();

        WorkspaceVerificationResult res = bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), location.root());

        assertFalse(res.verified());
        assertEquals("CONTROL_CHECKOUT_MUTATION_DENIED", res.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test04AnotherSessionsWorktreeCannotBecomeVerified() throws Exception {
        var b1 = bindingService.list(location, "codex").getLast();

        // Create second binding using fake fingerprint file
        Path fakeKey = location.synesisDirectory().resolve("local/providers/codex.bootstrap-key");
        Files.deleteIfExists(fakeKey);
        var b2Res = bindingService.ensure(location, "codex", "evidence-two");
        var b2 = b2Res.binding();

        // Point b2's worktreePath and branch to b1's worktree and branch
        Path sessionDir = location.synesisDirectory().resolve("local/sessions");
        Path b2Path = sessionDir.resolve("codex-" + b2.providerInstanceFingerprint() + ".json");
        Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(Files.readString(b2Path));
        map.put("worktreePath", b1.worktreePath());
        map.put("branch", b1.branch());
        Files.writeString(b2Path, ProviderJson.write(map));

        WorkspaceVerificationResult res = bindingService.verifyWorkspaceTrust(location, "codex", b2.sessionId(), Path.of(b1.worktreePath()));

        assertFalse(res.verified());
        assertEquals("DUPLICATE_ACTIVE_WORKTREE", res.code());
    }

    @Test
    void test05PathTraversalIsRejected() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "../escaped.txt", "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.BLOCKED, result.decision());
        assertFalse(Files.exists(tempDir.getParent().resolve("escaped.txt")));
    }

    @Test
    void test06AbsoluteTargetPathsAreRejected() throws Exception {
        setVerifiedTrustState();

        String absPath = tempDir.getRoot().resolve("escaped_abs.txt").toString();
        MutationRequest request = new MutationRequest(location, "codex", absPath, "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.BLOCKED, result.decision());
    }

    @Test
    void test07SymlinkEscapeIsRejected() throws Exception {
        setVerifiedTrustState();

        var binding = bindingService.list(location, "codex").getLast();
        Path worktreePath = Path.of(binding.worktreePath());
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);

        Path symlink = worktreePath.resolve("symdir");
        try {
            Files.createSymbolicLink(symlink, outsideDir);
        } catch (Exception e) {
            // Symlinks may require elevated privileges on Windows; skip if restricted
            return;
        }

        MutationRequest request = new MutationRequest(location, "codex", "symdir/file.txt", "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.BLOCKED, result.decision());
    }

    @Test
    void test08MutationWithoutVerifiedTrustIsRejected() {
        MutationRequest request = new MutationRequest(location, "codex", "src/file.txt", "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.WORKSPACE_UNVERIFIED, result.decision());
    }

    @Test
    void test09MutationWithoutAllowIsRejected() throws Exception {
        setVerifiedTrustState();

        ConstraintApplicationService constraintService = new ConstraintApplicationService();
        constraintService.create(location, "Block src", "No edits", "src/protected.txt", ProjectConstraint.Effect.BLOCK);

        MutationRequest request = new MutationRequest(location, "codex", "src/protected.txt", "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertFalse(result.success());
        assertEquals(Decision.BLOCKED, result.decision());
    }

    @Test
    void test10ExactAllowedCreateOperationSucceeds() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/created.txt", "write_file", "hello world", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success(), "applyMutation failed: " + result.message() + " decision: " + result.decision());
        assertEquals(Decision.ALLOW, result.decision());
        assertNotNull(result.mutatedPath());
        assertTrue(Files.exists(result.mutatedPath()));
        assertEquals("hello world", Files.readString(result.mutatedPath()));
    }

    @Test
    void test11EvidenceAndDecisionIdAreReturned() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/evidence.txt", "write_file", "data", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success(), "applyMutation failed: " + result.message() + " decision: " + result.decision());
        assertNotNull(result.decisionId());
        assertTrue(result.decisionId().startsWith("dec-"));
        assertNotNull(result.interceptionEvidence());
        assertEquals(64, result.interceptionEvidence().length());
    }

    @Test
    void test13AgentsMdInstructsBrokeredMutation() throws Exception {
        String agentsText = Files.readString(location.root().resolve("AGENTS.md"));

        assertTrue(agentsText.contains("synesis workspace verify"));
        assertTrue(agentsText.contains("synesis workspace mutate"));
        assertTrue(agentsText.contains("Never use native apply_patch, shell redirection, or direct writes"));
    }

    @Test
    void test15ControlCheckoutRemainsUnchanged() throws Exception {
        setVerifiedTrustState();

        MutationRequest request = new MutationRequest(location, "codex", "src/worktree_only.txt", "write_file", "content", true, false);
        MutationResult result = broker.applyMutation(request);

        assertTrue(result.success(), "applyMutation failed: " + result.message() + " decision: " + result.decision());
        assertTrue(result.controlCheckoutUnchanged());
        assertFalse(Files.exists(location.root().resolve("src/worktree_only.txt")));
    }

    private void setVerifiedTrustState() throws Exception {
        var binding = bindingService.list(location, "codex").getLast();
        Path worktreePath = Path.of(binding.worktreePath());
        var res = bindingService.verifyWorkspaceTrust(location, "codex", binding.sessionId(), worktreePath);
        assertTrue(res.verified(), "setVerifiedTrustState failed: " + res.code());
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
