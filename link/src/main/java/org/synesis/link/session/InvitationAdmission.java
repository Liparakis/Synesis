package org.synesis.link.session;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.link.protocol.CompactBootstrapInvitation;
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
