package org.synesis.link.transport;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicTokenHandler;

/**
 * Internal adapter for the selected Netty QUIC implementation.
 *
 * <p>This type is package-private by design. Its handlers and native lifecycle
 * are implementation details and must be translated into transport-neutral
 * Synesis Link APIs before crossing a public boundary.
 */
final class NettyQuicTransport {

    private NettyQuicTransport() {
    }

    static ChannelHandler serverCodec(QuicSslContext sslContext, QuicTokenHandler tokenHandler,
            ChannelHandler connectionHandler, ChannelHandler streamHandler) {
        return new QuicServerCodecBuilder()
                .sslContext(sslContext)
                .tokenHandler(tokenHandler)
                .handler(connectionHandler)
                .streamHandler(streamHandler)
                .initialMaxData(1_048_576)
                .initialMaxStreamDataBidirectionalLocal(262_144)
                .initialMaxStreamDataBidirectionalRemote(262_144)
                .initialMaxStreamsBidirectional(16)
                .build();
    }

    static ChannelHandler clientCodec(QuicSslContext sslContext) {
        return new QuicClientCodecBuilder()
                .sslContext(sslContext)
                .initialMaxData(1_048_576)
                .initialMaxStreamDataBidirectionalLocal(262_144)
                .initialMaxStreamDataBidirectionalRemote(262_144)
                .initialMaxStreamsBidirectional(16)
                .build();
    }

}
