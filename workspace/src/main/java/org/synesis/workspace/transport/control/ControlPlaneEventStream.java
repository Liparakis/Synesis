package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.API_VERSION;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.newResponse;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.ordered;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The server-sent-events stream behind {@code /events}: framing, keepalives, and the mapping of
 * coordination and onboarding events to the coarse event names the browser UI subscribes to.
 *
 * <p>Separate from the handler because it is a long-lived, polling response with its own timing
 * constants; the handler supplies the closed flag and the snapshot source per stream.
 */
final class ControlPlaneEventStream {

    /**
     * Idle period after which an SSE stream emits a comment frame to keep proxies open.
     */
    private static final Duration SSE_KEEPALIVE = Duration.ofSeconds(15);

    /**
     * Interval at which the durable log is replayed for events appended by other processes.
     * Each read replays the whole log, so this stays well above {@link #SSE_POLL_INTERVAL_MS}.
     */
    private static final Duration DURABLE_POLL = Duration.ofSeconds(1);

    /**
     * Sleep between polls of the two independent SSE event sources.
     */
    private static final long SSE_POLL_INTERVAL_MS = 100;

    /**
     * SSE comment frame sent when a stream has been idle for {@link #SSE_KEEPALIVE}.
     */
    private static final byte[] SSE_KEEPALIVE_FRAME = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Durable coordination service; read for head sequence and live event subscriptions.
     */
    private final CoordinationService coordination;

    /**
     * Bounded hub of onboarding events streamed to the browser.
     */
    private final ControlPlaneEventHub eventHub;

    /**
     * Creates the event stream over the live event sources.
     *
     * @param coordination durable coordination service
     * @param eventHub     bounded onboarding event hub
     */
    ControlPlaneEventStream(CoordinationService coordination, ControlPlaneEventHub eventHub) {
        this.coordination = coordination;
        this.eventHub = eventHub;
    }

    /**
     * Streams a snapshot followed by coordination and onboarding events until the client leaves,
     * the handler closes, or a subscriber queue overflows.
     *
     * <p>Takes ownership of the exchange and closes it on exit.
     *
     * @param exchange HTTP exchange
     * @param closed   the handler's closed flag; the stream ends once it is set
     * @param snapshot source of the snapshot body sent as the first event
     * @throws IOException when the stream cannot be written
     */
    void events(HttpExchange exchange, AtomicBoolean closed, Supplier<Map<String, Object>> snapshot) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-store");
        exchange.sendResponseHeaders(200, 0);
        try (exchange;
                CoordinationService.Subscription coordinationSubscription = coordination.subscribeLive();
                ControlPlaneEventHub.Subscription onboardingSubscription = eventHub.subscribe();
                OutputStream output = exchange.getResponseBody()) {
            long delivered = coordination.headSequence();
            writeEvent(output, "snapshot", Long.toString(delivered), snapshotEvent(snapshot));
            long keepaliveAt = nextKeepalive();
            long durableCheckAt = nextDurableCheck();
            while (!Thread.currentThread().isInterrupted() && !closed.get()) {
                if (onboardingSubscription.overflowed()) {
                    writeEvent(output, "refresh_required", "refresh", ordered("apiVersion", API_VERSION, "reason", "subscriber_queue_overflow"));
                    break;
                }
                // Agents append from other processes, which no in-process subscription sees. The subscription
                // only wakes this loop early for the server's own appends; every event is sent from the durable log.
                boolean woken = false;
                while (coordinationSubscription.poll() != null) {
                    woken = true;
                }
                if (woken || System.nanoTime() >= durableCheckAt) {
                    durableCheckAt = nextDurableCheck();
                    List<PredictionEvent> appended = coordination.replayAfter(delivered);
                    for (PredictionEvent event : appended) {
                        // The local revision, the cursor of every reader (ADR-0098 step 4), not the entry's
                        // position in its own log.
                        delivered++;
                        String eventType = uiEventType(event.type());
                        writeEvent(output, eventType, Long.toString(delivered), ordered("apiVersion", API_VERSION, "sequence", delivered, "type", eventType));
                    }
                    if (!appended.isEmpty()) {
                        keepaliveAt = nextKeepalive();
                        continue;
                    }
                }
                Event onboardingEvent = onboardingSubscription.poll();
                if (onboardingEvent != null) {
                    String eventType = uiLinkEventType(onboardingEvent.type());
                    Map<String, Object> data = newResponse();
                    data.put("type", eventType);
                    if (!onboardingEvent.value().isEmpty()) {
                        data.put("value", onboardingEvent.value());
                    }
                    writeEvent(output, eventType, Long.toString(onboardingEvent.sequence()), data);
                    keepaliveAt = nextKeepalive();
                    continue;
                }
                if (System.nanoTime() >= keepaliveAt) {
                    output.write(SSE_KEEPALIVE_FRAME);
                    output.flush();
                    keepaliveAt = nextKeepalive();
                    continue;
                }
                pauseBetweenPolls();
            }
        }
    }

    /**
     * Builds the first event of every stream.
     *
     * @param snapshot source of the snapshot body
     * @return snapshot event payload
     */
    private Map<String, Object> snapshotEvent(Supplier<Map<String, Object>> snapshot) {
        Map<String, Object> event = newResponse();
        event.put("headSequence", coordination.headSequence());
        event.put("snapshot", snapshot.get());
        return event;
    }

    /**
     * Computes when the durable log is next read for events other processes appended.
     *
     * @return {@link System#nanoTime()} value {@link #DURABLE_POLL} from now
     */
    private static long nextDurableCheck() {
        return System.nanoTime() + DURABLE_POLL.toNanos();
    }

    /**
     * Computes the next keepalive deadline.
     *
     * @return {@link System#nanoTime()} value {@link #SSE_KEEPALIVE} from now
     */
    private static long nextKeepalive() {
        return System.nanoTime() + SSE_KEEPALIVE.toNanos();
    }

    /**
     * Sleeps between polls so the two independent event sources are served without spinning.
     * Restores the interrupt flag, which ends the stream loop.
     */
    private static void pauseBetweenPolls() {
        try {
            Thread.sleep(SSE_POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes and flushes one SSE frame.
     *
     * @param output stream to write to
     * @param type   SSE event name
     * @param id     SSE event id
     * @param data   JSON-serializable payload
     * @throws IOException when the stream cannot be written
     */
    private static void writeEvent(OutputStream output, String type, String id, Map<String, Object> data) throws IOException {
        String value = ProviderJson.write(data);
        output.write(("event: " + type + "\nid: " + id + "\ndata: " + value + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * Collapses a coordination event type into the coarse event name the UI subscribes to.
     *
     * @param type domain event type
     * @return UI event name; {@code coordination.updated} for anything not specifically grouped
     */
    private static String uiEventType(PredictionEventType type) {
        return switch (type) {
            case WORK_INTENT_ANNOUNCED, WORK_INTENT_RELEASED, COORDINATION_REQUESTED, COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT, CLAIM_HANDOFF_ACCEPTED, PARTICIPANT_ABANDONED, PARTICIPANT_SUSPENDED,
                 PARTICIPANT_REVOKED, PARTICIPANT_CANCELLED, PARTICIPANT_DETACHED, SESSION_FINALIZED, SESSION_ABANDONED -> "agent.updated";
            case WORK_GROUP_CREATED, WORK_GROUP_STATUS_CHANGED, LANE_GRANT_ISSUED, LANE_GRANT_CONSUMED, LANE_REVOKED, LANE_CONTINUATION_ACCEPTED -> "workgroup.updated";
            case TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED, COMPLETION_PREPARED, COMPLETION_UNWOUND, REPAIR_REQUIRED, REPAIR_LANE_CREATED -> "claim.updated";
            case CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED, CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED, CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED, CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND,
                 CONTRACT_SUPERSEDED, CAPABILITY_AVAILABLE, DEPENDENCY_INVALIDATED -> "capability.updated";
            case TASK_CREATED, TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED, INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED,
                 INTEGRATION_CONFLICTED, INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, INTEGRATION_BLOCKED, REVIEW_VALIDATION_RECORDED,
                 INTEGRATION_VALIDATION_REFUSED, REMERGE_SNAPSHOT_CREATED, COMBINE_REQUIRED -> "task.updated";
            default -> "coordination.updated";
        };
    }

    /**
     * Collapses an onboarding event type into the coarse event name the UI subscribes to.
     *
     * @param type onboarding event type
     * @return UI event name; {@code link.updated} for anything not specifically grouped
     */
    private static String uiLinkEventType(OnboardingEventType type) {
        return switch (type) {
            case PEER_CONNECTED -> "peer.connected";
            case SESSION_CLOSED -> "peer.disconnected";
            case LIVENESS -> "peer.updated";
            default -> "link.updated";
        };
    }
}