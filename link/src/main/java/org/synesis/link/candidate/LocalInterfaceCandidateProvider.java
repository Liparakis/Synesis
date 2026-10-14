package org.synesis.link.candidate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Enumerates up interfaces without sending anything or changing network state.
 */
public final class LocalInterfaceCandidateProvider implements CandidateProvider {

  private final String id;
  private final int port;
  private final int priority;

  /**
   * Creates a local-interface provider for one validated UDP port.
   *
   * @param id       stable provider identifier
   * @param port     advertised UDP port
   * @param priority candidate priority
   */
  public LocalInterfaceCandidateProvider(String id, int port, int priority) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("provider ID is blank");
    }
    if (port < 1 || port > 65_535 || priority < 0) {
      throw new IllegalArgumentException("invalid local candidate values");
    }
    this.id = id;
    this.port = port;
    this.priority = priority;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public CompletableFuture<List<Candidate>> gather(CandidateCancellation cancellation) {
    List<Candidate> result = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface network = interfaces.nextElement();
        if (cancellation.isCancelled()) {
          break;
        }
        // An address on the loopback interface need not be a loopback address (WSL binds
        // 10.255.255.254 there), and no other machine can reach it.
        if (!network.isUp() || network.isLoopback()) {
          continue;
        }
        for (InetAddress address : java.util.Collections.list(network.getInetAddresses())) {
          if (cancellation.isCancelled()) {
            break;
          }
          if (address.isAnyLocalAddress() || address.isMulticastAddress()
              || address.isLoopbackAddress()) {
            continue;
          }
          if (address instanceof Inet6Address ipv6
              && (ipv6.isLinkLocalAddress() || !answersFrom(ipv6))) {
            continue;
          }
          CandidateType type = address instanceof Inet6Address ipv6 && !ipv6.isSiteLocalAddress()
              ? CandidateType.IPV6 : CandidateType.LAN;
          result.add(new Candidate(type, address, port, priority));
        }
      }
      return CompletableFuture.completedFuture(result);
    } catch (IOException exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  /**
   * Tells whether this machine sends from {@code address} to a neighbour in the same prefix.
   *
   * <p>The listener is bound to the wildcard address, so the operating system chooses the source
   * of every reply. Where an interface holds a stable and a temporary address in one prefix, as
   * Windows does by default, it chooses the temporary one, and a peer that dialled the stable one
   * drops the reply as coming from a stranger. Connecting a datagram socket selects that source and
   * sends nothing. An address that cannot be probed is kept.
   */
  private static boolean answersFrom(Inet6Address address) {
    byte[] neighbour = address.getAddress();
    neighbour[neighbour.length - 1] ^= 1;
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(InetAddress.getByAddress(neighbour), 9);
      return Arrays.equals(probe.getLocalAddress().getAddress(), address.getAddress());
    } catch (IOException | UncheckedIOException unroutable) {
      return true;
    }
  }
}
