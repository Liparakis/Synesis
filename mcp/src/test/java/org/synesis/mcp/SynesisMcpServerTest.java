package org.synesis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies fail-closed MCP launcher argument admission. */
class SynesisMcpServerTest {

    @Test
    void explicitProjectWithoutValueRejectsStartup() {
        assertEquals(2, SynesisMcpServer.execute(new String[]{"--project"}));
    }

    @Test
    void explicitProjectCannotConsumeAnotherOptionAsItsValue() {
        assertEquals(2, SynesisMcpServer.execute(new String[]{"--project", "--provider", "codex"}));
    }
}
