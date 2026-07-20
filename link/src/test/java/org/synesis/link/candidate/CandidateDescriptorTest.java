package org.synesis.link.candidate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/** Verifies canonical, bounded, signed candidate descriptors. */
final class CandidateDescriptorTest {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-01-01T01:00:00Z");

    @Test
    void equivalentCandidateOrderProducesIdenticalBytes() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        Candidate lan = new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.1"), 4433, 10);
        Candidate ipv6 = new Candidate(CandidateType.IPV6, InetAddress.getByName("2001:db8::1"), 4433, 20);
        CandidateDescriptor first = CandidateDescriptor.create(identity, ISSUED, EXPIRES, List.of(lan, ipv6));
        CandidateDescriptor second = CandidateDescriptor.create(identity, ISSUED, EXPIRES, List.of(ipv6, lan));

        assertArrayEquals(first.encoded(), second.encoded());
        assertTrue(CandidateDescriptor.decode(first.encoded()).verify());
    }

    @Test
    void tamperingAndExpiryAreRejected() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        Candidate candidate = new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.4"), 9443, 1);
        CandidateDescriptor descriptor = CandidateDescriptor.create(identity, ISSUED, EXPIRES, List.of(candidate));
        byte[] tampered = descriptor.encoded();
        tampered[10] ^= 1;
        assertFalse(CandidateDescriptor.decode(tampered).verify());
        assertTrue(descriptor.isValidAt(ISSUED.plusSeconds(30), CandidateDescriptor.DEFAULT_CLOCK_SKEW));
        assertFalse(descriptor.isValidAt(EXPIRES, CandidateDescriptor.DEFAULT_CLOCK_SKEW));
    }

    @Test
    void duplicateCandidatesNormalizeAndLimitsAreBounded() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        Candidate candidate = new Candidate(CandidateType.LAN, InetAddress.getLoopbackAddress(), 4433, 1);
        CandidateDescriptor descriptor = CandidateDescriptor.create(identity, ISSUED, EXPIRES,
                List.of(candidate, candidate));
        assertTrue(descriptor.candidates().size() == 1);
        assertThrows(IllegalArgumentException.class, () -> new Candidate(CandidateType.LAN,
                InetAddress.getLoopbackAddress(), 0, 1));
    }
}
