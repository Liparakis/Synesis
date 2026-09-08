package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.synesis.link.identity.NodeIdentity;

/**
 * One live, in-memory organization relay core.
 *
 * <p>The core forwards only decoded `SLF1` metadata and opaque inner bytes.
 * It has no durable mailbox and no API that accepts arbitrary IP traffic. A
 * Netty or other event-loop server can bind its connection callbacks to this
 * class without changing the authorization and bound checks.
 */
public final class OverlayRelay {

    /** Live relay client callback. */
    @FunctionalInterface
    public interface Connection {

        /**
         * Delivers one already hop-consumed frame to the connected node.
         *
         * @param frame opaque frame
         * @return completion of bounded delivery
         */
        CompletionStage<Void> deliver(OverlayForwardingFrame frame);
    }

    private final OverlayRelayPolicy policy;
    private final Map<String, Registration> connections = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> queuedByDestination = new ConcurrentHashMap<>();
    private final OverlayDuplicateGuard duplicateGuard = new OverlayDuplicateGuard(4_096);

    /**
     * Creates a live relay core.
     *
     * @param policy operator allowlist and bounds
     */
    public OverlayRelay(OverlayRelayPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "relay policy");
    }

    /**
     * Authenticates and registers one node connection.
     *
     * @param identity durable node identity used for relay authentication
     * @param connection live delivery callback
     * @return registration that closes the live connection
     * @throws GeneralSecurityException if the node is not authorized
     */
    public Registration register(NodeIdentity identity, Connection connection) throws GeneralSecurityException {
        Objects.requireNonNull(identity, "identity");
        return registerAuthenticated(identity.nodeId(), connection);
    }

    /**
     * Registers a node after an external transport has verified its durable
     * identity proof.
     *
     * @param authenticatedNodeId node ID established by the transport
     * @param connection live delivery callback
     * @return registration that closes the live connection
     * @throws GeneralSecurityException if the node is not authorized
     */
    public synchronized Registration registerAuthenticated(String authenticatedNodeId, Connection connection)
            throws GeneralSecurityException {
        OverlayCodecSupport.requireNodeId(authenticatedNodeId);
        Objects.requireNonNull(connection, "connection");
        if (!isAllowedOnAnyProject(authenticatedNodeId)) {
            throw new OverlayRelayException(OverlayRelayException.Failure.UNAUTHORIZED_NODE,
                    "node is not authorized by relay policy");
        }
        if (connections.size() >= policy.maxConnections()) {
            throw new OverlayRelayException(OverlayRelayException.Failure.CONNECTION_LIMIT,
                    "relay connection limit reached");
        }
        Registration registration = new Registration(this, authenticatedNodeId, connection);
        if (connections.putIfAbsent(authenticatedNodeId, registration) != null) {
            throw new OverlayRelayException(OverlayRelayException.Failure.UNAUTHORIZED_NODE,
                    "node already has a live relay connection");
        }
        return registration;
    }

    /**
     * Returns the current live connection count.
     *
     * @return connection count
     */
    public int connectionCount() {
        return connections.size();
    }

    private CompletionStage<Void> forward(String sourceNodeId, OverlayForwardingFrame frame, Instant now) {
        try {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(now, "now");
            if (!policy.allows(frame.projectId(), sourceNodeId)
                    || !policy.allows(frame.projectId(), frame.destinationNodeId())) {
                return failed(new OverlayRelayException(OverlayRelayException.Failure.UNAUTHORIZED_PROJECT,
                        "relay project route is unauthorized"));
            }
            if (!withinRate(sourceNodeId, now)) {
                return failed(new OverlayRelayException(OverlayRelayException.Failure.RATE_LIMITED,
                        "relay source rate limit exceeded"));
            }
            if (frame.remainingHops() == 0) {
                return failed(new OverlayRelayException(OverlayRelayException.Failure.HOP_LIMIT_EXCEEDED,
                        "relay hop budget exhausted"));
            }
            Registration destination = connections.get(frame.destinationNodeId());
            if (destination == null || destination.closed) {
                return failed(new OverlayRelayException(OverlayRelayException.Failure.NO_ROUTE,
                        "relay destination is not connected"));
            }
            AtomicInteger queued = queuedByDestination.computeIfAbsent(frame.destinationNodeId(),
                    ignored -> new AtomicInteger());
            if (queued.incrementAndGet() > policy.maxQueuedFrames()) {
                queued.decrementAndGet();
                return failed(new OverlayRelayException(OverlayRelayException.Failure.QUEUE_FULL,
                        "relay destination queue is full"));
            }
            if (!duplicateGuard.firstSeen(frame, now)) {
                queued.decrementAndGet();
                return failed(new OverlayRelayException(OverlayRelayException.Failure.DUPLICATE,
                        "duplicate relay frame"));
            }
            CompletionStage<Void> delivery;
            try {
                delivery = destination.connection.deliver(frame.forwardOneHop());
            } catch (RuntimeException failure) {
                queued.decrementAndGet();
                return failed(failure);
            }
            if (delivery == null) {
                queued.decrementAndGet();
                return failed(new OverlayRelayException(OverlayRelayException.Failure.NO_ROUTE,
                        "relay destination returned no completion"));
            }
            return delivery.whenComplete((ignored, failure) -> queued.decrementAndGet());
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    private boolean withinRate(String nodeId, Instant now) {
        RateWindow window = rateWindows.computeIfAbsent(nodeId, ignored -> new RateWindow());
        synchronized (window) {
            long second = now.getEpochSecond();
            if (window.second != second) {
                window.second = second;
                window.count = 0;
            }
            return ++window.count <= policy.maxFramesPerSecond();
        }
    }

    private boolean isAllowedOnAnyProject(String nodeId) {
        return policy.allowsOnAnyProject(nodeId);
    }

    private static CompletionStage<Void> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    private static final class RateWindow {

        private long second = Long.MIN_VALUE;
        private int count;
    }

    /**
     * One authenticated live node registration.
     */
    public static final class Registration implements AutoCloseable {

        private final OverlayRelay relay;
        private final String nodeId;
        private final Connection connection;
        private volatile boolean closed;

        private Registration(OverlayRelay relay, String nodeId, Connection connection) {
            this.relay = relay;
            this.nodeId = nodeId;
            this.connection = connection;
        }

        /**
         * Sends one bounded frame through this source registration.
         *
         * @param frame bounded opaque frame
         * @param now current time
         * @return completion of live forwarding
         */
        public CompletionStage<Void> send(OverlayForwardingFrame frame, Instant now) {
            if (closed) {
                return CompletableFuture.failedFuture(new OverlayRelayException(
                        OverlayRelayException.Failure.UNAUTHORIZED_NODE, "relay registration is closed"));
            }
            return relay.forward(nodeId, frame, now);
        }

        /**
         * Returns the authenticated node ID.
         *
         * @return node ID
         */
        public String nodeId() {
            return nodeId;
        }

        /**
         * Closes this live relay registration.
         */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                relay.connections.remove(nodeId, this);
            }
        }
    }
}
