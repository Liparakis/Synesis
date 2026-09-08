package org.synesis.link.overlay;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.synesis.link.session.PeerSession;

/**
 * Adapts the logical overlay to the existing bounded authenticated application
 * stream while preserving one {@link PeerSession} per physical peer.
 */
public final class OverlayPeerSessionBridge {

    private OverlayPeerSessionBridge() {
    }

    /**
     * Creates an outbound transport binding for one direct peer session.
     *
     * @param session authenticated direct peer session
     * @return overlay transport binding
     */
    public static OverlayForwarder.PeerTransport outbound(PeerSession session) {
        Objects.requireNonNull(session, "session");
        return frame -> session.requestApplication(frame.encoded()).thenApply(response -> null);
    }

    /**
     * Creates an inbound application callback for one local forwarder.
     *
     * <p>The callback returns an empty bounded response as an acknowledgement;
     * no application plaintext is put on the physical stream.
     *
     * @param forwarder local overlay forwarder
     * @param clock time source for expiry and replay checks
     * @return callback suitable for {@link PeerSession.ApplicationStreamHandler}
     */
    public static PeerSession.ApplicationStreamHandler inbound(OverlayForwarder forwarder, Clock clock) {
        Objects.requireNonNull(forwarder, "forwarder");
        Objects.requireNonNull(clock, "clock");
        return (remoteNodeId, payload) -> {
            try {
                OverlayForwardingFrame frame = OverlayForwardingFrame.decode(payload);
                return forwarder.forwardFrom(remoteNodeId, frame, Instant.now(clock))
                        .thenApply(ignored -> new byte[0]);
            } catch (IOException | RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }
}
