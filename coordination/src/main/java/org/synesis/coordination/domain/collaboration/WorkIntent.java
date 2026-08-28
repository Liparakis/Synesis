package org.synesis.coordination.domain.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authenticated declaration of one worker's intended change.
 * @param intentId intent identifier
 * @param projectId project identifier
 * @param participant opaque participant handle
 * @param provider provider identifier
 * @param taskId task identifier
 * @param goal work goal
 * @param acceptance acceptance criteria
 * @param baseCommit base commit
 * @param selectors requested resource selectors
 * @param version intent version
 * @param workGroupId logical work-group parent
 * @param authorityLineageId durable authority lineage shared by authorized successor lanes
 * @param status lifecycle status
 * @param completionMode declared completion contract for this intent
 * @param role semantic role of the intent in the work group
 * @param reviewTargetSelectors non-ownership selectors identifying producer work this reviewer may review
 */
public record WorkIntent(UUID intentId, UUID projectId, String participant,
                         String provider, UUID taskId, String goal,
                         String acceptance, String baseCommit,
                         List<ResourceSelector> selectors, long version,
                         UUID workGroupId, UUID authorityLineageId,
                         Status status, CompletionMode completionMode,
                         Role role, List<ResourceSelector> reviewTargetSelectors) {

    /**
     * Derives a stable singleton lineage for intents replayed without an
     * explicit lineage field.
     *
     * @param intentId intent identifier
     * @return deterministic lineage identifier
     */
    public static UUID defaultAuthorityLineage(UUID intentId) {
        Objects.requireNonNull(intentId, "intentId");
        return UUID.nameUUIDFromBytes(("synesis-authority-lineage:" + intentId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Constructs a singleton work-group intent when no parent group is supplied.
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version version
     * @param status status
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version, Status status) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, intentId, defaultAuthorityLineage(intentId), status,
                CompletionMode.SNAPSHOT_REQUIRED, Role.PRODUCER, List.of());
    }

    /**
     * Constructs an intent with a logical work-group and explicit authority
     * lineage.
     *
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version intent version
     * @param workGroupId work-group ID
     * @param status lifecycle status
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version,
                      UUID workGroupId, Status status) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, workGroupId,
                defaultAuthorityLineage(intentId), status, CompletionMode.SNAPSHOT_REQUIRED,
                Role.PRODUCER, List.of());
    }

    /** Constructs an intent with explicit authority lineage and the default snapshot contract.
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version intent version
     * @param workGroupId work-group ID
     * @param authorityLineageId authority lineage
     * @param status lifecycle status
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version,
                      UUID workGroupId, UUID authorityLineageId, Status status) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, workGroupId, authorityLineageId, status,
                CompletionMode.SNAPSHOT_REQUIRED, Role.PRODUCER, List.of());
    }

    /** Constructs an intent with an explicit completion contract and default producer role.
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version intent version
     * @param workGroupId work-group ID
     * @param authorityLineageId authority lineage
     * @param status lifecycle status
     * @param completionMode completion contract
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version,
                      UUID workGroupId, UUID authorityLineageId, Status status,
                      CompletionMode completionMode) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, workGroupId, authorityLineageId, status,
                completionMode, Role.PRODUCER, List.of());
    }

    /** Constructs an intent with an explicit semantic role and no review target selectors.
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version intent version
     * @param workGroupId work-group ID
     * @param authorityLineageId authority lineage
     * @param status lifecycle status
     * @param completionMode completion contract
     * @param role semantic role
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version,
                      UUID workGroupId, UUID authorityLineageId, Status status,
                      CompletionMode completionMode, Role role) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, workGroupId, authorityLineageId, status,
                completionMode, role, List.of());
    }

    /** Intent lifecycle states. */
    public enum Status {
        /** Intent owns its selectors. */
        ANNOUNCED,
        /** Intent has released its selectors. */
        RELEASED
    }

    /** Declares the narrow semantic role used for review routing. */
    public enum Role {
        /** Produces mutable implementation work and may publish its lane snapshot. */
        PRODUCER("producer", 1),
        /** Reviews a producer's immutable snapshot without owning the producer's files. */
        REVIEWER("reviewer", 2);

        private final String wireValue;
        private final int wireCode;

        Role(String wireValue, int wireCode) {
            this.wireValue = wireValue;
            this.wireCode = wireCode;
        }

        /** Returns the stable protocol value.
         * @return lowercase protocol value
         */
        public String wireValue() {
            return wireValue;
        }

        /** Returns the stable binary payload code.
         * @return binary code
         */
        public int wireCode() {
            return wireCode;
        }

        /** Parses the explicit protocol value.
         * @param value protocol value
         * @return semantic role
         * @throws IllegalArgumentException for an unknown value
         */
        public static Role fromWire(String value) {
            Objects.requireNonNull(value, "role");
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            for (Role role : values()) {
                if (role.wireValue.equals(normalized)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("unknown work intent role: " + value);
        }

        /** Parses the stable binary payload code.
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
            throw new IOException("unknown work intent role code");
        }
    }

    /** Declares whether successful completion requires an immutable snapshot. */
    public enum CompletionMode {
        /** Completion must publish the normal immutable task snapshot. */
        SNAPSHOT_REQUIRED("snapshot_required", 1),
        /** Completion may release the intent when the verified worktree is clean. */
        NO_CHANGE_ALLOWED("no_change_allowed", 2);

        private final String wireValue;
        private final int wireCode;

        CompletionMode(String wireValue, int wireCode) {
            this.wireValue = wireValue;
            this.wireCode = wireCode;
        }

        /** Returns the stable protocol value.
         * @return lowercase protocol value
         */
        public String wireValue() {
            return wireValue;
        }

        /** Returns the stable binary payload code.
         * @return binary code
         */
        public int wireCode() {
            return wireCode;
        }

        /** Parses the explicit protocol value.
         * @param value protocol value
         * @return completion mode
         * @throws IllegalArgumentException for an unknown value
         */
        public static CompletionMode fromWire(String value) {
            Objects.requireNonNull(value, "completion mode");
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (normalized) {
                case "snapshot", "snapshot_required" -> SNAPSHOT_REQUIRED;
                case "no_change", "no_change_allowed" -> NO_CHANGE_ALLOWED;
                default -> throw new IllegalArgumentException("unknown completion mode: " + value);
            };
        }

        /** Parses the stable binary payload code.
         * @param code binary code
         * @return completion mode
         * @throws IOException for an unknown code
         */
        public static CompletionMode fromWireCode(int code) throws IOException {
            for (CompletionMode mode : values()) {
                if (mode.wireCode == code) {
                    return mode;
                }
            }
            throw new IOException("unknown completion mode code");
        }
    }

    /** Validates bounds and immutable collections. */
    public WorkIntent {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(authorityLineageId, "authorityLineageId");
        require(participant, "participant");
        require(provider, "provider");
        Objects.requireNonNull(taskId, "taskId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        require(baseCommit, "baseCommit");
        Objects.requireNonNull(selectors, "selectors");
        if (selectors.isEmpty() || selectors.size() > 128) {
            throw new IllegalArgumentException("intent must contain 1..128 selectors");
        }
        selectors = List.copyOf(selectors);
        if (version < 1) {
            throw new IllegalArgumentException("intent version must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(completionMode, "completionMode");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reviewTargetSelectors, "reviewTargetSelectors");
        if (reviewTargetSelectors.size() > 128) {
            throw new IllegalArgumentException("review target selector bound");
        }
        reviewTargetSelectors = List.copyOf(reviewTargetSelectors);
        if (role == Role.PRODUCER && !reviewTargetSelectors.isEmpty()) {
            throw new IllegalArgumentException("producer intent cannot declare review targets");
        }
    }

    private static void require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException(name + " is empty or exceeds bound");
        }
    }
}
