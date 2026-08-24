package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies managed manual installation and tamper attestation. */
class ProviderManualServiceTest {

    @Test
    void installationIsAtomicAndTamperingRestrictsAttestation(@TempDir Path tempHome) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            ProviderManualService service = new ProviderManualService();
            assertFalse(service.attest("codex").valid());
            assertTrue(service.install("codex").valid());
            Path manual = service.skillDirectory("codex").resolve("SKILL.md");
            String content = Files.readString(manual);
            assertTrue(content.contains("workflow `IMPLEMENT`"));
            assertTrue(content.contains("visible assigned worktree"));
            assertTrue(content.contains("Do not inspect `.synesis/**`"));
            assertTrue(content.contains("Do not call `finish_lane` or another lifecycle tool"));
            assertTrue(content.contains("Execute `finish_lane` only when `get_next_action` projects it"));
            assertTrue(content.contains("exact tool with those exact arguments"));
            assertTrue(content.contains("optional `integrationCheck` input only evaluates explicitly supplied compatibility facts"));
            assertTrue(content.contains("When `get_next_action` projects `WAIT`"));
            assertTrue(content.contains("Do not report success or stop merely because your own lane is complete"));
            Files.writeString(manual, Files.readString(manual) + "tampered\n");
            assertFalse(service.attest("codex").valid());
            assertTrue(service.install("codex").valid());
            assertTrue(service.attest("codex").valid());
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }
}
