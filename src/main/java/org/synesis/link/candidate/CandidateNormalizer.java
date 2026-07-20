package org.synesis.link.candidate;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates, canonicalizes, and deduplicates runtime candidates.
 */
public final class CandidateNormalizer {
    private CandidateNormalizer() {
    }

    /**
     * Normalizes candidates before pair generation.
     *
     * @param candidates candidate values to validate
     * @param policy     privacy and address policy
     * @return sorted duplicate-free candidates
     * @throws IllegalArgumentException when an unsafe address is supplied
     */
    public static List<Candidate> normalize(Collection<Candidate> candidates, CandidateGatheringPolicy policy) {
        if (candidates == null || policy == null) throw new NullPointerException("candidates/policy");
        if (candidates.size() > policy.maxTotalCandidates()) throw new IllegalArgumentException("too many candidates");
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate source : candidates) {
            if (source == null) throw new IllegalArgumentException("null candidate");
            Candidate candidate = canonical(source, policy);
            if (candidate == null) continue;
            String key = candidate.type() + ":" + HexFormat.of().formatHex(candidate.address().getAddress())
                    + ":" + candidate.port();
            Candidate prior = unique.get(key);
            if (prior == null || candidate.priority() < prior.priority()) unique.put(key, candidate);
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingInt(Candidate::priority)
                .thenComparing(candidate -> candidate.type().name())
                .thenComparing(candidate -> HexFormat.of().formatHex(candidate.address().getAddress()))
                .thenComparingInt(Candidate::port));
        return List.copyOf(result);
    }

    private static Candidate canonical(Candidate source, CandidateGatheringPolicy policy) {
        InetAddress address = source.address();
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isMapped(bytes)) {
            try {
                address = InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16));
            } catch (java.net.UnknownHostException impossible) {
                throw new AssertionError(impossible);
            }
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress())
            throw new IllegalArgumentException("unspecified or multicast candidate");
        if (address.isLoopbackAddress() && !policy.allowLoopback())
            throw new IllegalArgumentException("loopback candidate");
        if (address.isSiteLocalAddress() && !policy.allowPrivate())
            throw new IllegalArgumentException("private candidate");
        if (address instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress() && ipv6.getScopeId() == 0)
            throw new IllegalArgumentException("link-local candidate lacks scope");
        if (source.type() == CandidateType.RELAY) return null;
        if (source.type() == CandidateType.MAPPED_IPV4 && bytes.length != 4)
            throw new IllegalArgumentException("mapped candidate is not IPv4");
        if (source.type() == CandidateType.IPV6 && address.getAddress().length != 16)
            throw new IllegalArgumentException("IPv6 candidate is not IPv6");
        if (source.type() == CandidateType.IPV6 && !policy.allowGlobalIpv6()) return null;
        if (source.type() == CandidateType.MAPPED_IPV4 && !policy.allowMappedIpv4()) return null;
        if (source.type() == CandidateType.SERVER_REFLEXIVE && !policy.allowServerReflexive()) return null;
        return new Candidate(source.type(), address, source.port(), source.priority());
    }

    private static boolean isMapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
