package org.synesis.coordination;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic capability request projection over the shared event sequence.
 *
 * <p>Reconstructs capability request handles, contracts, lifecycle states,
 * owner assignments, and pending actions strictly from signed coordination events.
 *
 * @since 1.0
 */
public final class CapabilityRequestProjection {

    private final Map<String, CapabilityRequestRecord> records = new LinkedHashMap<>();

    /**
     * Creates an empty capability request projection.
     */
    public CapabilityRequestProjection() {
    }

    /**
     * Copy constructor for non-mutating validation.
     *
     * @param source source projection
     */
    private CapabilityRequestProjection(CapabilityRequestProjection source) {
        records.putAll(source.records);
    }

    /**
     * Applies one capability event to update this projection.
     *
     * @param event event to apply
     * @throws IOException when an event payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case CAPABILITY_REQUEST_CREATED -> processCreated(event);
            case CAPABILITY_REQUEST_CONTRACT_REVISED -> processRevised(event);
            case CAPABILITY_REQUEST_ACCEPTED -> processAccepted(event);
            case CAPABILITY_REQUEST_REJECTED -> processRejected(event);
            case CAPABILITY_REQUEST_CANCELLED -> processCancelled(event);
            case CAPABILITY_REQUEST_SUPERSEDED -> processSuperseded(event);
            default -> {
            }
        }
    }

    /**
     * Validates one event without mutating this projection.
     *
     * @param event event to validate
     * @throws IOException when the event payload or state transition is invalid
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        CapabilityRequestProjection candidate = new CapabilityRequestProjection(this);
        candidate.apply(event);
    }

    /**
     * Looks up a capability request by public handle string.
     *
     * @param handleValue handle string (e.g. {@code req_...})
     * @return record when found
     */
    public synchronized Optional<CapabilityRequestRecord> findByHandle(String handleValue) {
        if (handleValue == null || handleValue.isBlank()) {
            return Optional.empty();
        }
        try {
            String canonical = CapabilityRequestHandle.parse(handleValue).value();
            return Optional.ofNullable(records.get(canonical));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Looks up an active (non-terminal) capability request for a requester and capability.
     *
     * @param requesterNodeId requester node ID
     * @param capability      capability name
     * @return record when an active request exists
     */
    public synchronized Optional<CapabilityRequestRecord> findActiveByRequesterAndCapability(String requesterNodeId, String capability) {
        if (requesterNodeId == null || capability == null) {
            return Optional.empty();
        }
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.requesterNodeId().equals(requesterNodeId) && rec.capability().equals(capability)) {
                if (rec.state() == CapabilityLifecycleState.AWAITING_OWNER || rec.state() == CapabilityLifecycleState.REVISION_REQUESTED) {
                    return Optional.of(rec);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all pending capability requests for an owner node.
     *
     * @param ownerNodeId assigned owner node ID
     * @return list of pending requests
     */
    public synchronized List<CapabilityRequestRecord> findPendingForOwner(String ownerNodeId) {
        if (ownerNodeId == null) {
            return List.of();
        }
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.ownerNodeId().equals(ownerNodeId) && rec.state() == CapabilityLifecycleState.AWAITING_OWNER) {
                list.add(rec);
            }
        }
        return List.copyOf(list);
    }

    /**
     * Returns all active or actionable capability requests for a requester node.
     *
     * @param requesterNodeId requester node ID
     * @return list of active requests for requester
     */
    public synchronized List<CapabilityRequestRecord> findPendingForRequester(String requesterNodeId) {
        if (requesterNodeId == null) {
            return List.of();
        }
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.requesterNodeId().equals(requesterNodeId)) {
                if (rec.state() == CapabilityLifecycleState.AWAITING_OWNER
                        || rec.state() == CapabilityLifecycleState.REVISION_REQUESTED
                        || rec.state() == CapabilityLifecycleState.ACCEPTED
                        || rec.state() == CapabilityLifecycleState.REJECTED) {
                    list.add(rec);
                }
            }
        }
        return List.copyOf(list);
    }

    /**
     * Returns a stable snapshot of all capability request records.
     *
     * @return immutable map of handle to record
     */
    public synchronized Map<String, CapabilityRequestRecord> records() {
        return Map.copyOf(records);
    }

    private void processCreated(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        if (records.containsKey(handleKey)) {
            throw new IOException("DUPLICATE_CAPABILITY_REQUEST_HANDLE");
        }
        CapabilityRequestRecord rec = new CapabilityRequestRecord(
                payload.handle(),
                payload.capability(),
                payload.requesterNodeId(),
                payload.ownerNodeId(),
                payload.contract(),
                payload.state(),
                payload.reason(),
                event.createdAtEpochMillis(),
                event.createdAtEpochMillis()
        );
        records.put(handleKey, rec);
    }

    private void processRevised(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        CapabilityRequestRecord current = records.get(handleKey);
        if (current == null) {
            throw new IOException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        if (current.state() == CapabilityLifecycleState.CANCELLED
                || current.state() == CapabilityLifecycleState.EXPIRED
                || current.state() == CapabilityLifecycleState.SUPERSEDED) {
            throw new IOException("TERMINAL_CAPABILITY_REQUEST_CANNOT_BE_REVISED");
        }
        CapabilityRequestRecord updated = current.withUpdate(payload.state(), payload.contract(), payload.reason(), event.createdAtEpochMillis());
        records.put(handleKey, updated);
    }

    private void processAccepted(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        CapabilityRequestRecord current = records.get(handleKey);
        if (current == null) {
            throw new IOException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.ACCEPTED, payload.contract(), null, event.createdAtEpochMillis());
        records.put(handleKey, updated);
    }

    private void processRejected(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        CapabilityRequestRecord current = records.get(handleKey);
        if (current == null) {
            throw new IOException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.REJECTED, null, payload.reason(), event.createdAtEpochMillis());
        records.put(handleKey, updated);
    }

    private void processCancelled(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        CapabilityRequestRecord current = records.get(handleKey);
        if (current == null) {
            throw new IOException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.CANCELLED, null, payload.reason(), event.createdAtEpochMillis());
        records.put(handleKey, updated);
    }

    private void processSuperseded(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        String handleKey = payload.handle().value();
        CapabilityRequestRecord current = records.get(handleKey);
        if (current == null) {
            throw new IOException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.SUPERSEDED, null, payload.reason(), event.createdAtEpochMillis());
        records.put(handleKey, updated);
    }
}
