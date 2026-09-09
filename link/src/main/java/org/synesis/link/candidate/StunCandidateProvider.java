package org.synesis.link.candidate;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Optional server-reflexive candidate provider backed by a caller-owned STUN transport.
 *
 * <p>The provider owns no socket and therefore cannot accidentally move the
 * mapping to a second UDP socket. Configure it only when the supplied transport is tied to the Link
 * QUIC socket.
 */
public final class StunCandidateProvider implements CandidateProvider {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final InetSocketAddress server;
  private final int priority;
  private final StunBindingTransport transport;

  /**
   * Creates a bounded STUN candidate provider.
   *
   * @param server    configured STUN server endpoint
   * @param priority  non-negative candidate priority
   * @param transport caller-owned same-socket transport
   */
  public StunCandidateProvider(InetSocketAddress server, int priority,
      StunBindingTransport transport) {
    this.server = Objects.requireNonNull(server, "STUN server");
    this.transport = Objects.requireNonNull(transport, "STUN transport");
    if (server.isUnresolved() || server.getPort() < 1 || priority < 1) {
      throw new IllegalArgumentException("STUN server or candidate priority is invalid");
    }
    this.priority = priority;
  }

  /**
   * @return stable non-sensitive provider identifier
   */
  @Override
  public String id() {
    return "stun";
  }

  /**
   * @return the server-reflexive candidate type
   */
  @Override
  public java.util.Set<CandidateType> supportedTypes() {
    return java.util.Set.of(CandidateType.SERVER_REFLEXIVE);
  }

  /**
   * Starts one Binding transaction.
   *
   * @param cancellation cooperative cancellation signal
   * @return one server-reflexive candidate
   */
  @Override
  public CompletionStage<List<Candidate>> gather(CandidateCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    if (cancellation.isCancelled()) {
      return CompletableFuture.failedFuture(new CancellationException("STUN gathering cancelled"));
    }
    byte[] transactionId = new byte[StunBindingMessage.TRANSACTION_ID_BYTES];
    RANDOM.nextBytes(transactionId);
    return transport.request(server, StunBindingMessage.bindingRequest(transactionId), cancellation)
        .thenApply(response -> {
          if (cancellation.isCancelled()) {
            throw new CancellationException("STUN gathering cancelled");
          }
          try {
            InetSocketAddress mapped = StunBindingMessage.xorMappedAddress(response, transactionId);
            return List.of(new Candidate(CandidateType.SERVER_REFLEXIVE, mapped.getAddress(),
                mapped.getPort(), priority));
          } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("invalid STUN response", exception);
          }
        });
  }
}
