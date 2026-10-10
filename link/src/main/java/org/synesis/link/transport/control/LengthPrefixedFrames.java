package org.synesis.link.transport.control;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

/**
 * Four-byte big-endian length-prefixed framing shared by every Link stream.
 */
public final class LengthPrefixedFrames {

  private LengthPrefixedFrames() {
  }

  /**
   * Adds the length-prefix decoder and prepender for frames of at most {@code maxFrameBytes}.
   *
   * @param pipeline      stream pipeline
   * @param maxFrameBytes maximum unprefixed frame size
   */
  public static void install(ChannelPipeline pipeline, int maxFrameBytes) {
    pipeline.addLast(new LengthFieldBasedFrameDecoder(maxFrameBytes + Integer.BYTES, 0,
        Integer.BYTES, 0, Integer.BYTES));
    pipeline.addLast(new LengthFieldPrepender(Integer.BYTES));
  }

  /**
   * Reads one decoded frame into a byte array.
   *
   * @param message       decoded frame
   * @param maxFrameBytes maximum accepted frame size
   * @param oversized     message of the exception thrown for a larger frame
   * @return frame bytes
   */
  public static byte[] read(ByteBuf message, int maxFrameBytes, String oversized) {
    if (message.readableBytes() > maxFrameBytes) {
      throw new IllegalArgumentException(oversized);
    }
    byte[] bytes = new byte[message.readableBytes()];
    message.readBytes(bytes);
    return bytes;
  }
}
