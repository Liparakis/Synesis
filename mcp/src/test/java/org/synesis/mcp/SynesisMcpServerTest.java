package org.synesis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies fail-closed MCP launcher argument admission.
 */
class SynesisMcpServerTest {

  @Test
  void projectLaunchArgumentIsRemoved() {
    assertEquals(2, SynesisMcpServer.execute(new String[]{"--project", "C:/fixture"}));
  }
}
