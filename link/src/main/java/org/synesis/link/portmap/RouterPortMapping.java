package org.synesis.link.portmap;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * A UDP port the home router forwards to this machine, asked for over UPnP. Closing it asks the
 * router to remove the forward.
 *
 * <p>Two home routers both drop traffic their own side did not start, and on the common Linux-based
 * ones an early packet from the other side even takes the port the host would answer from. A
 * forward the router was asked for comes before both, so a joiner reaches the host directly. It
 * changes a setting on the user's router, so it is only asked for when the user chose to.
 *
 * @since 1.0
 */
public final class RouterPortMapping implements AutoCloseable {

  /** How long the router keeps the forward if this process ends without removing it. */
  static final Duration LEASE = Duration.ofHours(1);
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final UpnpGateway gateway;
  private final InetSocketAddress external;

  private RouterPortMapping(UpnpGateway gateway, InetSocketAddress external) {
    this.gateway = gateway;
    this.external = external;
  }

  /**
   * Finds the router and asks it to forward the same public UDP port to {@code localPort}.
   *
   * @param localPort the listening port on this machine
   * @return the open forward
   * @throws IOException with a short reason when no router answers, the router refuses, or the
   *                     router's own address is not public, as behind a second router or an
   *                     operator's NAT, where a forward cannot be reached from the Internet
   */
  public static RouterPortMapping open(int localPort) throws IOException {
    UpnpGateway gateway = UpnpGateway.discover(TIMEOUT)
        .orElseThrow(() -> new IOException("no UPnP router answered"));
    return open(gateway, localPort);
  }

  static RouterPortMapping open(UpnpGateway gateway, int localPort) throws IOException {
    InetAddress external = gateway.externalAddress();
    if (!(external instanceof Inet4Address) || !isPublic(external)) {
      throw new IOException("the router's address is not public");
    }
    gateway.addUdpMapping(localPort, localAddressToward(gateway.router()), localPort, LEASE);
    return new RouterPortMapping(gateway, new InetSocketAddress(external, localPort));
  }

  /** The address of this machine the router sees, found by connecting a socket, which sends nothing. */
  private static InetAddress localAddressToward(InetAddress router) throws IOException {
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(router, 1900);
      return probe.getLocalAddress();
    }
  }

  /**
   * Whether an IPv4 address is reachable from the Internet: not private, shared (carrier NAT),
   * loopback, link-local, or reserved.
   */
  static boolean isPublic(InetAddress address) {
    byte[] bytes = address.getAddress();
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    return !(address.isSiteLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isAnyLocalAddress()
        || first == 0 || first >= 224
        || first == 100 && second >= 64 && second <= 127);
  }

  /**
   * Returns the public address and port the router forwards.
   *
   * @return public endpoint
   */
  public InetSocketAddress external() {
    return external;
  }

  /** Asks the router to remove the forward; a router that does not answer keeps it until the lease ends. */
  @Override
  public void close() {
    try {
      gateway.deleteUdpMapping(external.getPort());
    } catch (IOException unanswered) {
      // The lease removes it.
    }
  }

  @Override
  public String toString() {
    return "RouterPortMapping[" + Objects.toString(external.getPort()) + "]";
  }
}
