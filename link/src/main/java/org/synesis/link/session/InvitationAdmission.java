package org.synesis.link.session;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.LinkCompatibility;
import org.synesis.link.protocol.HandshakeTranscript;

/**
 * Single-use host admission state shared by per-connection handshake state.
 */
public final class InvitationAdmission implements AutoCloseable {

  /**
   * Maximum time one invitation reservation may remain unconsumed.
   */
  static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(15);
  private final UUID sessionId;
  private final byte[] capability;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      runnable -> {
        Thread thread = new Thread(runnable, "synesis-link-invite-reservation");
        thread.setDaemon(true);
        return thread;
      });
  private static final Object AVAILABLE = new Object();
  private static final Object CONSUMED = new Object();
  /** {@link #AVAILABLE}, {@link #CONSUMED}, or the {@link Reservation} that currently holds it. */
  private final AtomicReference<Object> state = new AtomicReference<>(AVAILABLE);
  private final AtomicReference<Consumer<LinkCompatibility.Mismatch>> incompatiblePeer = new AtomicReference<>();
  private final AtomicReference<Returning> returning = new AtomicReference<>();

  private record Returning(Predicate<String> peers, Consumer<PeerSession> delivery) {
  }

  /**
   * Sets who is told, once per invitation, that a connection came from a build with another
   * compatibility number. That connection is refused before it is authenticated, so it does not end
   * the wait for the real joiner; the listener exists so the host's user still learns of it.
   *
   * @param listener receives the first such mismatch
   */
  public void onIncompatiblePeer(Consumer<LinkCompatibility.Mismatch> listener) {
    incompatiblePeer.set(listener);
  }

  /**
   * Reports a connection refused for its compatibility number; only the first report is passed on.
   *
   * @param mismatch both numbers
   */
  public void incompatiblePeer(LinkCompatibility.Mismatch mismatch) {
    Consumer<LinkCompatibility.Mismatch> listener = incompatiblePeer.getAndSet(null);
    if (listener != null) {
      listener.accept(mismatch);
    }
  }

  /**
   * Creates one single-use admission state for a signed invitation capability.
   *
   * @param sessionId  invited session identifier
   * @param capability compact invitation capability bytes
   */
  public InvitationAdmission(UUID sessionId, byte[] capability) {
    this.sessionId = sessionId;
    this.capability = capability.clone();
    if (capability.length != CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES) {
      throw new IllegalArgumentException("invalid invitation capability");
    }
  }

  /**
   * Attempts to reserve the invitation for the supplied authenticated transcript.
   *
   * @param transcript handshake transcript under evaluation
   * @return the reservation token, or empty when the invitation was not reserved
   */
  public Optional<Reservation> reserve(HandshakeTranscript transcript) {
    if (!sessionId.equals(transcript.sessionId()) || !Arrays.equals(capability,
        transcript.invitationCapability())) {
      return Optional.empty();
    }
    Reservation reservation = new Reservation();
    if (!state.compareAndSet(AVAILABLE, reservation)) {
      return Optional.empty();
    }
    reservation.expiry = scheduler.schedule(() -> state.compareAndSet(reservation, AVAILABLE),
        RESERVATION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    return Optional.of(reservation);
  }

  /**
   * Lets a peer that already used this invitation connect again without one, for as long as the
   * caller's rule names it. The invitation is the only way in for a peer nobody has named; this is
   * the way back in for a peer the project already knows (ADR-0098: a dropped session is redialed).
   *
   * <p>The rule is asked twice per connection, once for the identity a handshake claims and once for
   * the identity its proofs verified, and is read afresh each time, so a peer removed in between is
   * refused. It is never asked while the invitation is unused: until a first peer authenticated
   * with the capability, nobody is admitted without it.
   *
   * @param peers    whether a node ID is a peer that may come back
   * @param delivery receives each session admitted this way, on the connection's event loop; it must
   *                 not block
   */
  public void admitReturning(Predicate<String> peers, Consumer<PeerSession> delivery) {
    returning.set(new Returning(Objects.requireNonNull(peers, "peers"),
        Objects.requireNonNull(delivery, "delivery")));
  }

  /**
   * Tells whether a connection claiming this node ID may be admitted without the capability.
   *
   * @param remoteNodeId node ID the connection claims or has proved
   * @return true only after the invitation was consumed, with a rule installed that names the node
   */
  public boolean admitsReturning(String remoteNodeId) {
    Returning rule = returning.get();
    return rule != null && state.get() == CONSUMED && rule.peers().test(remoteNodeId);
  }

  /**
   * Hands a session admitted without the capability to its owner.
   *
   * @param session the authenticated session
   */
  public void returned(PeerSession session) {
    Returning rule = returning.get();
    if (rule != null) {
      rule.delivery().accept(session);
    }
  }

  /**
   * Marks the invitation as consumed after peer authentication succeeds.
   *
   * @param reservation the reservation returned to the authenticating attempt
   * @throws IllegalStateException when that reservation no longer holds the invitation
   */
  public void authenticated(Reservation reservation) {
    if (!state.compareAndSet(reservation, CONSUMED)) {
      throw new IllegalStateException("invitation admission is no longer reserved");
    }
    reservation.cancelExpiry();
  }

  /**
   * Releases a reservation that failed before peer authentication completed. A reservation that
   * expired, was already released, or was consumed leaves the invitation untouched, so a stale
   * release can never free a later attempt's reservation.
   *
   * @param reservation the reservation returned to the failing attempt
   */
  public void releaseBeforeAuthentication(Reservation reservation) {
    if (state.compareAndSet(reservation, AVAILABLE)) {
      reservation.cancelExpiry();
    }
  }

  @Override
  public void close() {
    if (state.get() instanceof Reservation held) {
      held.cancelExpiry();
    }
    scheduler.shutdownNow();
  }

  /**
   * Identity of one attempt's hold on the invitation; only the attempt that received it can
   * consume or release it.
   */
  public static final class Reservation {

    private volatile ScheduledFuture<?> expiry;

    private Reservation() {
    }

    private void cancelExpiry() {
      ScheduledFuture<?> value = expiry;
      if (value != null) {
        value.cancel(false);
      }
    }
  }
}
