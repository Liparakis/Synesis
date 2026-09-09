package org.synesis.relay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayEnvelope;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayMembershipSnapshot;

/**
 * Drives the real relay protocol against an extracted customer-style relay
 * launcher.
 *
 * <p>This test-only client is deliberately outside the shipped distribution.
 * It gives the release acceptance harness a separate origin and destination
 * identity, starts the extracted relay as a child process, verifies the
 * signed handshake and authorization boundary, forwards one end-to-end
 * encrypted frame, and then bounds process shutdown.
 */
public final class RelayArtifactAcceptanceMain {

  private static final long PROCESS_TIMEOUT_SECONDS = 20;
  private static final long OPERATION_TIMEOUT_SECONDS = 10;

  private RelayArtifactAcceptanceMain() {
  }

  /**
   * Runs one bounded shipped-relay acceptance scenario.
   *
   * @param arguments requires {@code --launcher PATH}
   * @throws Exception if the extracted relay does not authenticate, forward,
   *                   or shut down within the acceptance bounds
   */
  public static void main(String[] arguments) throws Exception {
    Options options = Options.parse(arguments);
    Path workDirectory = Files.createTempDirectory("synesis-relay-artifact-acceptance-");
    Path identityDirectory = workDirectory.resolve("relay-identity");
    NodeIdentity relayIdentity = new IdentityBootstrap(identityDirectory).loadOrCreate()
        .identity();
    NodeIdentity authority = NodeIdentity.generate();
    NodeIdentity origin = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    NodeIdentity unauthorized = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination,
        unauthorized);
    ExecutorService outputReader = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "synesis-relay-artifact-output");
      thread.setDaemon(true);
      return thread;
    });
    Process relayProcess = null;
    boolean shutdownVerified = false;
    try {
      relayProcess = startRelay(options.launcher(), projectId, origin, destination,
          identityDirectory, workDirectory);
      RelayReadiness readiness = awaitReadiness(relayProcess, outputReader);
      if (!relayProcess.isAlive()) {
        throw new IllegalStateException("shipped relay exited before acceptance: "
            + readiness.output());
      }
      if (!relayIdentity.nodeId().equals(readiness.nodeId())) {
        throw new IllegalStateException("shipped relay reported an unexpected identity");
      }

      InetSocketAddress address = new InetSocketAddress("127.0.0.1", readiness.port());
      CompletableFuture<OverlayForwardingFrame> destinationInbound = new CompletableFuture<>();
      CompletableFuture<OverlayForwardingFrame> originInbound = new CompletableFuture<>();
      try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
          relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), destinationInbound::complete);
          OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), originInbound::complete);
          OverlayRelayClient unauthorizedClient = new OverlayRelayClient(unauthorized, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
          })) {
        destinationClient.connect().toCompletableFuture()
            .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        originClient.connect().toCompletableFuture()
            .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        verifyUnauthorizedClientRejected(unauthorizedClient);

        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
            destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(),
            destination, membership);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement,
            response.response(), membership);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        try {
          byte[] plaintext = "shipped relay artifact acceptance".getBytes(StandardCharsets.UTF_8);
          OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(), plaintext);
          OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId,
              destination.nodeId(), envelope.messageId(), 1, envelope.encoded());
          originClient.send(frame).toCompletableFuture()
              .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          OverlayForwardingFrame delivered = destinationInbound.get(OPERATION_TIMEOUT_SECONDS,
              TimeUnit.SECONDS);
          if (delivered.remainingHops() != 0) {
            throw new IllegalStateException("shipped relay did not consume the hop budget");
          }
          byte[] decrypted = receiver.decrypt(OverlayEnvelope.decode(delivered.innerRecord()));
          if (!java.util.Arrays.equals(plaintext, decrypted)) {
            throw new IllegalStateException("shipped relay changed the end-to-end payload");
          }

          byte[] reversePlaintext = "shipped relay reverse acceptance".getBytes(
              StandardCharsets.UTF_8);
          OverlayEnvelope reverseEnvelope = receiver.encrypt(UUID.randomUUID(), reversePlaintext);
          OverlayForwardingFrame reverseFrame = OverlayForwardingFrame.create(projectId,
              origin.nodeId(), reverseEnvelope.messageId(), 1, reverseEnvelope.encoded());
          destinationClient.send(reverseFrame).toCompletableFuture()
              .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
          OverlayForwardingFrame reverseDelivered = originInbound.get(OPERATION_TIMEOUT_SECONDS,
              TimeUnit.SECONDS);
          if (!java.util.Arrays.equals(reversePlaintext,
              sender.decrypt(OverlayEnvelope.decode(reverseDelivered.innerRecord())))) {
            throw new IllegalStateException("shipped relay reverse forwarding failed");
          }
        } finally {
          sender.close();
          receiver.close();
        }
      }
      System.out.println("RELAY_AUTHENTICATED=true");
      System.out.println("RELAY_UNAUTHORIZED_REJECTED=true");
      System.out.println("RELAY_FORWARDING=true");
      System.out.println("RELAY_E2E=true");
    } finally {
      if (relayProcess != null) {
        stopProcessTree(relayProcess);
        shutdownVerified = true;
      }
      outputReader.shutdownNow();
      try (var paths = Files.walk(workDirectory)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (IOException ignored) {
            // The acceptance result is still useful if the OS retains a log file.
          }
        });
      }
    }
    if (!shutdownVerified) {
      throw new IllegalStateException("shipped relay process was not started");
    }
    System.out.println("RELAY_SHUTDOWN=true");
    System.out.println("RELAY_ARTIFACT_ACCEPTANCE=PASS");
  }

  private static void stopProcessTree(Process process) throws InterruptedException {
    List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      descendants.forEach(ProcessHandle::destroyForcibly);
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("shipped relay did not shut down after termination");
      }
    }
    for (ProcessHandle descendant : descendants) {
      if (descendant.isAlive()) {
        descendant.destroyForcibly();
      }
    }
  }

  private static Process startRelay(Path launcher, UUID projectId, NodeIdentity origin,
      NodeIdentity destination, Path identityDirectory, Path workDirectory) throws IOException {
    List<String> arguments = List.of("--project", projectId.toString(), "--node", origin.nodeId(),
        "--node", destination.nodeId(), "--host", "127.0.0.1", "--port", "0", "--workers",
        "2", "--identity-dir", identityDirectory.toString());
    List<String> command = new ArrayList<>();
    if (isWindows()) {
      command.add("cmd.exe");
      command.add("/d");
      command.add("/c");
      command.add("call " + windowsCommandLine(launcher, arguments));
    } else {
      command.add(launcher.toString());
      command.addAll(arguments);
    }
    return new ProcessBuilder(command).directory(workDirectory.toFile()).redirectErrorStream(true)
        .start();
  }

  private static RelayReadiness awaitReadiness(Process process, ExecutorService outputReader)
      throws Exception {
    Future<RelayReadiness> readiness = outputReader.submit(() -> {
      List<String> lines = new ArrayList<>();
      String nodeId = null;
      Integer port = null;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          lines.add(line);
          if (line.startsWith("RELAY_NODE_ID=")) {
            nodeId = line.substring("RELAY_NODE_ID=".length()).trim();
          } else if (line.startsWith("RELAY_PORT=")) {
            port = Integer.valueOf(line.substring("RELAY_PORT=".length()).trim());
          }
          if (nodeId != null && port != null) {
            return new RelayReadiness(nodeId, port, String.join("\n", lines));
          }
        }
      }
      throw new IllegalStateException("shipped relay closed before readiness: "
          + String.join("\n", lines));
    });
    try {
      return readiness.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception failure) {
      process.destroyForcibly();
      throw failure;
    }
  }

  private static void verifyUnauthorizedClientRejected(OverlayRelayClient unauthorizedClient)
      throws Exception {
    try {
      unauthorizedClient.connect().toCompletableFuture()
          .get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      throw new IllegalStateException("unauthorized relay client was accepted");
    } catch (ExecutionException expected) {
      // The relay must reject a validly signed but non-allowlisted node.
    }
  }

  private static OverlayMembershipSnapshot membership(UUID projectId, NodeIdentity authority,
      NodeIdentity... additional) throws GeneralSecurityException {
    List<OverlayMembershipSnapshot.Member> members = new ArrayList<>();
    members.add(new OverlayMembershipSnapshot.Member(authority.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE, authority.publicKeyEncoded()));
    for (NodeIdentity identity : additional) {
      members.add(new OverlayMembershipSnapshot.Member(identity.nodeId(),
          OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded()));
    }
    Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    return OverlayMembershipSnapshot.create(projectId, 1, issuedAt, issuedAt.plusSeconds(300),
        List.copyOf(members), authority);
  }

  private static String windowsCommandLine(Path launcher, List<String> arguments) {
    StringBuilder command = new StringBuilder();
    command.append('"').append(launcher).append('"');
    for (String argument : arguments) {
      if (argument.indexOf('"') >= 0) {
        throw new IllegalArgumentException("acceptance argument contains a quote");
      }
      command.append(" \"").append(argument).append('"');
    }
    return command.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  private record RelayReadiness(String nodeId, int port, String output) {
  }

  private record Options(Path launcher) {

    private static Options parse(String[] arguments) {
      if (arguments.length != 2 || !"--launcher".equals(arguments[0])
          || arguments[1].isBlank()) {
        throw new IllegalArgumentException("usage: RelayArtifactAcceptanceMain --launcher PATH");
      }
      Path launcher = Path.of(arguments[1]).toAbsolutePath().normalize();
      if (!Files.isRegularFile(launcher)) {
        throw new IllegalArgumentException("relay launcher is missing: " + launcher);
      }
      return new Options(launcher);
    }
  }
}
