package org.synesis.cli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Defines the bounded authenticated installation-daemon IPC contract. */
final class DaemonProtocolTest {

  @Test
  void parsesOnlySupportedAuthenticatedOperations() throws Exception {
    DaemonProtocol.Request request = DaemonProtocol.parse(
        DaemonProtocol.writeRequest(Map.of(
            "token", "secret",
            "operation", "OPEN_PROJECT",
            "project", Path.of("C:\\projects\\one").toString())));

    assertEquals("secret", request.token());
    assertEquals(DaemonProtocol.Operation.OPEN_PROJECT, request.operation());
    assertEquals(Path.of("C:\\projects\\one").toString(), request.project());
  }

  @Test
  void rejectsMalformedUnknownAndOversizedRequests() {
    assertThrows(IOException.class, () -> DaemonProtocol.parse("not-json"));
    assertThrows(IOException.class, () -> DaemonProtocol.parse(
        DaemonProtocol.writeRequest(Map.of("token", "secret", "operation", "SHELL"))));
    assertThrows(IOException.class, () -> DaemonProtocol.parse("x".repeat(
        DaemonProtocol.MAX_REQUEST_BYTES + 1)));
  }

  @Test
  void rejectsMissingOrOverlongSensitiveFields() {
    assertThrows(IOException.class, () -> DaemonProtocol.parse(
        DaemonProtocol.writeRequest(Map.of("operation", "STATUS"))));
    assertThrows(IOException.class, () -> DaemonProtocol.parse(
        DaemonProtocol.writeRequest(Map.of(
            "token", "x".repeat(DaemonProtocol.MAX_TOKEN_CHARS + 1),
            "operation", "STATUS"))));
  }

  @Test
  void singletonLockRejectsSecondOwnerAndReleasesOnClose() throws Exception {
    Path lock = Files.createTempFile("synesis-daemon", ".lock");
    try (DaemonLock first = DaemonLock.acquire(lock)) {
      assertTrue(first.isHeld());
      assertThrows(IOException.class, () -> DaemonLock.acquire(lock));
    }
    try (DaemonLock second = DaemonLock.acquire(lock)) {
      assertTrue(second.isHeld());
    }
  }
}
