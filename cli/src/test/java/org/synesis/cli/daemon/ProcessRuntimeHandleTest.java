package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRuntimeHandleTest {

    @Test
    void aProjectRuntimesStandardErrorIsNeverAPipeNobodyReads(@TempDir Path project) {
        // A pipe nobody reads filled during a cut link and froze the runtime's log exchange (PC + VM run, 2026-10-18).
        assertNotEquals(ProcessBuilder.Redirect.PIPE, ProcessRuntimeHandle.command(project).redirectError());
    }
}
