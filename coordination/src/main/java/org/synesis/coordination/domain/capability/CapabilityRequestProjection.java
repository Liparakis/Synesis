package org.synesis.coordination.domain.capability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.synesis.coordination.domain.identity.ImplementationRevision;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic capability request projection over the shared event sequence.
 *
 * <p>Reconstructs capability request handles, contracts, lifecycle states,
 * owner assignments, pending actions, immutable implementation revisions, and active validation contexts strictly from
 * signed coordination
 * events.
 *
 * @since 1.0
 */
public final class CapabilityRequestProjection {

    private final Map<CapabilityRequestHandle, CapabilityRequestRecord> records = new LinkedHashMap<>();
    private final Map<ImplementationRevisionKey, ImplementationRevisionRecord> implementations = new LinkedHashMap<>();
    private final Map<CapabilityRequestHandle, ImplementationRevision> latestRevision = new LinkedHashMap<>();
    private final Map<CapabilityRequestHandle, ValidationContextRecord> validationContexts = new LinkedHashMap<>();

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
        implementations.putAll(source.implementations);
        latestRevision.putAll(source.latestRevision);
        validationContexts.putAll(source.validationContexts);
    }

    /**
     * Applies one capability event to update this projection.
     *
     * @param event event to apply
     * @throws IOException when an event payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case CAPABILITY_REQUEST_CREATED -> processCreated(event);
            case CAPABILITY_REQUEST_CONTRACT_REVISED -> processRevised(event);
            case CAPABILITY_REQUEST_ACCEPTED -> processAccepted(event);
            case CAPABILITY_REQUEST_REJECTED -> processRejected(event);
            case CAPABILITY_REQUEST_CANCELLED -> processCancelled(event);
            case CAPABILITY_REQUEST_SUPERSEDED -> processSuperseded(event);
            case CAPABILITY_IMPLEMENTATION_PUBLISHED -> processImplementationPublished(event);
            case CAPABILITY_VALIDATION_STARTED -> processValidationStarted(event);
            case CAPABILITY_IMPLEMENTATION_VALIDATED -> processValidated(event);
            case CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED -> processRevisionRequired(event);
            case LANE_CONTINUATION_ACCEPTED -> processLaneContinuation(event);
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
            return findByHandle(CapabilityRequestHandle.parse(handleValue));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Looks up a request by its validated capability-request identity.
     *
     * @param handle validated request identity
     * @return matching request when known
     */
    public synchronized Optional<CapabilityRequestRecord> findByHandle(CapabilityRequestHandle handle) {
        return Optional.ofNullable(records.get(CoordinationNullException.requireNonNull(handle, "handle")));
    }

    /**
     * Returns all pending capability requests for an owner node that require a response. Authorization is by node ID
     * only; caller must
     * additionally check worker identity.
     *
     * @param ownerNodeId assigned owner node ID
     * @return list of pending requests
     */
    public synchronized List<CapabilityRequestRecord> findPendingForOwner(String ownerNodeId) {
        if (ownerNodeId == null) {
            return List.of();
        }
        return findPendingForOwner(new NodeId(ownerNodeId));
    }

    /**
     * Returns pending requests for a typed owner node that require a response.
     *
     * @param ownerNodeId assigned owner node identity
     * @return immutable list of pending requests
     */
    public synchronized List<CapabilityRequestRecord> findPendingForOwner(NodeId ownerNodeId) {
        NodeId owner = CoordinationNullException.requireNonNull(ownerNodeId, "owner node ID");
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.ownerNodeIdentity().equals(owner) && rec.state() == CapabilityLifecycleState.AWAITING_OWNER) {
                list.add(rec);
            }
        }
        return List.copyOf(list);
    }

    /**
     * Returns all capability requests for an owner node that require a validation revision response.
     *
     * @param ownerNodeId assigned owner node ID
     * @return list of records needing validation revision response
     */
    public synchronized List<CapabilityRequestRecord> findValidationRevisionForOwner(String ownerNodeId) {
        if (ownerNodeId == null) {
            return List.of();
        }
        return findValidationRevisionForOwner(new NodeId(ownerNodeId));
    }

    /**
     * Returns requests awaiting a validation revision for a typed owner node.
     *
     * @param ownerNodeId assigned owner node identity
     * @return immutable list of records needing validation revision response
     */
    public synchronized List<CapabilityRequestRecord> findValidationRevisionForOwner(NodeId ownerNodeId) {
        NodeId owner = CoordinationNullException.requireNonNull(ownerNodeId, "owner node ID");
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.ownerNodeIdentity().equals(owner) && rec.state() == CapabilityLifecycleState.IMPLEMENTING
                    && rec.reason() != null && !rec.reason().isBlank()) {
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
        return findPendingForRequester(new NodeId(requesterNodeId));
    }

    /**
     * Returns active or actionable requests for a typed requester node.
     *
     * @param requesterNodeId requester node identity
     * @return immutable list of active requests
     */
    public synchronized List<CapabilityRequestRecord> findPendingForRequester(NodeId requesterNodeId) {
        NodeId requester = CoordinationNullException.requireNonNull(requesterNodeId, "requester node ID");
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.requesterNodeIdentity().equals(requester)) {
                if (rec.state().isActionableForRequester()) {
                    list.add(rec);
                }
            }
        }
        return List.copyOf(list);
    }

    /**
     * Returns all capability requests created by a requester node, regardless of state.
     *
     * @param requesterNodeId requester node ID
     * @return list of all capability requests for requester
     */
    public synchronized List<CapabilityRequestRecord> findAllForRequester(String requesterNodeId) {
        if (requesterNodeId == null) {
            return List.of();
        }
        return findAllForRequester(new NodeId(requesterNodeId));
    }

    /**
     * Returns all capability requests created by a typed requester node.
     *
     * @param requesterNodeId requester node identity
     * @return immutable list of all matching requests
     */
    public synchronized List<CapabilityRequestRecord> findAllForRequester(NodeId requesterNodeId) {
        NodeId requester = CoordinationNullException.requireNonNull(requesterNodeId, "requester node ID");
        List<CapabilityRequestRecord> list = new ArrayList<>();
        for (CapabilityRequestRecord rec : records.values()) {
            if (rec.requesterNodeIdentity().equals(requester)) {
                list.add(rec);
            }
        }
        return List.copyOf(list);
    }

    /**
     * Returns the latest implementation revision record for a given handle, if any.
     *
     * @param handleValue public request handle string
     * @return latest revision record when found
     */
    public synchronized Optional<ImplementationRevisionRecord> findLatestImplementation(String handleValue) {
        if (handleValue == null || handleValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return findLatestImplementation(CapabilityRequestHandle.parse(handleValue));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns the current implementation revision for a typed request handle.
     *
     * @param handle validated request identity
     * @return current implementation revision when present
     */
    public synchronized Optional<ImplementationRevisionRecord> findLatestImplementation(CapabilityRequestHandle handle) {
        CapabilityRequestHandle identity = CoordinationNullException.requireNonNull(handle, "handle");
        ImplementationRevision revision = latestRevision.get(identity);
        return revision == null ? Optional.empty()
                : Optional.ofNullable(implementations.get(new ImplementationRevisionKey(identity, revision)));
    }

    /**
     * Returns a specific implementation revision record, if any.
     *
     * @param handleValue    public request handle string
     * @param revisionNumber revision number to look up
     * @return revision record when found
     */
    public synchronized Optional<ImplementationRevisionRecord> findImplementation(String handleValue,
            int revisionNumber) {
        if (handleValue == null || handleValue.isBlank()) {
            return Optional.empty();
        }
        if (revisionNumber < 1) {
            return Optional.empty();
        }
        try {
            return findImplementation(CapabilityRequestHandle.parse(handleValue), revisionNumber);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns one implementation revision by typed request handle and revision number.
     *
     * @param handle         validated request identity
     * @param revisionNumber one-based implementation revision number
     * @return revision record when found
     */
    public synchronized Optional<ImplementationRevisionRecord> findImplementation(CapabilityRequestHandle handle,
            int revisionNumber) {
        if (revisionNumber < 1) {
            return Optional.empty();
        }
        return findImplementation(handle, new ImplementationRevision(revisionNumber));
    }

    /**
     * Returns one implementation revision by typed request handle and revision identity.
     *
     * @param handle   request handle
     * @param revision implementation revision
     * @return revision record when found
     */
    public synchronized Optional<ImplementationRevisionRecord> findImplementation(CapabilityRequestHandle handle,
            ImplementationRevision revision) {
        return Optional.ofNullable(implementations.get(new ImplementationRevisionKey(CoordinationNullException.requireNonNull(
                handle,
                "handle"), CoordinationNullException.requireNonNull(revision, "implementation revision"))));
    }

    /**
     * Returns the active validation context for a given handle, if any.
     *
     * @param handleValue public request handle string
     * @return active validation context when found
     */
    public synchronized Optional<ValidationContextRecord> findValidationContext(String handleValue) {
        if (handleValue == null || handleValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return findValidationContext(CapabilityRequestHandle.parse(handleValue));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns the active validation context for a typed request handle.
     *
     * @param handle validated request identity
     * @return validation context when active
     */
    public synchronized Optional<ValidationContextRecord> findValidationContext(CapabilityRequestHandle handle) {
        return Optional.ofNullable(validationContexts.get(CoordinationNullException.requireNonNull(handle, "handle")));
    }

    /**
     * Returns all active validation contexts; used by diagnostics to discover abandoned worktrees.
     *
     * @return immutable map of handleKey to validation context
     */
    public synchronized Map<String, ValidationContextRecord> allValidationContexts() {
        Map<String, ValidationContextRecord> snapshot = new LinkedHashMap<>();
        validationContexts.forEach((handle, context) -> snapshot.put(handle.value(), context));
        return Map.copyOf(snapshot);
    }

    /**
     * Returns a stable snapshot of all capability request records.
     *
     * @return immutable map of handle to record
     */
    public synchronized Map<String, CapabilityRequestRecord> records() {
        Map<String, CapabilityRequestRecord> snapshot = new LinkedHashMap<>();
        records.forEach((handle, record) -> snapshot.put(handle.value(), record));
        return Map.copyOf(snapshot);
    }

    /**
     * Returns all capability requests keyed by typed request handle.
     *
     * @return immutable typed record map
     */
    public synchronized Map<CapabilityRequestHandle, CapabilityRequestRecord> recordsByHandle() {
        return Map.copyOf(records);
    }

    /**
     * Applies the created event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCreated(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestHandle handleKey = payload.handle();
        if (records.containsKey(handleKey)) {
            throw new CoordinationException("DUPLICATE_CAPABILITY_REQUEST_HANDLE");
        }
        CapabilityRequestRecord rec = new CapabilityRequestRecord(payload.handle(),
                payload.capability(),
                payload.requesterNodeId(),
                payload.requesterSupervisorId(),
                payload.requesterWorkerId(),
                payload.ownerNodeId(),
                payload.ownerSupervisorId(),
                payload.ownerWorkerId(),
                payload.authorityLineageId(),
                payload.contract(),
                payload.state(),
                payload.reason(),
                event.createdAtEpochMillis(),
                event.createdAtEpochMillis(),
                event.sequence());
        records.put(handleKey, rec);
    }

    /**
     * Applies the revised event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processRevised(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestRecord current = requireRequest(payload,
                CapabilityLifecycleState::isUnderNegotiation,
                "TERMINAL_CAPABILITY_REQUEST_CANNOT_BE_REVISED");
        if (!payload.state().isUnderNegotiation()) {
            throw new CoordinationException("INVALID_CAPABILITY_CONTRACT_REVISION_STATE");
        }
        records.put(payload.handle(),
                current.withUpdate(payload.state(),
                        payload.contract(),
                        payload.reason(),
                        event.createdAtEpochMillis(),
                        event.sequence()));
    }

    /**
     * Applies the accepted event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processAccepted(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestRecord current = requireRequest(payload,
                CapabilityLifecycleState::isUnderNegotiation,
                "STALE_CAPABILITY_REQUEST_ACCEPTANCE");
        if (!current.contract().isEquivalent(payload.contract())) {
            throw new CoordinationException("CAPABILITY_CONTRACT_REVISION_MISMATCH");
        }
        records.put(payload.handle(),
                current.withUpdate(CapabilityLifecycleState.ACCEPTED,
                        payload.contract(),
                        null,
                        event.createdAtEpochMillis()));
    }

    /**
     * Applies the rejected event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processRejected(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestRecord current = requireRequest(payload,
                state -> state == CapabilityLifecycleState.AWAITING_OWNER,
                "STALE_CAPABILITY_REQUEST_REJECTION");
        records.put(payload.handle(),
                current.withUpdate(CapabilityLifecycleState.REJECTED,
                        null,
                        payload.reason(),
                        event.createdAtEpochMillis()));
    }

    /**
     * Applies the cancelled event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCancelled(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestRecord current = requireRequest(payload,
                CapabilityLifecycleState::isUnderNegotiation,
                "STALE_CAPABILITY_REQUEST_CANCELLATION");
        records.put(payload.handle(),
                current.withUpdate(CapabilityLifecycleState.CANCELLED,
                        null,
                        payload.reason(),
                        event.createdAtEpochMillis()));
    }

    /**
     * Applies the superseded event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processSuperseded(PredictionEvent event) throws IOException {
        CapabilityRequestPayload payload = CapabilityRequestPayload.decode(event.payload());
        CapabilityRequestRecord current = requireRequest(payload, state -> true, null);
        records.put(payload.handle(),
                current.withUpdate(CapabilityLifecycleState.SUPERSEDED,
                        null,
                        payload.reason(),
                        event.createdAtEpochMillis()));
    }

    /**
     * Applies the implementation published event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processImplementationPublished(PredictionEvent event) throws IOException {
        ImplementationEventPayload payload = ImplementationEventPayload.decode(event.payload());
        CapabilityRequestHandle handleKey = payload.handle();
        CapabilityRequestRecord current = requireRecord(handleKey);
        if (!current.authorityLineageIdentity().equals(payload.authorityLineageIdentity())) {
            throw new CoordinationException("CAPABILITY_LINEAGE_MISMATCH");
        }
        if (!current.state().acceptsImplementation()) {
            throw new CoordinationException("CAPABILITY_REQUEST_NOT_ACCEPTED");
        }
        ImplementationRevision latest = latestRevision.get(handleKey);
        int expectedRevision = latest == null ? 1 : latest.value() + 1;
        if (payload.revisionNumber() != expectedRevision) {
            throw new CoordinationException("CAPABILITY_IMPLEMENTATION_REVISION_STALE");
        }
        // Transition to IMPLEMENTATION_AVAILABLE
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
                null,
                null,
                event.createdAtEpochMillis());
        records.put(handleKey, updated);

        ImplementationRevision revision = payload.revisionIdentity();
        ImplementationRevisionKey revKey = new ImplementationRevisionKey(handleKey, revision);
        // Idempotency is valid only for the same immutable publisher lineage
        // and commit.  A stale or unrelated publisher must never overwrite a
        // revision number that has already become authoritative.
        ImplementationRevisionRecord existing = implementations.get(revKey);
        if (existing != null && (!existing.authorityLineageIdentity().equals(payload.authorityLineageIdentity())
                || !existing.commitSha().equals(payload.commitSha()))) {
            throw new CoordinationException("CAPABILITY_PUBLISHER_STALE");
        }
        if (existing == null) {
            implementations.put(revKey,
                    new ImplementationRevisionRecord(payload.handle(),
                            payload.authorityLineageId(),
                            revision.value(),
                            payload.baseCommit(),
                            payload.commitSha(),
                            payload.changedPaths(),
                            payload.summary(),
                            event.createdAtEpochMillis()));
        }
        latestRevision.put(handleKey, revision);
    }

    /**
     * Applies the validation started event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processValidationStarted(PredictionEvent event) throws IOException {
        ImplementationEventPayload payload = ImplementationEventPayload.decode(event.payload());
        CapabilityRequestHandle handleKey = payload.handle();
        CapabilityRequestRecord current = requireRecord(handleKey);
        requireCurrentImplementation(current, payload, CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE, CapabilityLifecycleState.VALIDATING);
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.VALIDATING,
                null,
                null,
                event.createdAtEpochMillis());
        records.put(handleKey, updated);
        // Store the validation context including worktree path for restart recovery
        if (!payload.worktreePath().isBlank()) {
            validationContexts.put(handleKey,
                    new ValidationContextRecord(payload.handle(),
                            payload.revisionNumber(),
                            payload.worktreePath(),
                            payload.requesterSessionId(),
                            event.createdAtEpochMillis()));
        }
    }

    /**
     * Applies the validated event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processValidated(PredictionEvent event) throws IOException {
        ImplementationEventPayload payload = ImplementationEventPayload.decode(event.payload());
        CapabilityRequestHandle handleKey = payload.handle();
        CapabilityRequestRecord current = requireRecord(handleKey);
        requireCurrentImplementation(current, payload, CapabilityLifecycleState.VALIDATING);
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.VALIDATED,
                null,
                null,
                event.createdAtEpochMillis());
        records.put(handleKey, updated);
        validationContexts.remove(handleKey);
    }

    /**
     * Applies the revision required event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processRevisionRequired(PredictionEvent event) throws IOException {
        ImplementationEventPayload payload = ImplementationEventPayload.decode(event.payload());
        CapabilityRequestHandle handleKey = payload.handle();
        CapabilityRequestRecord current = requireRecord(handleKey);
        requireCurrentImplementation(current, payload, CapabilityLifecycleState.VALIDATING);
        String reason =
                payload.validationReason().isBlank() ? "Revision required by requester" : payload.validationReason();
        CapabilityRequestRecord updated = current.withUpdate(CapabilityLifecycleState.IMPLEMENTING,
                null,
                reason,
                event.createdAtEpochMillis());
        records.put(handleKey, updated);
        validationContexts.remove(handleKey);
    }

    private CapabilityRequestRecord requireRecord(CapabilityRequestHandle handle) throws IOException {
        CapabilityRequestRecord current = records.get(handle);
        if (current == null) {
            throw new CoordinationException("CAPABILITY_REQUEST_NOT_FOUND");
        }
        return current;
    }

    /**
     * Looks up the request a contract event names, fences its contract revision, then requires its state to be
     * one the event may leave; the checks run in this order because it decides which error a caller sees.
     */
    private CapabilityRequestRecord requireRequest(CapabilityRequestPayload payload,
            Predicate<CapabilityLifecycleState> allowedFrom,
            String staleCode) throws IOException {
        CapabilityRequestRecord current = requireRecord(payload.handle());
        requireExpectedContractRevision(current, payload);
        if (!allowedFrom.test(current.state())) {
            throw new CoordinationException(staleCode);
        }
        return current;
    }

    private static void requireExpectedContractRevision(CapabilityRequestRecord current,
            CapabilityRequestPayload payload) throws IOException {
        if (payload.expectedContractRevision() != current.contractRevision()) {
            throw new CoordinationException("CAPABILITY_CONTRACT_REVISION_STALE");
        }
    }

    private void requireCurrentImplementation(CapabilityRequestRecord current,
            ImplementationEventPayload payload,
            CapabilityLifecycleState... allowedStates) throws IOException {
        boolean stateAllowed = false;
        for (CapabilityLifecycleState allowedState : allowedStates) {
            stateAllowed |= current.state() == allowedState;
        }
        ImplementationRevision latest = latestRevision.get(current.handle());
        ImplementationRevisionRecord implementation = latest == null ? null : implementations.get(new ImplementationRevisionKey(current.handle(), latest));
        if (!stateAllowed || latest == null || implementation == null || latest.value() != payload.revisionNumber()
                || !current.authorityLineageId().equals(payload.authorityLineageId())
                || !implementation.authorityLineageId().equals(payload.authorityLineageId())
                || !implementation.commitSha().equals(payload.commitSha())) {
            throw new CoordinationException("CAPABILITY_VALIDATION_STALE");
        }
    }

    /** Transfers actionable capability actors as part of the signed lane continuation event. */
    private void processLaneContinuation(PredictionEvent event) throws IOException {
        LaneContinuation continuation = CollaborationCodec.decodeContinuation(event.payload());
        CapabilityActorScope source = continuation.sourceActorScope();
        CapabilityActorScope successor = continuation.targetActorScope();
        if (source == null || successor == null) {
            return;
        }
        for (Map.Entry<CapabilityRequestHandle, CapabilityRequestRecord> entry : List.copyOf(records.entrySet())) {
            CapabilityRequestRecord current = entry.getValue();
            // A request its owner superseded by finishing binds nobody, yet the requester's
            // successor is the one who has to learn that the owner went ahead and what it last
            // proposed. Left with the predecessor, the successor would see a dependency it
            // apparently never requested and no explanation. The owner's side is over.
            boolean informsRequester = current.ownerFinishedWithoutAnswer() && current.matchesRequester(source);
            if (!current.state().bindsLanes() && !informsRequester) {
                continue;
            }
            boolean requester = current.matchesRequester(source);
            boolean owner = !informsRequester && current.matchesOwner(source);
            if (!requester && !owner) {
                continue;
            }
            if (requester && owner) {
                throw new CoordinationException("CAPABILITY_CONTINUATION_SELF_OWNERSHIP_FORBIDDEN");
            }
            CapabilityRequestRecord updated = requester
                    ? current.withRequesterActor(successor)
                    : current.withOwnerActor(successor);
            if (updated.matchesRequester(successor) && updated.matchesOwner(successor)) {
                throw new CoordinationException("CAPABILITY_CONTINUATION_SELF_OWNERSHIP_FORBIDDEN");
            }
            if (requester && current.state() == CapabilityLifecycleState.VALIDATING) {
                // A validation workspace belongs to the predecessor session. Keep the exact
                // implementation revision, but require the successor to inspect it again.
                updated = updated.withUpdate(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
                        null,
                        null,
                        event.createdAtEpochMillis());
                validationContexts.remove(current.handle());
            }
            records.put(entry.getKey(), updated);
        }
    }

    /**
     * Distinguishes one immutable implementation revision from every other revision.
     */
    private record ImplementationRevisionKey(CapabilityRequestHandle handle, ImplementationRevision revision) {

    }
}
