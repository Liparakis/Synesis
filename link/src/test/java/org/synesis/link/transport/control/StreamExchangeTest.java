package org.synesis.link.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.synesis.link.demo.DemoWorkCodec;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;

/**
 * Verifies that a demo-work request to a peer that never replies fails and frees its stream slot.
 */
final class StreamExchangeTest {

  @Test
  void demoRequestWithoutReplyTimesOutAndReleasesItsSlot() throws Exception {
    AtomicInteger active = new AtomicInteger();
    Runnable release = StreamExchange.acquire(active);
    assertNotNull(release);
    assertEquals(1, active.get());
    DemoWorkRequest request = new DemoWorkRequest(UUID.randomUUID(),
        DemoWorkRequest.DESCRIBE_SESSION);
    CompletableFuture<DemoWorkResult> result = new CompletableFuture<>();
    EmbeddedChannel channel = new EmbeddedChannel(new StreamExchange.ClientHandler<>("demo",
        DemoWorkCodec.MAX_FRAME_BYTES, () -> DemoWorkCodec.encodeRequest(request),
        DemoWorkCodec::decodeResult, result, release));

    Thread.sleep(StreamExchange.OPERATION_TIMEOUT_MILLIS + 200);
    channel.runScheduledPendingTasks();

    ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
        ExecutionException.class, result::get);
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals("demo stream timed out", failure.getCause()
        .getMessage());
    assertTrue(!channel.isActive());
    assertEquals(0, active.get());
  }
}
