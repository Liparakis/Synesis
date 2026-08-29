package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests the shared lifecycle plan-integrity digest.
 */
class PlanIntegrityTest {

    /**
     * Verifies the canonical UTF-8 SHA-256 representation.
     */
    @Test
    void computesCanonicalSha256() throws Exception {
        assertEquals(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                PlanIntegrity.sha256Utf8("test"));
    }
}
