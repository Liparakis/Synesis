package org.synesis.link.transport.quic;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Internal adapter for the selected Netty QUIC implementation.
 *
 * <p>This type is package-private by design. Its handlers and native lifecycle
 * are implementation details and must be translated into transport-neutral Synesis Link APIs before
 * crossing a public boundary.
 */
public final class NettyQuicTransport {

  private static final int INITIAL_MAX_BIDIRECTIONAL_STREAMS = 64;

  private NettyQuicTransport() {
  }

  /**
   * A connection handler that does nothing and may be installed on every connection of one server
   * codec. The codec adds its connection handler to each accepted connection's pipeline, and Netty
   * refuses a second addition of a handler that is not sharable: a server codec given a plain
   * {@code ChannelInboundHandlerAdapter} accepts one connection and silently drops the rest, which
   * is why a host could never be dialed twice.
   *
   * @return the shared no-op connection handler
   */
  public static ChannelHandler sharedConnectionHandler() {
    return SharedConnectionHandler.INSTANCE;
  }

  @ChannelHandler.Sharable
  private static final class SharedConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final SharedConnectionHandler INSTANCE = new SharedConnectionHandler();
  }

  /**
   * Creates the server-side QUIC codec used by the internal Netty transport adapter, with no idle
   * timeout.
   *
   * @param sslContext        QUIC TLS context
   * @param tokenHandler      QUIC token handler
   * @param connectionHandler connection bootstrap handler
   * @param streamHandler     stream bootstrap handler
   * @return configured server codec
   */
  public static ChannelHandler serverCodec(QuicSslContext sslContext, QuicTokenHandler tokenHandler,
      ChannelHandler connectionHandler, ChannelHandler streamHandler) {
    return serverCodec(sslContext, tokenHandler, connectionHandler, streamHandler, null);
  }

  /**
   * Creates the server-side QUIC codec with an explicit idle timeout.
   *
   * @param sslContext        QUIC TLS context
   * @param tokenHandler      QUIC token handler
   * @param connectionHandler connection bootstrap handler
   * @param streamHandler     stream bootstrap handler
   * @param maxIdleTimeout    time without any packet after which a connection closes, or
   *                          {@code null} for none
   * @return configured server codec
   */
  public static ChannelHandler serverCodec(QuicSslContext sslContext, QuicTokenHandler tokenHandler,
      ChannelHandler connectionHandler, ChannelHandler streamHandler, Duration maxIdleTimeout) {
    QuicServerCodecBuilder builder = new QuicServerCodecBuilder();
    if (maxIdleTimeout != null) {
      builder.maxIdleTimeout(maxIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    return builder
        .sslContext(sslContext)
        .tokenHandler(tokenHandler)
        .handler(connectionHandler)
        .streamHandler(streamHandler)
        .initialMaxData(1_048_576)
        .initialMaxStreamDataBidirectionalLocal(262_144)
        .initialMaxStreamDataBidirectionalRemote(262_144)
        .initialMaxStreamsBidirectional(INITIAL_MAX_BIDIRECTIONAL_STREAMS)
        .build();
  }

  /**
   * Creates the client-side QUIC codec used by the internal Netty transport adapter, with no idle
   * timeout.
   *
   * @param sslContext QUIC TLS context
   * @return configured client codec
   */
  public static ChannelHandler clientCodec(QuicSslContext sslContext) {
    return clientCodec(sslContext, null);
  }

  /**
   * Creates the client-side QUIC codec with an explicit idle timeout.
   *
   * @param sslContext     QUIC TLS context
   * @param maxIdleTimeout time without any packet after which a connection closes, or
   *                       {@code null} for none
   * @return configured client codec
   */
  public static ChannelHandler clientCodec(QuicSslContext sslContext, Duration maxIdleTimeout) {
    QuicClientCodecBuilder builder = new QuicClientCodecBuilder();
    if (maxIdleTimeout != null) {
      builder.maxIdleTimeout(maxIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    return builder
        .sslContext(sslContext)
        .initialMaxData(1_048_576)
        .initialMaxStreamDataBidirectionalLocal(262_144)
        .initialMaxStreamDataBidirectionalRemote(262_144)
        .initialMaxStreamsBidirectional(INITIAL_MAX_BIDIRECTIONAL_STREAMS)
        .build();
  }

}
