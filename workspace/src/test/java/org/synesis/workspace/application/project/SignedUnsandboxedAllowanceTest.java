package org.synesis.workspace.application.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.test.TestGit;

/**
 * The one seam through which a project can run commands without a sandbox (ADR-0104, Part C): only a signed
 * decision grants it, and anything unclear refuses.
 */
final class SignedUnsandboxedAllowanceTest {

    private static final ProjectPolicyService.OperatorPrompt TYPES = newKey -> "a passphrase for tests".toCharArray();

    @TempDir
    Path tmp;

    private final ProjectApplicationService service = new ProjectApplicationService();
    private final SignedUnsandboxedAllowance allowance = new SignedUnsandboxedAllowance();

    private Path project() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        TestGit.run(root, "init");
        TestGit.run(root, "config", "user.name", "Test User");
        TestGit.run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "# Baseline\n");
        TestGit.run(root, "add", "README.md");
        TestGit.run(root, "commit", "-m", "initial commit");
        service.init(root, false);
        return root;
    }

    private void sign(Path root, String value) throws Exception {
        ProjectPolicyService.decide(service.locate(root), TYPES, Map.of(PolicyName.SANDBOX_UNSANDBOXED, value), Optional.empty());
    }

    @Test
    void noControlRootOrNoProjectIsRefused() {
        assertFalse(allowance.allowedFor(null));
        assertFalse(allowance.allowedFor(tmp.resolve("nowhere")));
    }

    @Test
    void aProjectWithNoSignedDecisionIsRefusedAndItsFileCannotGrantIt() throws Exception {
        Path root = project();

        // The project file has no field for it, and a file that adds one is refused as malformed.
        Files.writeString(root.resolve(".synesis/project.json"), Files.readString(root.resolve(".synesis/project.json")).replaceFirst("\\}\\s*$", ",\"unsandboxed\":\"ALLOW\"}"));

        assertFalse(allowance.allowedFor(root));
        Files.writeString(root.resolve(".synesis/project.json"), Files.readString(root.resolve(".synesis/project.json")).replace(",\"unsandboxed\":\"ALLOW\"", ""));
        assertFalse(service.locate(root).unsandboxedAllowed());
    }

    @Test
    void aSignedAllowIsGrantedAndASignedDenyTakesEffectAtOnce() throws Exception {
        Path root = project();

        sign(root, "ALLOW");
        assertTrue(allowance.allowedFor(root));
        sign(root, "DENY");
        assertFalse(allowance.allowedFor(root));
    }

    @Test
    void aLogThatCannotBeReadIsRefused() throws Exception {
        Path root = project();
        sign(root, "ALLOW");
        try (var files = Files.list(service.locate(root).coordinationDirectory().resolve("events"))) {
            Path entry = files.filter(Files::isRegularFile).sorted().findFirst().orElseThrow();
            byte[] bytes = Files.readAllBytes(entry);
            Files.write(entry, Arrays.copyOf(bytes, bytes.length / 2));
        }

        assertFalse(allowance.allowedFor(root), "fail closed when the policy cannot be read");
    }
}
