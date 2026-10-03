package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.IntentVersion;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.ProviderId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Immutable authenticated declaration of one worker's intended change.
 *
 * @param intentId              intent identifier
 * @param projectId             project identifier
 * @param participant           opaque participant handle
 * @param provider              provider identifier
 * @param taskId                task identifier
 * @param goal                  work goal
 * @param acceptance            acceptance criteria
 * @param baseCommit            base commit
 * @param selectors             requested resource selectors
 * @param version               intent version
 * @param workGroupId           logical work-group parent
 * @param authorityLineageId    durable authority lineage shared by authorized successor lanes
 * @param status                lifecycle status
 * @param role                  semantic role of the intent in the work group
 * @param reviewTargetSelectors non-ownership selectors identifying producer work this reviewer may review
 * @param knownDependencies     capability identifiers this intent explicitly requires
 */
public record WorkIntent(UUID intentId, UUID projectId, String participant, String provider, UUID taskId, String goal,
                         String acceptance, String baseCommit, List<ResourceSelector> selectors, long version,
                         UUID workGroupId, UUID authorityLineageId, Status status, Role role,
                         List<ResourceSelector> reviewTargetSelectors, List<String> knownDependencies) {

    /**
     * Constructs a singleton work-group intent when no parent group is supplied.
     *
     * @param intentId    intent ID
     * @param projectId   project ID
     * @param participant participant
     * @param provider    provider
     * @param taskId      task ID
     * @param goal        goal
     * @param acceptance  acceptance
     * @param baseCommit  base commit
     * @param selectors   selectors
     * @param version     version
     * @param status      status
     */
    public WorkIntent(UUID intentId,
            UUID projectId,
            String participant,
            String provider,
            UUID taskId,
            String goal,
            String acceptance,
            String baseCommit,
            List<ResourceSelector> selectors,
            long version,
            Status status) {
        this(intentId,
                projectId,
                participant,
                provider,
                taskId,
                goal,
                acceptance,
                baseCommit,
                selectors,
                version,
                intentId,
                defaultAuthorityLineage(intentId),
                status,
                Role.PRODUCER,
                List.of(),
                List.of());
    }

    /**
     * Constructs an intent with a logical work-group and explicit authority lineage.
     *
     * @param intentId    intent ID
     * @param projectId   project ID
     * @param participant participant
     * @param provider    provider
     * @param taskId      task ID
     * @param goal        goal
     * @param acceptance  acceptance
     * @param baseCommit  base commit
     * @param selectors   selectors
     * @param version     intent version
     * @param workGroupId work-group ID
     * @param status      lifecycle status
     */
    public WorkIntent(UUID intentId,
            UUID projectId,
            String participant,
            String provider,
            UUID taskId,
            String goal,
            String acceptance,
            String baseCommit,
            List<ResourceSelector> selectors,
            long version,
            UUID workGroupId,
            Status status) {
        this(intentId,
                projectId,
                participant,
                provider,
                taskId,
                goal,
                acceptance,
                baseCommit,
                selectors,
                version,
                workGroupId,
                defaultAuthorityLineage(intentId),
                status,
                Role.PRODUCER,
                List.of(),
                List.of());
    }

    /**
     * Constructs an intent with explicit authority lineage and the default producer role.
     *
     * @param intentId           intent ID
     * @param projectId          project ID
     * @param participant        participant
     * @param provider           provider
     * @param taskId             task ID
     * @param goal               goal
     * @param acceptance         acceptance
     * @param baseCommit         base commit
     * @param selectors          selectors
     * @param version            intent version
     * @param workGroupId        work-group ID
     * @param authorityLineageId authority lineage
     * @param status             lifecycle status
     */
    public WorkIntent(UUID intentId,
            UUID projectId,
            String participant,
            String provider,
            UUID taskId,
            String goal,
            String acceptance,
            String baseCommit,
            List<ResourceSelector> selectors,
            long version,
            UUID workGroupId,
            UUID authorityLineageId,
            Status status) {
        this(intentId,
                projectId,
                participant,
                provider,
                taskId,
                goal,
                acceptance,
                baseCommit,
                selectors,
                version,
                workGroupId,
                authorityLineageId,
                status,
                Role.PRODUCER,
                List.of(),
                List.of());
    }

    /**
     * Constructs an intent with an explicit semantic role and no review target selectors.
     *
     * @param intentId           intent ID
     * @param projectId          project ID
     * @param participant        participant
     * @param provider           provider
     * @param taskId             task ID
     * @param goal               goal
     * @param acceptance         acceptance
     * @param baseCommit         base commit
     * @param selectors          selectors
     * @param version            intent version
     * @param workGroupId        work-group ID
     * @param authorityLineageId authority lineage
     * @param status             lifecycle status
     * @param role               semantic role
     */
    public WorkIntent(UUID intentId,
            UUID projectId,
            String participant,
            String provider,
            UUID taskId,
            String goal,
            String acceptance,
            String baseCommit,
            List<ResourceSelector> selectors,
            long version,
            UUID workGroupId,
            UUID authorityLineageId,
            Status status,
            Role role) {
        this(intentId,
                projectId,
                participant,
                provider,
                taskId,
                goal,
                acceptance,
                baseCommit,
                selectors,
                version,
                workGroupId,
                authorityLineageId,
                status,
                role,
                List.of(),
                List.of());
    }

    /**
     * Constructs an intent with the historical review-target shape.
     *
     * @param intentId              intent ID
     * @param projectId             project ID
     * @param participant           participant
     * @param provider              provider
     * @param taskId                task ID
     * @param goal                  goal
     * @param acceptance            acceptance criteria
     * @param baseCommit            base commit
     * @param selectors             resource selectors
     * @param version               intent version
     * @param workGroupId           work-group ID
     * @param authorityLineageId    authority lineage
     * @param status                lifecycle status
     * @param role                  semantic role
     * @param reviewTargetSelectors review-target selectors
     */
    public WorkIntent(UUID intentId,
            UUID projectId,
            String participant,
            String provider,
            UUID taskId,
            String goal,
            String acceptance,
            String baseCommit,
            List<ResourceSelector> selectors,
            long version,
            UUID workGroupId,
            UUID authorityLineageId,
            Status status,
            Role role,
            List<ResourceSelector> reviewTargetSelectors) {
        this(intentId,
                projectId,
                participant,
                provider,
                taskId,
                goal,
                acceptance,
                baseCommit,
                selectors,
                version,
                workGroupId,
                authorityLineageId,
                status,
                role,
                reviewTargetSelectors,
                List.of());
    }

    /**
     * Validates bounds and immutable collections.
     */
    public WorkIntent {
        CoordinationNullException.requireNonNull(intentId, "intentId");
        CoordinationNullException.requireNonNull(projectId, "projectId");
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        require(participant, "participant");
        require(provider, "provider");
        CoordinationNullException.requireNonNull(taskId, "taskId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        require(baseCommit, "baseCommit");
        CoordinationNullException.requireNonNull(selectors, "selectors");
        if (selectors.isEmpty() || selectors.size() > 128) {
            throw new CoordinationArgumentException("intent must contain 1..128 selectors");
        }
        selectors = List.copyOf(selectors);
        if (version < 1) {
            throw new CoordinationArgumentException("intent version must be positive");
        }
        CoordinationNullException.requireNonNull(status, "status");
        CoordinationNullException.requireNonNull(role, "role");
        CoordinationNullException.requireNonNull(reviewTargetSelectors, "reviewTargetSelectors");
        if (reviewTargetSelectors.size() > 128) {
            throw new CoordinationArgumentException("review target selector bound");
        }
        reviewTargetSelectors = List.copyOf(reviewTargetSelectors);
        CoordinationNullException.requireNonNull(knownDependencies, "knownDependencies");
        if (knownDependencies.size() > 50 || knownDependencies.stream()
                .anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new CoordinationArgumentException("known dependency bound");
        }
        knownDependencies = List.copyOf(knownDependencies);
        if (role == Role.PRODUCER && !reviewTargetSelectors.isEmpty()) {
            throw new CoordinationArgumentException("producer intent cannot declare review targets");
        }
    }

    /**
     * Derives a stable singleton lineage for intents replayed without an explicit lineage field.
     *
     * @param intentId intent identifier
     * @return deterministic lineage identifier
     */
    public static UUID defaultAuthorityLineage(UUID intentId) {
        CoordinationNullException.requireNonNull(intentId, "intentId");
        return UUID.nameUUIDFromBytes(("synesis-authority-lineage:" + intentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks that the type value satisfies this type's bounds.
     */
    private static void require(String value, String name) {
        CoordinationNullException.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new CoordinationArgumentException(name + " is empty or exceeds bound");
        }
    }

    /**
     * Returns the typed work-intent identity.
     *
     * @return the typed work-intent identity.
     */
    public WorkIntentId identity() {
        return new WorkIntentId(intentId);
    }

    /**
     * Returns the typed project identity.
     *
     * @return the typed project identity.
     */
    public ProjectId project() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the participant-incarnation identity.
     *
     * @return the participant-incarnation identity.
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Requires the supplied participant to own this intent.
     *
     * @param owner participant identity to check
     * @throws CoordinationException when the participant is not the intent owner
     */
    public void requireOwnedBy(ParticipantId owner) throws CoordinationException {
        ParticipantId exactOwner = CoordinationNullException.requireNonNull(owner, "owner");
        if (!participantIdentity().equals(exactOwner)) {
            throw new CoordinationException("INTENT_OWNER_MISMATCH");
        }
    }

    /**
     * Returns the provider name with its identity type.
     *
     * @return the provider name with its identity type.
     */
    public ProviderId providerIdentity() {
        return new ProviderId(provider);
    }

    /**
     * Returns the typed task identity.
     *
     * @return the typed task identity.
     */
    public TaskId task() {
        return new TaskId(taskId);
    }

    /**
     * Returns the typed work-group identity.
     *
     * @return the typed work-group identity.
     */
    public WorkGroupId group() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the durable identity shared by authorized successors.
     *
     * @return the durable identity shared by authorized successors.
     */
    public AuthorityLineageId lineage() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Returns the base Git commit as a freshness-bound identity.
     *
     * @return the base Git commit as a freshness-bound identity.
     */
    public CommitId baseCommitIdentity() {
        return new CommitId(baseCommit);
    }

    /**
     * Returns the intent version as a domain type.
     *
     * @return the intent version as a domain type.
     */
    public IntentVersion intentVersion() {
        return new IntentVersion(version);
    }

    /**
     * Returns the version in the claim-epoch domain used by continuation authority.
     *
     * @return the version in the claim-epoch domain used by continuation authority.
     */
    public ClaimEpoch claimEpoch() {
        return new ClaimEpoch(version);
    }

    /**
     * Intent lifecycle states.
     */
    public enum Status {
        /**
         * Intent owns its selectors.
         */
        ANNOUNCED
    }

    /**
     * Declares the narrow semantic role used for review routing.
     */
    public enum Role {
        /**
         * Produces mutable implementation work and may publish its lane snapshot.
         */
        PRODUCER("producer", 1),
        /**
         * Reviews a producer's immutable snapshot without owning the producer's files.
         */
        REVIEWER("reviewer", 2);

        private final String wireValue;
        private final int wireCode;

        /**
         * Validates and initializes this role.
         */
        Role(String wireValue, int wireCode) {
            this.wireValue = wireValue;
            this.wireCode = wireCode;
        }

        /**
         * Parses the explicit protocol value.
         *
         * @param value protocol value
         * @return semantic role
         * @throws IllegalArgumentException for an unknown value
         */
        public static Role fromWire(String value) {
            CoordinationNullException.requireNonNull(value, "role");
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Role role : values()) {
                if (role.wireValue.equals(normalized)) {
                    return role;
                }
            }
            throw new CoordinationArgumentException("unknown work intent role: " + value);
        }

        /**
         * Parses the stable binary payload code.
         *
         * @param code binary code
         * @return semantic role
         * @throws IOException for an unknown code
         */
        public static Role fromWireCode(int code) throws IOException {
            for (Role role : values()) {
                if (role.wireCode == code) {
                    return role;
                }
            }
            throw new CoordinationException("unknown work intent role code");
        }

        /**
         * Returns the stable protocol value.
         *
         * @return lowercase protocol value
         */
        public String wireValue() {
            return wireValue;
        }

        /**
         * Returns the stable binary payload code.
         *
         * @return binary code
         */
        public int wireCode() {
            return wireCode;
        }
    }

}
