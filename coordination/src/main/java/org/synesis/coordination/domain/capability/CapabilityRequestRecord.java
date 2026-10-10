package org.synesis.coordination.domain.capability;


import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Projected durable state for a Stage 2B capability request.
 *
 * @param handle                public request handle locator
 * @param capability            target capability identifier
 * @param requesterNodeId       authenticated requester node ID
 * @param requesterSupervisorId authenticated requester supervisor ID
 * @param requesterWorkerId     authenticated requester worker ID
 * @param ownerNodeId           assigned semantic owner node ID
 * @param ownerSupervisorId     assigned semantic owner supervisor ID
 * @param ownerWorkerId         assigned semantic owner worker ID
 * @param authorityLineageId    durable authority lineage required for implementation publication
 * @param contract              current contract specification
 * @param state                 current request lifecycle state
 * @param reason                optional rejection or revision reason
 * @param createdAtEpochMillis  creation timestamp
 * @param updatedAtEpochMillis  last modification timestamp
 * @param contractRevision      event sequence that last changed the negotiated API contract
 * @since 1.0
 */
public record CapabilityRequestRecord(CapabilityRequestHandle handle, String capability, String requesterNodeId,
                                      String requesterSupervisorId, String requesterWorkerId, String ownerNodeId,
                                      String ownerSupervisorId, String ownerWorkerId, UUID authorityLineageId,
                                      CapabilityContract contract, CapabilityLifecycleState state, String reason,
                                      long createdAtEpochMillis, long updatedAtEpochMillis, long contractRevision) {

    /**
     * Compact constructor enforcing nullability checks.
     *
     * @param handle                public request handle locator
     * @param capability            target capability identifier
     * @param requesterNodeId       authenticated requester node ID
     * @param requesterSupervisorId authenticated requester supervisor ID
     * @param requesterWorkerId     authenticated requester worker ID
     * @param ownerNodeId           assigned semantic owner node ID
     * @param ownerSupervisorId     assigned semantic owner supervisor ID
     * @param ownerWorkerId         assigned semantic owner worker ID
     * @param authorityLineageId    durable authority lineage required for implementation publication
     * @param contract              current contract specification
     * @param state                 current request lifecycle state
     * @param reason                optional rejection or revision reason
     * @param createdAtEpochMillis  creation timestamp
     * @param updatedAtEpochMillis  last modification timestamp
     * @param contractRevision      event sequence that last changed the contract
     */
    public CapabilityRequestRecord {
        CoordinationNullException.requireNonNull(handle, "handle");
        CoordinationNullException.requireNonNull(capability, "capability");
        CoordinationNullException.requireNonNull(requesterNodeId, "requesterNodeId");
        requesterSupervisorId = requesterSupervisorId == null ? "" : requesterSupervisorId;
        requesterWorkerId = requesterWorkerId == null ? "" : requesterWorkerId;
        CoordinationNullException.requireNonNull(ownerNodeId, "ownerNodeId");
        ownerSupervisorId = ownerSupervisorId == null ? "" : ownerSupervisorId;
        ownerWorkerId = ownerWorkerId == null ? "" : ownerWorkerId;
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(contract, "contract");
        CoordinationNullException.requireNonNull(state, "state");
        if (contractRevision < 0) {
            throw new CoordinationArgumentException("contractRevision must not be negative");
        }
    }

    /**
     * Retains the previous canonical shape for callers without event-derived revision metadata.
     *
     * @param handle public request handle
     * @param capability requested capability identifier
     * @param requesterNodeId authenticated requester node
     * @param requesterSupervisorId authenticated requester supervisor
     * @param requesterWorkerId authenticated requester worker
     * @param ownerNodeId assigned owner node
     * @param ownerSupervisorId assigned owner supervisor
     * @param ownerWorkerId assigned owner worker
     * @param authorityLineageId durable authority lineage
     * @param contract current negotiated API contract
     * @param state request lifecycle state
     * @param reason optional rejection or revision reason
     * @param createdAtEpochMillis creation time
     * @param updatedAtEpochMillis latest update time
     */
    public CapabilityRequestRecord(CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String requesterSupervisorId,
            String requesterWorkerId,
            String ownerNodeId,
            String ownerSupervisorId,
            String ownerWorkerId,
            UUID authorityLineageId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason,
            long createdAtEpochMillis,
            long updatedAtEpochMillis) {
        this(handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                authorityLineageId,
                contract,
                state,
                reason,
                createdAtEpochMillis,
                updatedAtEpochMillis,
                0);
    }

    /**
     * Converts the serialized empty-string sentinel to an absent typed identity.
     *
     * @param value   serialized identity, or the empty optional-field sentinel
     * @param factory identity constructor
     * @param <T>     identity type
     * @return typed identity when present
     */
    private static <T> Optional<T> optionalIdentity(String value, Function<String, T> factory) {
        return value.isBlank() ? Optional.empty() : Optional.of(factory.apply(value));
    }

    /**
     * Converts an optional provider-boundary identity without changing blank-as-absent semantics.
     *
     * @param value   caller identity value
     * @param factory identity constructor
     * @param <T>     identity type
     * @return identity when the caller supplied a nonblank value
     */
    private static <T> T callerIdentity(String value, Function<String, T> factory) {
        return value == null || value.isBlank() ? null : factory.apply(value);
    }

    /**
     * Reports whether two optional scoped identities are both present and disagree.
     *
     * @param expected recorded scope identity
     * @param caller   supplied scope identity
     * @param <T>      identity type
     * @return true when both scopes are set to different identities
     */
    private static <T> boolean scopesDiffer(T expected, T caller) {
        return expected != null && caller != null && !expected.equals(caller);
    }

    /**
     * Returns the requested capability as a typed identity.
     *
     * @return capability identity
     */
    public CapabilityId capabilityIdentity() {
        return new CapabilityId(capability);
    }

    /**
     * Returns the authenticated requester node identity.
     *
     * @return requester node identity
     */
    public NodeId requesterNodeIdentity() {
        return new NodeId(requesterNodeId);
    }

    /**
     * Returns the optional requester supervisor identity.
     *
     * @return requester supervisor identity when present
     */
    Optional<SupervisorId> requesterSupervisorIdentity() {
        return optionalIdentity(requesterSupervisorId, SupervisorId::new);
    }

    /**
     * Returns the optional requester worker identity.
     *
     * @return requester worker identity when present
     */
    Optional<WorkerId> requesterWorkerIdentity() {
        return optionalIdentity(requesterWorkerId, WorkerId::new);
    }

    /**
     * Returns the assigned semantic owner node identity.
     *
     * @return owner node identity
     */
    NodeId ownerNodeIdentity() {
        return new NodeId(ownerNodeId);
    }

    /**
     * Returns the optional owner supervisor identity.
     *
     * @return owner supervisor identity when present
     */
    Optional<SupervisorId> ownerSupervisorIdentity() {
        return optionalIdentity(ownerSupervisorId, SupervisorId::new);
    }

    /**
     * Returns the optional owner worker identity.
     *
     * @return owner worker identity when present
     */
    Optional<WorkerId> ownerWorkerIdentity() {
        return optionalIdentity(ownerWorkerId, WorkerId::new);
    }

    /**
     * Returns the durable implementation authority lineage.
     *
     * @return authority lineage identity
     */
    AuthorityLineageId authorityLineageIdentity() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Creates an updated record with a new state, contract, and modification timestamp.
     *
     * @param newState           updated lifecycle state
     * @param newContract        updated contract specification
     * @param newReason          updated reason message
     * @param updatedEpochMillis current modification timestamp
     * @return updated record instance
     */
    public CapabilityRequestRecord withUpdate(CapabilityLifecycleState newState,
            CapabilityContract newContract,
            String newReason,
            long updatedEpochMillis) {
        return new CapabilityRequestRecord(handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                authorityLineageId,
                newContract != null ? newContract : contract,
                newState,
                newReason,
                createdAtEpochMillis,
                updatedEpochMillis,
                contractRevision);
    }

    /**
     * Transfers only the exact requester actor fields into a verified successor binding.
     *
     * @param successor verified successor actor scope
     * @return a copy retaining contract, state, and revision history
     */
    CapabilityRequestRecord withRequesterActor(CapabilityActorScope successor) {
        CoordinationNullException.requireNonNull(successor, "successor");
        return new CapabilityRequestRecord(handle,
                capability,
                successor.nodeId(),
                successor.supervisorId(),
                successor.workerId(),
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                authorityLineageId,
                contract,
                state,
                reason,
                createdAtEpochMillis,
                updatedAtEpochMillis,
                contractRevision);
    }

    /**
     * Transfers only the exact owner actor fields into a verified successor binding.
     *
     * @param successor verified successor actor scope
     * @return a copy retaining contract, state, and revision history
     */
    CapabilityRequestRecord withOwnerActor(CapabilityActorScope successor) {
        CoordinationNullException.requireNonNull(successor, "successor");
        return new CapabilityRequestRecord(handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                successor.nodeId(),
                successor.supervisorId(),
                successor.workerId(),
                authorityLineageId,
                contract,
                state,
                reason,
                createdAtEpochMillis,
                updatedAtEpochMillis,
                contractRevision);
    }

    /**
     * Reason recorded on a request that its owner superseded by finishing without the
     * requester's answer to a contract the owner had proposed.
     */
    public static final String OWNER_FINISHED_WITHOUT_ANSWER = "OWNER_FINISHED_WITHOUT_REQUESTER_ANSWER";

    /**
     * Reports whether this request ended because its owner finished without the requester's
     * answer.
     *
     * <p>Such a request binds nobody, but it is the only record of what the owner last proposed
     * and of why the requester holds no agreement, so the requester still has to be told.</p>
     *
     * @return {@code true} for a request superseded for that reason
     */
    public boolean ownerFinishedWithoutAnswer() {
        return state == CapabilityLifecycleState.SUPERSEDED && OWNER_FINISHED_WITHOUT_ANSWER.equals(reason);
    }

    /**
     * Checks whether this request has the exact stored requester actor fields.
     *
     * @param scope authenticated actor scope to compare
     * @return {@code true} when node, supervisor, and worker identities all match
     */
    public boolean matchesRequester(CapabilityActorScope scope) {
        return scope.matches(requesterNodeId, requesterSupervisorId, requesterWorkerId);
    }

    /**
     * Checks whether this request has the exact stored owner actor fields.
     *
     * @param scope authenticated actor scope to compare
     * @return {@code true} when node, supervisor, and worker identities all match
     */
    public boolean matchesOwner(CapabilityActorScope scope) {
        return scope.matches(ownerNodeId, ownerSupervisorId, ownerWorkerId);
    }

    /**
     * Creates an update whose contract revision is the event sequence that records it.
     *
     * @param newState replacement lifecycle state
     * @param newContract replacement contract, or {@code null} to retain the existing contract
     * @param newReason replacement reason
     * @param updatedEpochMillis update timestamp
     * @param newContractRevision event sequence for the new contract revision
     * @return updated capability request record
     */
    public CapabilityRequestRecord withUpdate(CapabilityLifecycleState newState,
            CapabilityContract newContract,
            String newReason,
            long updatedEpochMillis,
            long newContractRevision) {
        return new CapabilityRequestRecord(handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                authorityLineageId,
                newContract != null ? newContract : contract,
                newState,
                newReason,
                createdAtEpochMillis,
                updatedEpochMillis,
                newContractRevision);
    }

    /**
     * Verifies if a given caller worker matches the requester actor boundary.
     *
     * @param callerNodeId       caller node ID
     * @param callerSupervisorId caller supervisor ID
     * @param callerWorkerId     caller worker ID
     * @return true if authorized as requester
     */
    public boolean matchesRequester(String callerNodeId, String callerSupervisorId, String callerWorkerId) {
        if (callerNodeId == null) {
            return false;
        }
        return matchesRequester(new NodeId(callerNodeId),
                callerIdentity(callerSupervisorId, SupervisorId::new),
                callerIdentity(callerWorkerId, WorkerId::new));
    }

    /**
     * Verifies a requester using typed identities; absent supervisor or worker values are {@code null}.
     *
     * @param callerNodeId       caller node identity
     * @param callerSupervisorId caller supervisor identity, or {@code null} when unavailable
     * @param callerWorkerId     caller worker identity, or {@code null} when unavailable
     * @return true if authorized as requester
     */
    public boolean matchesRequester(NodeId callerNodeId, SupervisorId callerSupervisorId, WorkerId callerWorkerId) {
        NodeId callerNode = CoordinationNullException.requireNonNull(callerNodeId, "caller node ID");
        if (!requesterNodeIdentity().equals(callerNode) || scopesDiffer(requesterWorkerIdentity().orElse(null),
                callerWorkerId)) {
            return false;
        }
        return !scopesDiffer(requesterSupervisorIdentity().orElse(null), callerSupervisorId);
    }

    /**
     * Verifies if a given caller worker matches the owner actor boundary.
     *
     * @param callerNodeId       caller node ID
     * @param callerSupervisorId caller supervisor ID
     * @param callerWorkerId     caller worker ID
     * @return true if authorized as owner
     */
    public boolean matchesOwner(String callerNodeId, String callerSupervisorId, String callerWorkerId) {
        if (callerNodeId == null) {
            return false;
        }
        return matchesOwner(new NodeId(callerNodeId),
                callerIdentity(callerSupervisorId, SupervisorId::new),
                callerIdentity(callerWorkerId, WorkerId::new));
    }

    /**
     * Verifies an owner using typed identities; absent supervisor or worker values are {@code null}.
     *
     * @param callerNodeId       caller node identity
     * @param callerSupervisorId caller supervisor identity, or {@code null} when unavailable
     * @param callerWorkerId     caller worker identity, or {@code null} when unavailable
     * @return true if authorized as owner
     */
    public boolean matchesOwner(NodeId callerNodeId, SupervisorId callerSupervisorId, WorkerId callerWorkerId) {
        NodeId callerNode = CoordinationNullException.requireNonNull(callerNodeId, "caller node ID");
        if (!ownerNodeIdentity().equals(callerNode) || scopesDiffer(ownerWorkerIdentity().orElse(null),
                callerWorkerId)) {
            return false;
        }
        return !scopesDiffer(ownerSupervisorIdentity().orElse(null), callerSupervisorId);
    }
}
