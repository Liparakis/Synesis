package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProtocolVersion;

/**
 * A joiner on another network keeps dialing until the host's person pastes its answer, which can be minutes later
 * (ADR-0097). Only the attempt the joiner is waiting on may use the invitation: an attempt it gave up on in an earlier
 * round, which the host's firewall let through once the host sent toward the joiner, would use up the single-use
 * invitation on a connection nobody completes, and the joiner's live attempt would then be refused.
 */
@Timeout(120)
final class LateAnswerTest {

  @Test
  void anAttemptTheJoinerGaveUpOnNeverUsesTheInvitationWhenTheAnswerIsPastedLate() throws Exception {
    List<OnboardingEvent> hostEvents = new CopyOnWriteArrayList<>();
    List<OnboardingEvent> joinEvents = new CopyOnWriteArrayList<>();
    Path hostProfile = Files.createTempDirectory("synesis-late-host");
    Onboarding host = new Onboarding(hostProfile, hostEvents::add);
    try (Onboarding.PreparedHost hosting = host.createRetainedInvitation(null, false, null, session -> { }, peer -> false);
        Firewall firewall = new Firewall(hosting.listeningPort())) {
      CompletableFuture<Void> hostWait = CompletableFuture.runAsync(() -> {
        try {
          hosting.awaitConnectionAndKeepAlive();
        } catch (OnboardingFailure failure) {
          throw new IllegalStateException(failure.code().name(), failure);
        }
      });
      Onboarding joiner = new Onboarding(Files.createTempDirectory("synesis-late-join"), joinEvents::add);
      try (Onboarding.PreparedJoin join = joiner.importRetainedInvitation(firewall.behind(hosting.invitationLink(), hostProfile), null, session -> { })) {
        CompletableFuture<Void> joined = CompletableFuture.runAsync(() -> {
          try {
            join.connectAndKeepAlive();
          } catch (OnboardingFailure failure) {
            throw new IllegalStateException(failure.code().name(), failure);
          }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while ((joinEvents.stream().noneMatch(event -> event.type() == OnboardingEventType.WAITING_FOR_HOST)
            || firewall.connections() < 2) && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        assertTrue(firewall.connections() >= 2, "the joiner gave up on its first round and dials again");

        // The person pastes the answer: the host sends toward the joiner, and its firewall lets the joiner in. The
        // packets of the first round's connection are the ones that arrive.
        hosting.importAnswer(join.answerLink());
        firewall.letInOnlyTheFirstConnection();
        Thread.sleep(20_000);
        assertFalse(hostEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.PEER_CONNECTED),
            "a connection of a round the joiner gave up on was not admitted: " + hostEvents);
        assertFalse(hostWait.isDone(), () -> "the host still waits for the joiner, but its wait ended: "
            + hostWait.handle((ignored, failure) -> String.valueOf(failure)).join());

        firewall.letEverythingIn();
        joined.get(60, TimeUnit.SECONDS);
        hostWait.get(30, TimeUnit.SECONDS);
        assertEquals(1, hostEvents.stream().filter(event -> event.type() == OnboardingEventType.PEER_CONNECTED).count());
      }
    }
  }

  /**
   * The host's side of a firewall, in front of the host's port: the joiner dials it in place of the host. Until it
   * is opened it drops everything from the joiner; it can then let in only the QUIC connection the joiner opened
   * first, or everything.
   */
  private static final class Firewall implements AutoCloseable {

    private static final int CLOSED = 0;
    private static final int FIRST_CONNECTION = 1;
    private static final int OPEN = 2;
    private final DatagramSocket outside = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    private final DatagramSocket inside = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    private final List<byte[]> initialConnectionIds = new CopyOnWriteArrayList<>();
    private volatile int state = CLOSED;
    private volatile SocketAddress joiner;

    Firewall(int hostPort) throws IOException {
      InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), hostPort);
      Thread.ofVirtual().start(() -> pump(outside, inside, packet -> {
        joiner = packet.getSocketAddress();
        return admitted(packet) ? hostAddress : null;
      }));
      Thread.ofVirtual().start(() -> pump(inside, outside, packet -> joiner));
    }

    /** Rewrites the host's invitation to name this firewall, signed by the host, with the same capability. */
    String behind(String invitationLink, Path hostProfile) throws Exception {
      CompactBootstrapInvitation invitation = CompactBootstrapInvitation.fromShareLink(invitationLink);
      return CompactBootstrapInvitation.createMinimized(new IdentityBootstrap(hostProfile).loadOrCreate().identity(),
          ProtocolVersion.V1, invitation.issuedAt(), invitation.expiresAt(), invitation.capability(),
          List.of(new Candidate(CandidateType.LAN, InetAddress.getLoopbackAddress(), outside.getLocalPort(), 0)))
          .shareLink();
    }

    int connections() {
      return initialConnectionIds.size();
    }

    void letInOnlyTheFirstConnection() {
      state = FIRST_CONNECTION;
    }

    void letEverythingIn() {
      state = OPEN;
    }

    private boolean admitted(DatagramPacket packet) {
      byte[] connection = clientConnectionId(packet);
      if (connection != null && initialConnectionIds.stream().noneMatch(known -> Arrays.equals(known, connection))) {
        initialConnectionIds.add(connection);
      }
      return switch (state) {
        case OPEN -> true;
        // A short-header packet belongs to a connection that is past its handshake, and only the first one can be.
        case FIRST_CONNECTION -> connection == null || Arrays.equals(connection, initialConnectionIds.getFirst());
        default -> false;
      };
    }

    /**
     * The connection ID the joiner chose for itself, which every long-header packet it sends carries as its source
     * connection ID (QUIC v1), or {@code null} for a short-header packet.
     */
    private static byte[] clientConnectionId(DatagramPacket packet) {
      byte[] data = packet.getData();
      int offset = packet.getOffset();
      int end = offset + packet.getLength();
      if (packet.getLength() < 7 || (data[offset] & 0x80) == 0) {
        return null;
      }
      int source = offset + 6 + (data[offset + 5] & 0xFF);
      if (source >= end) {
        return null;
      }
      int length = data[source] & 0xFF;
      return source + 1 + length > end ? null : Arrays.copyOfRange(data, source + 1, source + 1 + length);
    }

    private static void pump(DatagramSocket from, DatagramSocket via, java.util.function.Function<DatagramPacket, SocketAddress> target) {
      byte[] buffer = new byte[65_536];
      while (!from.isClosed()) {
        try {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          from.receive(packet);
          SocketAddress to = target.apply(packet);
          if (to != null) {
            via.send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength(), to));
          }
        } catch (IOException closed) {
          return;
        }
      }
    }

    @Override
    public void close() {
      outside.close();
      inside.close();
    }
  }
}
