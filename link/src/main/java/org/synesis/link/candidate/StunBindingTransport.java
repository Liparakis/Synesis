package org.synesis.link.candidate;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

/**
 * Caller-owned transport for one RFC 8489 STUN Binding transaction.
 *
 * <p>An implementation must send and receive on the same UDP socket that the
 * eventual QUIC attempt will use when server-reflexive mapping continuity is required. This
 * interface deliberately does not prescribe a socket stack.
 */
@FunctionalInterface
public interface StunBindingTransport {

  /**
   * Sends one bounded STUN request and returns the complete bounded response.
   *
   * @param server       configured STUN server endpoint
   * @param request      canonical Binding request bytes
   * @param cancellation cooperative cancellation signal
   * @return response bytes
   */
  CompletionStage<byte[]> request(InetSocketAddress server, byte[] request,
      CandidateCancellation cancellation);
}
