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

/** Runs host and join through the installed launcher with isolated profiles. */
@Timeout(120)
final class GeneratedOnboardingTest {
    @Test
    void installedLaunchersCompleteTwoProfileOnboarding() throws Exception {
        Path hostProfile = Files.createTempDirectory("synesis-generated-host");
        Path joinProfile = Files.createTempDirectory("synesis-generated-join");
        Process host = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), hostProfile, "host");
        Process join = null;
        try {
            CompletableFuture<String> linkFuture = new CompletableFuture<>();
            CompletableFuture<CapturedHost> hostFuture = CompletableFuture.supplyAsync(() -> captureHost(host, linkFuture));
            String link = linkFuture.get(45, TimeUnit.SECONDS);
            join = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), joinProfile, "join", link);
            boolean joinExited = join.waitFor(60, TimeUnit.SECONDS);
            CapturedHost capturedHost = hostFuture.get(60, TimeUnit.SECONDS);
            assertTrue(joinExited);
            assertEquals(0, join.exitValue());
            assertEquals(0, host.exitValue());
            String joinOutput = new String(join.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(capturedHost.output().contains("SHARE_LINK="));
            assertTrue(capturedHost.output().contains("CONTROL_READY=true"));
            assertTrue(capturedHost.output().contains("SESSION_CLOSED"));
            assertTrue(joinOutput.contains("INVITE_VERIFIED"));
            assertTrue(joinOutput.contains("WORK_RESULT=OK"));
            assertTrue(joinOutput.contains("SESSION_CLOSED"));
        } finally {
            if (join != null && join.isAlive()) join.destroyForcibly();
            if (host.isAlive()) host.destroyForcibly();
        }
    }

    private static CapturedHost captureHost(Process host, CompletableFuture<String> linkFuture) {
        StringBuilder output = new StringBuilder();
        String link = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (line.startsWith("SHARE_LINK=")) {
                    link = line.substring("SHARE_LINK=".length());
                    linkFuture.complete(link);
                }
            }
            if (link == null) {
                IllegalStateException failure = new IllegalStateException("launcher host emitted no invitation");
                linkFuture.completeExceptionally(failure);
                throw failure;
            }
            if (!host.waitFor(60, TimeUnit.SECONDS)) {
                linkFuture.completeExceptionally(new IllegalStateException("launcher host did not close"));
            }
            return new CapturedHost(link, output.toString());
        } catch (IOException | InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("launcher host capture failed", failure);
        }
    }

    private record CapturedHost(String link, String output) { }
}
