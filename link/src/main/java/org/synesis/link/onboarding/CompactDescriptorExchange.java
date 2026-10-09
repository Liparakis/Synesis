package org.synesis.link.onboarding;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.protocol.AuthenticatedDescriptorExchange;
import org.synesis.link.session.PeerSession;

/**
 * Authenticated descriptor exchange that follows a compact one-way session.
 *
 * <p>This is separate from {@link Onboarding} because it holds the identity and expiry checks that
 * bind the exchanged descriptor to the compact invitation on both the host side (incoming request)
 * and the joiner side (response), independent of how either side set up its transport.
 */
final class CompactDescriptorExchange {

  private CompactDescriptorExchange() {
  }

  static PeerSession.ApplicationStreamHandler compactApplicationHandler(
      AtomicReference<CandidateDescriptor> localDescriptor,
      CompletableFuture<CandidateDescriptor> remoteDescriptor,
      PeerSession.ApplicationStreamHandler delegate) {
    return (remoteNodeId, payload) -> {
      if (!AuthenticatedDescriptorExchange.looksLike(payload)) {
        return delegate == null
            ? CompletableFuture.failedFuture(new IllegalStateException(
                "no application handler is installed"))
            : delegate.handle(remoteNodeId, payload);
      }
      try {
        CandidateDescriptor received = AuthenticatedDescriptorExchange.decodeRequest(payload);
        if (!remoteNodeId.equals(received.nodeId())
            || !received.isValidAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
          throw new IllegalArgumentException("remote descriptor does not match session identity");
        }
        CandidateDescriptor local = localDescriptor.get();
        if (local == null) {
          throw new IllegalStateException("local descriptor is not ready");
        }
        remoteDescriptor.complete(received);
        return CompletableFuture.completedFuture(AuthenticatedDescriptorExchange.response(local));
      } catch (Exception failure) {
        remoteDescriptor.completeExceptionally(failure);
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

  static CandidateDescriptor exchangeDescriptor(PeerSession session,
      CandidateDescriptor localDescriptor, String expectedRemoteNodeId, byte[] expectedRemoteKey,
      Instant expiresAt) throws Exception {
    byte[] response = session.requestApplication(
            AuthenticatedDescriptorExchange.request(localDescriptor))
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
    CandidateDescriptor remote = AuthenticatedDescriptorExchange.decodeResponse(response);
    if (!remote.nodeId().equals(expectedRemoteNodeId)
        || !Arrays.equals(remote.publicKeyEncoded(), expectedRemoteKey)
        || !remote.isValidAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)
        || remote.expiresAt().isAfter(expiresAt)) {
      throw new IllegalArgumentException("remote descriptor is not bound to the invitation");
    }
    return remote;
  }
}
