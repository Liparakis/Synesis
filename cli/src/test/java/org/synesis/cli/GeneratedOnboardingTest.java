package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs host and join through the installed launcher with isolated profiles.
 */
@Timeout(120)
final class GeneratedOnboardingTest {

  private static CompletableFuture<CapturedProcess> capture(Process process, String marker,
      CompletableFuture<String> linkFuture) {
    return CompletableFuture.supplyAsync(() -> {
      StringBuilder output = new StringBuilder();
      String link = null;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(),
              StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line)
              .append(System.lineSeparator());
          if (line.startsWith(marker + "=")) {
            link = line.substring((marker + "=").length());
            linkFuture.complete(link);
          }
        }
        if (link == null) {
          IllegalStateException failure = new IllegalStateException(
              "launcher emitted no " + marker);
          linkFuture.completeExceptionally(failure);
          throw failure;
        }
        return new CapturedProcess(link, output.toString());
      } catch (IOException failure) {
        throw new IllegalStateException("launcher output capture failed", failure);
      }
    });
  }

  @Test
  void installedLaunchersCompleteTwoProfileOnboarding() throws Exception {
    Path hostProfile = Files.createTempDirectory("synesis-generated-host");
    Path joinProfile = Files.createTempDirectory("synesis-generated-join");
    Process host = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), hostProfile,
        "host");
    Process join = null;
    try {
      CompletableFuture<String> linkFuture = new CompletableFuture<>();
      CompletableFuture<CapturedProcess> hostFuture = capture(host, "SHARE_LINK", linkFuture);
      String link = linkFuture.get(45, TimeUnit.SECONDS);
      join = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), joinProfile,
          "join", link);
      CompletableFuture<String> answerFuture = new CompletableFuture<>();
      CompletableFuture<CapturedProcess> joinFuture = capture(join, "ANSWER_LINK", answerFuture);
      String answer = answerFuture.get(45, TimeUnit.SECONDS);
      host.getOutputStream()
          .write((answer + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      host.getOutputStream().flush();
      CapturedProcess capturedHost = hostFuture.get(75, TimeUnit.SECONDS);
      CapturedProcess capturedJoin = joinFuture.get(75, TimeUnit.SECONDS);
      assertTrue(join.waitFor(10, TimeUnit.SECONDS), "join process did not exit");
      assertTrue(host.waitFor(10, TimeUnit.SECONDS), "host process did not exit");
      assertEquals(0, join.exitValue());
      assertEquals(0, host.exitValue());
      String joinOutput = capturedJoin.output();
      assertTrue(capturedHost.output()
          .contains("SHARE_LINK="));
      assertTrue(capturedHost.output()
          .contains("AWAITING_ANSWER=true"));
      assertTrue(capturedHost.output()
          .contains("ANSWER_VERIFIED"));
      assertTrue(capturedHost.output()
          .contains("CONTROL_READY=true"));
      assertTrue(capturedHost.output()
          .contains("SESSION_CLOSED"));
      assertTrue(joinOutput.contains("INVITE_VERIFIED"));
      assertTrue(joinOutput.contains("ANSWER_LINK="));
      assertTrue(joinOutput.contains("WORK_RESULT=OK"));
      assertTrue(joinOutput.contains("SESSION_CLOSED"));
    } finally {
      if (join != null && join.isAlive()) {
        join.destroyForcibly();
      }
      if (host.isAlive()) {
        host.destroyForcibly();
      }
    }
  }

  /**
   * Captures one generated onboarding host result for assertions.
   */
  private record CapturedProcess(String link, String output) {

  }
}
