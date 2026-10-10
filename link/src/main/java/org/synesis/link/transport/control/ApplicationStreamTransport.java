package org.synesis.link.transport.control;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.synesis.link.session.PeerSession;

/**
 * Internal Netty transport for one bounded opaque application-stream exchange.
 */
public final class ApplicationStreamTransport {

  private static final String LABEL = "application";

  private ApplicationStreamTransport() {
  }

  /**
   * Opens one bounded application stream from the control channel.
   *
   * @param controlContext active control-stream context
   * @param payload        opaque request payload
   * @param active         active-stream counter enforcing the stream limit
   * @return completion for the opaque response payload
   */
  static CompletionStage<byte[]> open(ChannelHandlerContext controlContext, byte[] payload,
      AtomicInteger active) {
    if (payload.length > ApplicationStreamCodec.MAX_PAYLOAD_BYTES) {
      return CompletableFuture.failedFuture(new IllegalArgumentException(
          "application payload exceeds supported bound"));
    }
    byte[] request = payload.clone();
    return StreamExchange.open(controlContext, active, LABEL,
        ApplicationStreamCodec.MAX_FRAME_BYTES, () -> ApplicationStreamCodec.encode(request),
        ApplicationStreamCodec::decode);
  }

  /**
   * Accepts one inbound application stream and replaces the bootstrap handler with the stream
   * owner.
   *
   * @param context    stream context
   * @param oldHandler bootstrap handler to replace
   * @param session    authenticated Link session
   * @param handler    bounded application-stream callback
   * @param active     active-stream counter enforcing the stream limit
   * @param firstFrame first already-read frame bytes
   */
  public static void accept(ChannelHandlerContext context, ChannelHandler oldHandler,
      PeerSession session,
      PeerSession.ApplicationStreamHandler handler, AtomicInteger active, byte[] firstFrame) {
    if (handler == null) {
      context.close();
      return;
    }
    StreamExchange.accept(context, oldHandler, "synesis-application", session, active, LABEL,
        ApplicationStreamCodec.MAX_FRAME_BYTES, frame -> {
          CompletionStage<byte[]> response = handler.handle(session.remoteNodeId(),
              ApplicationStreamCodec.decode(frame));
          return response == null ? null : response.thenApply(value -> {
            if (value == null || value.length > ApplicationStreamCodec.MAX_PAYLOAD_BYTES) {
              return null;
            }
            return ApplicationStreamCodec.encode(value);
          });
        }, firstFrame);
  }
}
