package org.synesis.link.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests candidate policy filtering, mapped-address normalization, and pair ranking. */
final class CandidateNormalizationTest {

    @Test
    void normalizationDeduplicatesMappedAddressesAndKeepsBestPriority() throws Exception {
        Candidate first = new Candidate(CandidateType.MANUAL,
                InetAddress.getByName("::ffff:192.0.2.10"), 4433, 20);
        Candidate better = new Candidate(CandidateType.MANUAL,
                InetAddress.getByName("192.0.2.10"), 4433, 5);

        List<Candidate> normalized = CandidateNormalizer.normalize(List.of(first, better),
                CandidateGatheringPolicy.defaults());

        assertEquals(1, normalized.size());
        assertEquals(5, normalized.get(0).priority());
        assertEquals(4, normalized.get(0).address().getAddress().length);
    }

    @Test
    void unsafeAddressesAndRelayPairsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CandidateNormalizer.normalize(List.of(
                new Candidate(CandidateType.MANUAL, InetAddress.getByName("0.0.0.0"), 4433, 1),
                new Candidate(CandidateType.MANUAL, InetAddress.getByName("224.0.0.1"), 4433, 1)),
                CandidateGatheringPolicy.defaults()));
        Candidate relay = new Candidate(CandidateType.RELAY, InetAddress.getByName("192.0.2.1"), 4433, 1);
        assertEquals(0, CandidateNormalizer.normalize(List.of(relay), CandidateGatheringPolicy.defaults()).size());
    }

    @Test
    void pairRankingAndCompatibilityAreDeterministic() throws Exception {
        Candidate local = new Candidate(CandidateType.LAN, InetAddress.getByName("192.0.2.1"), 4433, 10);
        Candidate remote = new Candidate(CandidateType.MANUAL, InetAddress.getByName("192.0.2.2"), 4433, 10);
        Candidate ipv6 = new Candidate(CandidateType.IPV6, InetAddress.getByName("2001:db8::2"), 4433, 1);
        List<CandidatePair> pairs = CandidatePairs.generate(List.of(local, ipv6), List.of(remote), 8);

        assertEquals(1, pairs.size());
        assertTrue(pairs.get(0).identifier().startsWith("LAN/MANUAL/h"));
        assertTrue(!pairs.get(0).identifier().contains("192.0.2.1"));
        assertEquals(pairs, CandidatePairs.generate(List.of(local, ipv6), List.of(remote), 8));
    }
}
