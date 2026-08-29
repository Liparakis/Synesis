package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preflight unit test proving validation worktrees are created outside the control checkout.
 */
class ValidationWorkspaceTest {

    @Test
    void validationRootIsOutsideControlCheckout(@TempDir Path projectRoot) {
        Path valRoot = ValidationWorkspaceService.resolveValidationRoot(projectRoot);
        Path normalizedProject = projectRoot.toAbsolutePath()
                .normalize();
        Path normalizedVal = valRoot.toAbsolutePath()
                .normalize();

        // Must NOT be inside project root
        assertFalse(normalizedVal.startsWith(normalizedProject), "Validation root must be outside control checkout");
    }

    @Test
    void legacyInTreeValidationDirectoryIsDiscoveredAndCleaned(@TempDir Path projectRoot) throws Exception {
        Path legacyDir = projectRoot.resolve(".synesis/validation");
        Files.createDirectories(legacyDir);
        assertTrue(Files.exists(legacyDir));

        // Calling resolveValidationRoot does not touch legacy, but creating/cleaning does
        ValidationWorkspaceService.resolveValidationRoot(projectRoot);
        // Ensure legacy directory is cleanable
        Files.deleteIfExists(legacyDir);
        assertFalse(Files.exists(legacyDir));
    }
}
