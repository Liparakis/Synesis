package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.protocol.ProtocolVersion;

/**
 * A guard with a store keeps what it accepted before the handshake is handed on, and a guard rebuilt from it refuses
 * the same handshake again.
 */
final class ReplayGuardTest {

  private final Set<String> kept = new LinkedHashSet<>();

  private ReplayGuard.Store store() {
    return fingerprints -> {
      kept.clear();
      kept.addAll(fingerprints);
    };
  }

  @Test
  void aHandshakeAcceptedBeforeARestartIsRefusedAfterIt() throws Exception {
    NodeIdentity initiator = NodeIdentity.generate();
    NodeIdentity responder = NodeIdentity.generate();
    HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
        UUID.randomUUID(), 4, 9, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, initiator, responder);
    HandshakeProof initiatorProof = SessionAuthenticator.createProof(initiator, transcript, HandshakeRole.INITIATOR);
    HandshakeProof responderProof = SessionAuthenticator.createProof(responder, transcript, HandshakeRole.RESPONDER);

    SessionAuthenticator.establish(responder, initiator.nodeId(), transcript, responderProof, initiatorProof,
        new ReplayGuard(8, Set.of(), store()), Instant.EPOCH);

    assertEquals(1, kept.size());
    ReplayGuard restarted = new ReplayGuard(8, kept, store());
    assertThrows(IllegalStateException.class, () -> SessionAuthenticator.establish(responder, initiator.nodeId(),
        transcript, responderProof, initiatorProof, restarted, Instant.EPOCH));
  }

  @Test
  void theFingerprintIsKeptBeforeAcceptReturns() {
    ReplayGuard guard = new ReplayGuard(8, Set.of(), store());

    assertTrue(guard.accept("a"));
    assertEquals(Set.of("a"), kept);
    assertFalse(guard.accept("a"));
  }

  @Test
  void aFingerprintThatCannotBeKeptIsNotAccepted() {
    boolean[] failing = {true};
    ReplayGuard guard = new ReplayGuard(8, Set.of(), fingerprints -> {
      if (failing[0]) {
        throw new IOException("disk full");
      }
    });

    assertThrows(IllegalStateException.class, () -> guard.accept("a"));
    failing[0] = false;
    assertTrue(guard.accept("a"));
  }

  @Test
  void capacityHoldsAcrossAReload() {
    ReplayGuard first = new ReplayGuard(2, Set.of(), store());
    first.accept("a");
    first.accept("b");

    ReplayGuard reloaded = new ReplayGuard(2, kept, store());

    assertFalse(reloaded.accept("a"));
    assertThrows(IllegalStateException.class, () -> reloaded.accept("c"));
    assertEquals(Set.of("a", "b"), kept);
    assertThrows(IllegalArgumentException.class, () -> new ReplayGuard(1, Set.of("a", "b"), store()));
  }
}
