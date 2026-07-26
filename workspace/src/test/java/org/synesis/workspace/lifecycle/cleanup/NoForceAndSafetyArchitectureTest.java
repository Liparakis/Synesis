package org.synesis.workspace.lifecycle.cleanup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoForceAndSafetyArchitectureTest {

    @Test
    void verifiesNoForceOrProhibitedOperationsInCleanupCode() throws Exception {
        Path srcDir = Path.of("src/main/java/org/synesis/workspace/lifecycle/cleanup");
        if (!Files.exists(srcDir)) {
            srcDir = Path.of("workspace/src/main/java/org/synesis/workspace/lifecycle/cleanup");
        }
        assertTrue(Files.isDirectory(srcDir), "Cleanup source directory must exist");

        try (var stream = Files.list(srcDir)) {
            List<Path> javaFiles = stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());

            for (Path file : javaFiles) {
                String code = Files.readString(file);
                // Strip single-line and multi-line comments so descriptive Javadoc comments do not trigger false positives
                String codeWithoutComments = code.replaceAll("//.*", "").replaceAll("(?s)/\\*.*?\\*/", "");

                assertFalse(codeWithoutComments.contains("\"--force\""), "Prohibited --force flag argument found in " + file);
                assertFalse(codeWithoutComments.contains("worktree\", \"prune\""), "Prohibited worktree prune command found in " + file);
                assertFalse(codeWithoutComments.contains(".destroy()"), "Prohibited process termination found in " + file);
                assertFalse(codeWithoutComments.contains("destroyForcibly()"), "Prohibited process forcible destruction found in " + file);
            }
        }
    }
}
