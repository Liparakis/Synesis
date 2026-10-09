package org.synesis.link.onboarding;

import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import org.synesis.link.transport.quic.EphemeralTlsMaterial;
import org.synesis.link.transport.quic.NettyStunBindingTransport;

/**
 * Close ordering for the network resources that an onboarding host or join handle owns.
 *
 * <p>This is separate from {@link Onboarding} so that the failure paths of the factory methods and
 * the {@code close()} methods of the prepared handles share exactly one release order: STUN
 * binding, UDP channel, event loop group, invitation admission, then TLS material.
 */
final class EndpointCleanup {

  private EndpointCleanup() {
  }

  static void closeResources(Channel udp, MultiThreadIoEventLoopGroup group,
      EphemeralTlsMaterial tls,
      NettyStunBindingTransport stun, InvitationAdmission admission) {
    if (stun != null) {
      stun.close();
    }
    if (udp != null) {
      udp.close().syncUninterruptibly();
    }
    if (group != null) {
      group.shutdownGracefully().syncUninterruptibly();
    }
    if (admission != null) {
      admission.close();
    }
    if (tls != null) {
      try {
        tls.close();
      } catch (java.io.IOException ignored) {
        // All bounded network resources have already been closed.
      }
    }
  }
}
