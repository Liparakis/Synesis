package org.synesis.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayEnvelope;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayRelay;
import org.synesis.link.overlay.OverlayRelayPolicy;

/**
 * Exercises the standalone relay over real localhost event-loop sockets.
 */
final class OverlayRelayServerTest {

  private static int waitForRelayPort(Path output) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Files.exists(output)) {
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
          if (line.startsWith("RELAY_PORT=")) {
            return Integer.parseInt(line.substring("RELAY_PORT=".length()).trim());
          }
        }
      }
      Thread.sleep(25);
    }
    throw new AssertionError("relay process did not publish a port: " + relayOutput(output));
  }

  private static String relayOutput(Path output) {
    try {
      return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8)
          : "<no output>";
    } catch (java.io.IOException failure) {
      return "<unreadable output: " + failure.getMessage() + ">";
    }
  }

  private static OverlayMembershipSnapshot membership(UUID projectId, NodeIdentity authority,
      NodeIdentity... additional) throws GeneralSecurityException {
    java.util.ArrayList<OverlayMembershipSnapshot.Member> members = new java.util.ArrayList<>();
    members.add(new OverlayMembershipSnapshot.Member(authority.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE,
        authority.publicKeyEncoded()));
    for (NodeIdentity identity : additional) {
      members.add(new OverlayMembershipSnapshot.Member(identity.nodeId(),
          OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded()));
    }
    Instant issuedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    return OverlayMembershipSnapshot.create(projectId, 1, issuedAt, issuedAt.plusSeconds(300),
        List.copyOf(members),
        authority);
  }

  @Test
  void authenticatesAllowsLiveOpaqueBidirectionalPathAndRejectsUnauthorizedClient()
      throws Exception {
    NodeIdentity relayIdentity = NodeIdentity.generate();
    NodeIdentity authority = NodeIdentity.generate();
    NodeIdentity origin = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    NodeIdentity unauthorized = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination,
        unauthorized);
    OverlayRelayPolicy policy = new OverlayRelayPolicy(Map.of(projectId,
        Set.of(origin.nodeId(), destination.nodeId())), 8, 8, 64);
    OverlayRelay core = new OverlayRelay(policy);
    try (OverlayRelayServer server = new OverlayRelayServer(new InetSocketAddress("127.0.0.1", 0),
        relayIdentity, core, 2)) {
      InetSocketAddress address = server.start();
      CompletableFuture<OverlayForwardingFrame> inbound = new CompletableFuture<>();
      CompletableFuture<OverlayForwardingFrame> originInbound = new CompletableFuture<>();
      try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
          relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), inbound::complete);
          OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), originInbound::complete);
          OverlayRelayClient unauthorizedClient = new OverlayRelayClient(unauthorized, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
          })) {
        destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class,
            () -> unauthorizedClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS));

        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
            destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(),
            destination,
            membership);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement,
            response.response(),
            membership);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        byte[] plaintext = "localhost relay remains opaque".getBytes(StandardCharsets.UTF_8);
        OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(), plaintext);
        OverlayForwardingFrame frame = OverlayForwardingFrame.create(projectId,
            destination.nodeId(),
            envelope.messageId(), 1, envelope.encoded());
        originClient.send(frame).toCompletableFuture().get(10, TimeUnit.SECONDS);
        OverlayForwardingFrame delivered = inbound.get(10, TimeUnit.SECONDS);
        assertEquals(0, delivered.remainingHops());
        assertFalse(java.util.Arrays.equals(plaintext, delivered.innerRecord()));
        assertArrayEquals(plaintext,
            receiver.decrypt(OverlayEnvelope.decode(delivered.innerRecord())));
        assertThrows(ExecutionException.class,
            () -> originClient.send(frame).toCompletableFuture().get(10, TimeUnit.SECONDS));

        byte[] reversePlaintext = "localhost relay remains bidirectional".getBytes(
            StandardCharsets.UTF_8);
        OverlayEnvelope reverseEnvelope = receiver.encrypt(UUID.randomUUID(), reversePlaintext);
        OverlayForwardingFrame reverseFrame = OverlayForwardingFrame.create(projectId,
            origin.nodeId(),
            reverseEnvelope.messageId(), 1, reverseEnvelope.encoded());
        destinationClient.send(reverseFrame).toCompletableFuture().get(10, TimeUnit.SECONDS);
        OverlayForwardingFrame reverseDelivered = originInbound.get(10, TimeUnit.SECONDS);
        assertEquals(0, reverseDelivered.remainingHops());
        assertArrayEquals(reversePlaintext, sender.decrypt(OverlayEnvelope.decode(
            reverseDelivered.innerRecord())));
        sender.close();
        receiver.close();
      }
    }
  }

  @Test
  void forwardsConcurrentFramesWithoutCrossRecipientConfusion() throws Exception {
    NodeIdentity relayIdentity = NodeIdentity.generate();
    NodeIdentity authority = NodeIdentity.generate();
    NodeIdentity origin = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination);
    OverlayRelay core = new OverlayRelay(new OverlayRelayPolicy(Map.of(projectId,
        Set.of(origin.nodeId(), destination.nodeId())), 8, 32, 128));
    try (OverlayRelayServer server = new OverlayRelayServer(new InetSocketAddress("127.0.0.1", 0),
        relayIdentity, core, 2)) {
      InetSocketAddress address = server.start();
      java.util.concurrent.ConcurrentLinkedQueue<OverlayForwardingFrame> frames =
          new java.util.concurrent.ConcurrentLinkedQueue<>();
      try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
          relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), frames::add);
          OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), ignored -> {
          })) {
        destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
            destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(),
            destination,
            membership);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement,
            response.response(),
            membership);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        for (int index = 0; index < 8; index++) {
          OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(),
              ("frame-" + index).getBytes(StandardCharsets.UTF_8));
          originClient.send(OverlayForwardingFrame.create(projectId, destination.nodeId(),
              envelope.messageId(), 1, envelope.encoded())).toCompletableFuture().get(10,
              TimeUnit.SECONDS);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (frames.size() < 8 && System.nanoTime() < deadline) {
          Thread.yield();
        }
        assertEquals(8, frames.size());
        for (OverlayForwardingFrame frame : frames) {
          assertTrue(receiver.decrypt(OverlayEnvelope.decode(frame.innerRecord())).length > 0);
        }
        sender.close();
        receiver.close();
      }
    }
  }

  @Test
  void standaloneRelayMainProcessForwardsBidirectionally() throws Exception {
    Path directory = Files.createTempDirectory("synesis-relay-main-process");
    Path identityDirectory = directory.resolve("relay-identity");
    Path output = directory.resolve("relay.out");
    NodeIdentity relayIdentity = new IdentityBootstrap(identityDirectory).loadOrCreate().identity();
    NodeIdentity authority = NodeIdentity.generate();
    NodeIdentity origin = NodeIdentity.generate();
    NodeIdentity destination = NodeIdentity.generate();
    UUID projectId = UUID.randomUUID();
    OverlayMembershipSnapshot membership = membership(projectId, authority, origin, destination);
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "java.exe" : "java").toString();
    Process relayProcess = null;
    try {
      relayProcess = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp",
          System.getProperty("java.class.path"), RelayMain.class.getName(), "--project",
          projectId.toString(), "--node", origin.nodeId(), "--node", destination.nodeId(), "--host",
          "127.0.0.1", "--port", "0", "--workers", "2", "--identity-dir",
          identityDirectory.toString()).redirectErrorStream(true).redirectOutput(output.toFile())
          .start();
      int port = waitForRelayPort(output);
      assertTrue(relayProcess.isAlive(), relayOutput(output));
      InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
      CompletableFuture<OverlayForwardingFrame> destinationInbound = new CompletableFuture<>();
      CompletableFuture<OverlayForwardingFrame> originInbound = new CompletableFuture<>();
      try (OverlayRelayClient destinationClient = new OverlayRelayClient(destination, address,
          relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), destinationInbound::complete);
          OverlayRelayClient originClient = new OverlayRelayClient(origin, address,
              relayIdentity.nodeId(), relayIdentity.publicKeyEncoded(), originInbound::complete)) {
        destinationClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        originClient.connect().toCompletableFuture().get(10, TimeUnit.SECONDS);
        OverlayKeyAgreement.Initiator agreement = OverlayKeyAgreement.start(projectId,
            destination.nodeId(), UUID.randomUUID(), membership.revision(), origin);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(agreement.init(),
            destination,
            membership);
        OverlayKeyAgreement.E2eSession sender = OverlayKeyAgreement.finish(agreement,
            response.response(),
            membership);
        OverlayKeyAgreement.E2eSession receiver = response.session();
        byte[] forwardPlaintext = "standalone relay A-to-C".getBytes(StandardCharsets.UTF_8);
        OverlayEnvelope forwardEnvelope = sender.encrypt(UUID.randomUUID(), forwardPlaintext);
        originClient.send(OverlayForwardingFrame.create(projectId, destination.nodeId(),
                forwardEnvelope.messageId(), 1, forwardEnvelope.encoded())).toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        OverlayForwardingFrame deliveredForward = destinationInbound.get(10, TimeUnit.SECONDS);
        assertArrayEquals(forwardPlaintext,
            receiver.decrypt(OverlayEnvelope.decode(deliveredForward.innerRecord())));

        byte[] reversePlaintext = "standalone relay C-to-A".getBytes(StandardCharsets.UTF_8);
        OverlayEnvelope reverseEnvelope = receiver.encrypt(UUID.randomUUID(), reversePlaintext);
        destinationClient.send(OverlayForwardingFrame.create(projectId, origin.nodeId(),
                reverseEnvelope.messageId(), 1, reverseEnvelope.encoded())).toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        OverlayForwardingFrame deliveredReverse = originInbound.get(10, TimeUnit.SECONDS);
        assertArrayEquals(reversePlaintext, sender.decrypt(OverlayEnvelope.decode(
            deliveredReverse.innerRecord())));
        sender.close();
        receiver.close();
      }
    } finally {
      if (relayProcess != null && relayProcess.isAlive()) {
        relayProcess.destroy();
        if (!relayProcess.waitFor(5, TimeUnit.SECONDS)) {
          relayProcess.destroyForcibly();
        }
      }
      try (var paths = Files.walk(directory)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (java.io.IOException ignored) {
          }
        });
      }
    }
  }
}
