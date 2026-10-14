package org.synesis.link.candidate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests which addresses of the machine running the test are offered to a peer.
 */
final class LocalInterfaceCandidateProviderTest {

  /**
   * Bites only where the loopback interface holds a non-loopback address, as it does under WSL
   * with host networking; elsewhere the loopback interface offers nothing either way.
   */
  @Test
  void offersNoAddressOfTheLoopbackInterface() throws Exception {
    for (Candidate candidate : new LocalInterfaceCandidateProvider("local", 4433, 0)
        .gather(() -> false)
        .get(2, TimeUnit.SECONDS)) {
      assertFalse(NetworkInterface.getByInetAddress(candidate.address()).isLoopback(),
          () -> candidate.address() + " is on the loopback interface");
    }
  }

  /**
   * Bites only where an interface holds a stable and a temporary address in one prefix, as Windows
   * does by default: replies leave from one of them, so a peer can reach the machine on one only.
   */
  @Test
  void offersOneIpv6AddressPerPrefix() throws Exception {
    Set<String> prefixes = new HashSet<>();
    for (Candidate candidate : new LocalInterfaceCandidateProvider("local", 4433, 0)
        .gather(() -> false)
        .get(2, TimeUnit.SECONDS)) {
      if (candidate.address() instanceof Inet6Address) {
        String prefix = HexFormat.of().formatHex(candidate.address().getAddress(), 0, 8);
        assertTrue(prefixes.add(prefix), () -> "two addresses offered in prefix " + prefix);
      }
    }
  }
}
