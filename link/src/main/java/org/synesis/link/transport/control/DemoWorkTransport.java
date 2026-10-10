package org.synesis.link.transport.control;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.synesis.link.demo.DemoWorkCodec;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;
import org.synesis.link.demo.DemoWorkStatus;
import org.synesis.link.session.PeerSession;

/**
 * Internal Netty adapter for one bounded demo request/result stream.
 */
public final class DemoWorkTransport {

  private static final String LABEL = "demo";

  private DemoWorkTransport() {
  }

  /**
   * Opens one bounded demo-work stream from the control channel.
   *
   * @param controlContext active control-stream context
   * @param request        demo work request
   * @param active         active-stream counter enforcing the stream limit
   * @return completion for the demo result
   */
  static CompletionStage<DemoWorkResult> open(ChannelHandlerContext controlContext,
      DemoWorkRequest request, AtomicInteger active) {
    return StreamExchange.open(controlContext, active, LABEL, DemoWorkCodec.MAX_FRAME_BYTES,
        () -> DemoWorkCodec.encodeRequest(request), frame -> {
          DemoWorkResult value = DemoWorkCodec.decodeResult(frame);
          if (!request.requestId()
              .equals(value.requestId())) {
            throw new IllegalArgumentException("demo result correlation mismatch");
          }
          return value;
        });
  }

  /**
   * Accepts one inbound demo-work stream and replaces the bootstrap handler with the stream owner.
   *
   * @param context    stream context
   * @param oldHandler bootstrap handler to replace
   * @param session    authenticated Link session
   * @param seen       idempotency set of observed demo requests
   * @param active     active-stream counter enforcing the stream limit
   * @param firstFrame first already-read frame bytes
   */
  public static void accept(ChannelHandlerContext context, ChannelHandler oldHandler,
      PeerSession session, Set<UUID> seen, AtomicInteger active, byte[] firstFrame) {
    StreamExchange.accept(context, oldHandler, "demo-work", session, active, LABEL,
        DemoWorkCodec.MAX_FRAME_BYTES, frame -> {
          DemoWorkRequest request = DemoWorkCodec.decodeRequest(frame);
          boolean fresh = seen.add(request.requestId());
          return CompletableFuture.completedFuture(DemoWorkCodec.encodeResult(
              new DemoWorkResult(request.requestId(),
                  fresh ? DemoWorkStatus.OK : DemoWorkStatus.DUPLICATE_REQUEST,
                  fresh ? "accepted" : "duplicate-request")));
        }, firstFrame);
  }
}
