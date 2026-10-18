package org.synesis.workspace.provider.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins Synesis's hash of its own Codex Stop hook to the one Codex itself computes: if they differ, Codex skips the hook
 * as untrusted and the agent is woken only by the run controller.
 */
class CodexStopHookTest {

    /**
     * {@code currentHash} that Codex CLI 0.163.0 reported through app-server {@code hooks/list} on Windows on
     * 2026-10-18 for {@code -c "hooks.Stop=[{hooks=[{type='command',command='C:\synesis\bin\synesis.exe hook codex',timeout=660}]}]"}.
     */
    @Test
    void theHashIsTheOneCodexComputesForTheSameHook() {
        assertEquals("sha256:49c016353ee6d4d1739079a90bc3d9b5935efc70b2062f7b219df3456a34e52f", CodexStopHook.hash("C:\\synesis\\bin\\synesis.exe hook codex"));
    }

    /** The keys Codex 0.163.0 reported for the first Stop handler given on the command line, on Windows and on Linux. */
    @Test
    void theKeyNamesTheCommandLineLayerAsCodexDoes() {
        assertEquals("C:\\<session-flags>\\config.toml:stop:0:0", CodexStopHook.key(true));
        assertEquals("/<session-flags>/config.toml:stop:0:0", CodexStopHook.key(false));
    }
}
