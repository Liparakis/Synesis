package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** Verifies the bounded in-band descriptor exchange envelope. */
final class AuthenticatedDescriptorExchangeTest {

  @Test
  void roundTripsSignedRequestAndResponseWithoutChangingDescriptorBytes() throws Exception {
    NodeIdentity identity = NodeIdentity.generate();
    CandidateDescriptor descriptor = descriptor(identity);

    byte[] request = AuthenticatedDescriptorExchange.request(descriptor);
    byte[] response = AuthenticatedDescriptorExchange.response(descriptor);

    assertTrue(AuthenticatedDescriptorExchange.looksLike(request));
    assertArrayEquals(descriptor.encoded(),
        AuthenticatedDescriptorExchange.decodeRequest(request).encoded());
    assertArrayEquals(descriptor.encoded(),
        AuthenticatedDescriptorExchange.decodeResponse(response).encoded());
    assertTrue(request.length <= AuthenticatedDescriptorExchange.MAX_BYTES);
  }

  @Test
  void rejectsWrongEnvelopeKindAndTrailingBytes() throws Exception {
    CandidateDescriptor descriptor = descriptor(NodeIdentity.generate());
    byte[] request = AuthenticatedDescriptorExchange.request(descriptor);
    byte[] trailing = Arrays.copyOf(request, request.length + 1);
    assertThrows(java.io.IOException.class,
        () -> AuthenticatedDescriptorExchange.decodeRequest(trailing));
    assertThrows(java.io.IOException.class,
        () -> AuthenticatedDescriptorExchange.decodeResponse(request));
  }

  @Test
  void leavesSignatureValidationToTheDescriptorSecurityBoundary() throws Exception {
    CandidateDescriptor descriptor = descriptor(NodeIdentity.generate());
    byte[] encoded = AuthenticatedDescriptorExchange.request(descriptor);
    encoded[encoded.length - 1] ^= 1;

    CandidateDescriptor decoded = AuthenticatedDescriptorExchange.decodeRequest(encoded);
    assertFalse(decoded.verify());
  }

  private static CandidateDescriptor descriptor(NodeIdentity identity) throws Exception {
    Instant issued = Instant.parse("2026-09-13T09:00:00Z");
    return CandidateDescriptor.create(identity, issued, issued.plus(Duration.ofMinutes(10)),
        List.of(new Candidate(CandidateType.MANUAL,
            InetAddress.getByName("198.51.100.10"), 44_001, 1)));
  }
}
