package org.synesis.link.candidate;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Enumerates up interfaces without opening sockets or changing network state. */
public final class LocalInterfaceCandidateProvider implements CandidateProvider {
    private final String id;
    private final int port;
    private final int priority;

    /**
     * Creates a local-interface provider for one validated UDP port.
     *
     * @param id stable provider identifier
     * @param port advertised UDP port
     * @param priority candidate priority
     */
    public LocalInterfaceCandidateProvider(String id, int port, int priority) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("provider ID is blank");
        if (port < 1 || port > 65_535 || priority < 0) throw new IllegalArgumentException("invalid local candidate values");
        this.id = id;
        this.port = port;
        this.priority = priority;
    }

    @Override public String id() { return id; }
    @Override public Set<CandidateType> supportedTypes() { return Set.of(CandidateType.LAN, CandidateType.IPV6); }

    @Override
    public CompletableFuture<List<Candidate>> gather(CandidateCancellation cancellation) {
        List<Candidate> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (cancellation.isCancelled()) break;
                if (!network.isUp()) continue;
                for (InetAddress address : java.util.Collections.list(network.getInetAddresses())) {
                    if (cancellation.isCancelled()) break;
                    if (address.isAnyLocalAddress() || address.isMulticastAddress()
                            || address.isLoopbackAddress()) continue;
                    if (address instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress()
                            && ipv6.getScopeId() == 0) continue;
                    CandidateType type = address instanceof Inet6Address ipv6 && !ipv6.isSiteLocalAddress()
                            ? CandidateType.IPV6 : CandidateType.LAN;
                    result.add(new Candidate(type, address, port, priority));
                }
            }
            return CompletableFuture.completedFuture(result);
        } catch (java.io.IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
