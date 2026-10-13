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

  @Test
  void waitHoldIsTwentySecondsUnlessZeroToThirtyIsConfigured() {
    java.time.Duration twenty = java.time.Duration.ofSeconds(20);
    assertEquals(twenty, SynesisMcpServer.waitHold(null));
    assertEquals(twenty, SynesisMcpServer.waitHold(""));
    assertEquals(twenty, SynesisMcpServer.waitHold("  "));
    assertEquals(java.time.Duration.ZERO, SynesisMcpServer.waitHold("0"));
    assertEquals(java.time.Duration.ofSeconds(30), SynesisMcpServer.waitHold("30"));
    assertEquals(twenty, SynesisMcpServer.waitHold("31"));
    assertEquals(twenty, SynesisMcpServer.waitHold("-1"));
    assertEquals(twenty, SynesisMcpServer.waitHold("abc"));
  }
}
