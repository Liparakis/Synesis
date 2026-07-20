package org.synesis.link.session;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable safe liveness counters and diagnostics.
 *
 * <p>Counts are local observations and contain no payloads, identities, keys,
 * or authentication transcripts. The snapshot is eventually consistent with
 * a concurrently running control stream.</p>
 *
 * @param heartbeatSentCount heartbeats accepted for transport write
 * @param heartbeatReceivedCount newest valid heartbeats received
 * @param heartbeatAcknowledgedCount newest valid acknowledgements received
 * @param duplicateOrStaleCount duplicate or stale sequence observations
 * @param heartbeatSendFailureCount failed heartbeat writes
 * @param lastValidPeerActivityAge current monotonic age of valid peer activity
 * @param estimatedLocalRtt locally measured echoed-marker round-trip duration, if available
 * @param liveToSuspectCount LIVE to SUSPECT transitions
 * @param suspectToLiveCount SUSPECT to LIVE recoveries
 * @param suspectToExpiredCount SUSPECT to EXPIRED transitions
 * @param droppedEventCount liveness events dropped by a bounded dispatcher
 * @param terminalTransitionCount terminal liveness transitions selected
 * @since 1.0
 */
public record LivenessMetrics(
        long heartbeatSentCount,
        long heartbeatReceivedCount,
        long heartbeatAcknowledgedCount,
        long duplicateOrStaleCount,
        long heartbeatSendFailureCount,
        Duration lastValidPeerActivityAge,
        Duration estimatedLocalRtt,
        long liveToSuspectCount,
        long suspectToLiveCount,
        long suspectToExpiredCount,
        long droppedEventCount,
        long terminalTransitionCount) {

    /** Validates immutable diagnostic values. */
    public LivenessMetrics {
        Objects.requireNonNull(lastValidPeerActivityAge, "last valid peer activity age");
        Objects.requireNonNull(estimatedLocalRtt, "estimated local RTT");
        if (lastValidPeerActivityAge.isNegative() || estimatedLocalRtt.isNegative()) {
            throw new IllegalArgumentException("diagnostic durations cannot be negative");
        }
    }
}
