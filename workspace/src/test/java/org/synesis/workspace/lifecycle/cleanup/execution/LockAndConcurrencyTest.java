package org.synesis.workspace.lifecycle.cleanup.execution;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;

/**
 * Exercises lifecycle locks and concurrent cleanup coordination.
 */
class LockAndConcurrencyTest {

  @Test
  @SuppressWarnings("try")
  void locksExecutionAndFailsConcurrentAcquisition(@TempDir Path tempDir) throws Exception {
    Path controlRoot = tempDir.resolve("control-repo");
    Files.createDirectories(controlRoot);
    new ProjectApplicationService().init(controlRoot);

    try (CleanupExecutionLock _ = CleanupExecutionLock.acquire(controlRoot, "plan-1")) {
      // Concurrent acquire attempt must fail closed
      assertThrows(IOException.class, () -> CleanupExecutionLock.acquire(controlRoot, "plan-2"));
    }

    // After lock1 closes, lock file should be released
    Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
    Path lockFile = workspaceRoot.resolve("admin/cleanup-execution.lock");
    assertFalse(Files.exists(lockFile));
  }

  @Test
  @SuppressWarnings("try")
  void takesOverLockWhoseHolderProcessIsDead(@TempDir Path tempDir) throws Exception {
    Path controlRoot = initializedControlRoot(tempDir);
    Path lockFile = ProjectAdministrativeLayout.cleanupExecutionLock(controlRoot);
    Files.createDirectories(lockFile.getParent());
    Files.writeString(lockFile, "{\"pid\":9999999,\"processStartTime\":1,\"nonce\":\"dead\",\"acquiredAtEpochMillis\":2}");

    try (CleanupExecutionLock _ = CleanupExecutionLock.acquire(controlRoot, "plan-1")) {
      assertFalse(Files.readString(lockFile).contains("dead"));
      assertThrows(IOException.class, () -> CleanupExecutionLock.acquire(controlRoot, "plan-2"));
    }
    assertFalse(Files.exists(lockFile));
  }

  @Test
  @SuppressWarnings("try")
  void takesOverLockWhosePidNowBelongsToAnotherProcess(@TempDir Path tempDir) throws Exception {
    Path controlRoot = initializedControlRoot(tempDir);
    Path lockFile = ProjectAdministrativeLayout.cleanupExecutionLock(controlRoot);
    Files.createDirectories(lockFile.getParent());
    long pid = ProcessHandle.current().pid();
    Files.writeString(lockFile, "{\"pid\":" + pid + ",\"processStartTime\":1,\"nonce\":\"reused\",\"acquiredAtEpochMillis\":2}");

    try (CleanupExecutionLock _ = CleanupExecutionLock.acquire(controlRoot, "plan-1")) {
      assertFalse(Files.readString(lockFile).contains("reused"));
    }
  }

  @Test
  void keepsBlockingWhenTheHolderIsAliveOrCannotBeDetermined(@TempDir Path tempDir) throws Exception {
    Path controlRoot = initializedControlRoot(tempDir);
    Path lockFile = ProjectAdministrativeLayout.cleanupExecutionLock(controlRoot);
    Files.createDirectories(lockFile.getParent());
    long pid = ProcessHandle.current().pid();
    long start = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
    String[] contents = {
      "{\"pid\":" + pid + ",\"processStartTime\":" + start + ",\"acquiredAtEpochMillis\":" + (start + 5) + "}",
      "{\"pid\":" + pid + ",\"processStartTime\":7,\"acquiredAtEpochMillis\":7}",
      "{\"pid\":" + pid + "}",
      "",
      "not json",
      "{\"planId\":\"no-pid\"}",
      "{\"pid\":0}"
    };
    for (String content : contents) {
      Files.writeString(lockFile, content);
      assertThrows(IOException.class, () -> CleanupExecutionLock.acquire(controlRoot, "plan-1"), content);
      assertEquals(content, Files.readString(lockFile));
    }
  }

  private static Path initializedControlRoot(Path tempDir) throws Exception {
    Path controlRoot = tempDir.resolve("control-repo");
    Files.createDirectories(controlRoot);
    new ProjectApplicationService().init(controlRoot);
    return controlRoot;
  }
}
