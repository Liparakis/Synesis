package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.CombinePayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/**
 * Who may take a combine lane, and taking it (ADR-0098 step 5, designer item 22; ADR-0100 "The combine lane"). A
 * combine is offered by the {@code ensure_session} call that joins a repair lane, naming one of the combined advance's
 * snapshots, so an agent takes it with the tool it already has.
 */
public final class CombineLaneJoin {

    private CombineLaneJoin() {
    }

    /**
     * The combine a session's repair join names: one whose advance holds the snapshot and that still waits for its
     * lane, or whose lane this session opened already (a repeated call).
     *
     * @param store      current projection
     * @param snapshotId a snapshot of the combined advance
     * @param sessionId  the joining session
     * @return the newest such combine, or empty when the snapshot is in none
     */
    static Optional<CombinePayload> openFor(PredictionEventStore store, String snapshotId, String sessionId) {
        return store.taskCompletionProjection().combines().stream().filter(combine -> combine.snapshotIds().contains(snapshotId))
                .filter(combine -> waiting(store, combine) || store.collaborationProjection().intent(laneId(combine, sessionId)).isPresent())
                .reduce((older, newer) -> newer);
    }

    private static UUID laneId(CombinePayload combine, String sessionId) {
        return UUID.nameUUIDFromBytes(("combine-join|" + combine.combineId() + "|" + sessionId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether a combine waits for an agent to take its lane.
     *
     * @param store   current projection
     * @param combine the combine
     * @return {@code true} while its work group is active and holds no lane
     */
    public static boolean waiting(PredictionEventStore store, CombinePayload combine) {
        boolean active = store.workGroupProjection().group(combine.workGroupId()).filter(group -> group.status() == WorkGroup.Status.ACTIVE).isPresent();
        return active && store.collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.workGroupId().equals(combine.workGroupId()));
    }

    /**
     * Why a participant may not take a combine, or empty when it may: the combine must be this computer's, written for
     * lanes it introduced (it closes the combine lane when the re-merge lands, and writes only for its own agents), and
     * the participant must have written none of the combined snapshots.
     *
     * @param store       current projection
     * @param combine     the combine
     * @param participant the participant handle
     * @param node        this computer
     * @return the refusal code, or empty
     */
    public static Optional<String> refusal(PredictionEventStore store, CombinePayload combine, String participant, String node) {
        List<TaskSnapshotRecord> combined = combine.snapshotIds().stream().map(id -> store.taskCompletionProjection().findSnapshotById(id))
                .flatMap(Optional::stream).toList();
        if (combined.size() != combine.snapshotIds().size()) {
            return Optional.of("COMBINE_SNAPSHOT_NOT_FOUND");
        }
        boolean thisComputers = combined.stream().allMatch(snapshot -> store.collaborationProjection().introducingNode(snapshot.provenance().participantIdentity())
                .map(node::equals).orElse(false));
        if (!thisComputers) {
            return Optional.of("COMBINE_NOT_THIS_COMPUTERS");
        }
        if (combined.stream().anyMatch(snapshot -> snapshot.provenance().participant().equals(participant))) {
            return Optional.of("COMBINE_BY_AUTHOR_NOT_ALLOWED");
        }
        return Optional.empty();
    }

    /**
     * Takes a combine lane: an ordinary producer lane in the combine's work group, on its task, at the commit the
     * advance could not be merged onto, claiming the combined lanes' paths. The session's worktree is moved to that
     * commit, on its own branch, and given both versions with Git's conflict markers left in; its binding records that
     * commit as its base. A repeated call returns the lane it opened.
     *
     * @param store       current projection
     * @param identity    this computer
     * @param location    the project
     * @param binding     the session's binding
     * @param participant the session's participant handle
     * @param combine     the combine
     * @return the claim result
     * @throws Exception when the combine may not be taken, the worktree cannot be prepared, or the lane is refused
     */
    static ClaimResult join(PredictionEventStore store, NodeIdentity identity, ProjectLocation location, ProviderSessionBindingService.Binding binding,
            String participant, CombinePayload combine) throws Exception {
        String sessionId = binding.sessionId();
        UUID targetId = laneId(combine, sessionId);
        Optional<WorkIntent> existing = store.collaborationProjection().intent(targetId);
        if (existing.isPresent()) {
            return new ClaimResult(true, existing.get(), List.of());
        }
        Optional<String> refused = refusal(store, combine, participant, identity.nodeId());
        if (refused.isPresent()) {
            throw new IOException(refused.get());
        }
        if (!waiting(store, combine)) {
            throw new IOException("COMBINE_TAKEN_OR_CLOSED");
        }
        Set<ResourceSelector> claims = new LinkedHashSet<>();
        for (String snapshotId : combine.snapshotIds()) {
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotById(snapshotId).orElseThrow();
            snapshot.provenance().claimSelectors().forEach(encoded -> ResourceSelector.parse(encoded).ifPresent(claims::add));
        }
        // The worktree first: a lane is never announced without both versions in place, and a repeated call after a
        // crash finds the worktree already prepared. Then its binding, so the worktree is judged against the combine's
        // base and not against this computer's main (s5-run8).
        new IntegrationWorkspaceService().materializeCombineRepresentation(location.root(), Path.of(binding.worktreePath()), binding.branch(), binding.baseCommit(),
                combine);
        new ProviderSessionBindingService().recordCombineBase(location, binding, combine.base());
        WorkIntent target = new WorkIntent(targetId,
                location.projectId(),
                participant,
                binding.provider(),
                combine.taskId(),
                "Combine the work of " + String.join(", ", combine.snapshotIds()) + " with the other copy of main",
                "One change that holds both versions, with no conflict markers left; its authors ("
                        + String.join(", ", authors(store, combine)) + ") are messaged if both cannot be kept",
                combine.base(),
                List.copyOf(claims),
                1,
                combine.workGroupId(),
                WorkIntent.defaultAuthorityLineage(targetId),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER,
                List.of(),
                List.of());
        return new WorkIntentService(store, identity).announce(target);
    }

    /**
     * The base of the combine lane a session holds open (ADR-0098 step 5, designer item 26): its worktree is judged
     * against this commit, the other copy of {@code main}, instead of this computer's control head.
     *
     * @param store     current projection
     * @param sessionId the session
     * @return the combine's base, or empty when the session holds no open combine lane
     */
    public static Optional<String> heldBase(PredictionEventStore store, String sessionId) {
        return store.taskCompletionProjection().combines().stream()
                .filter(combine -> store.collaborationProjection().intent(laneId(combine, sessionId)).filter(lane -> lane.status() == WorkIntent.Status.ANNOUNCED).isPresent())
                .map(CombinePayload::base).reduce((older, newer) -> newer);
    }

    /**
     * The combines an agent may take: waiting for their lane, this computer's, and written by none of the agent's
     * sessions.
     *
     * @param store        current projection
     * @param participants the agent's sessions' participant handles
     * @param node         this computer
     * @return the combines, oldest first
     */
    public static List<CombinePayload> offeredTo(PredictionEventStore store, java.util.Collection<String> participants, String node) {
        if (participants.isEmpty()) {
            return List.of();
        }
        return store.taskCompletionProjection().combines().stream().filter(combine -> waiting(store, combine))
                .filter(combine -> participants.stream().allMatch(participant -> refusal(store, combine, participant, node).isEmpty())).toList();
    }

    /**
     * Describes a combine as an agent's next action offers it: what to combine, whose work it is, and the
     * {@code ensure_session} arguments that take its lane.
     *
     * @param store   current projection
     * @param combine the combine
     * @return the description
     */
    public static java.util.Map<String, Object> describe(PredictionEventStore store, CombinePayload combine) {
        TaskSnapshotRecord first = store.taskCompletionProjection().findSnapshotById(combine.snapshotIds().getFirst()).orElseThrow();
        List<String> files = combine.snapshotIds().stream().map(id -> store.taskCompletionProjection().findSnapshotById(id)).flatMap(Optional::stream)
                .flatMap(snapshot -> snapshot.changedPaths().stream()).distinct().toList();
        java.util.Map<String, Object> described = new java.util.LinkedHashMap<>();
        described.put("combineId", combine.combineId());
        described.put("snapshotIds", combine.snapshotIds());
        described.put("authors", authors(store, combine));
        described.put("files", files);
        described.put("ensureSession", java.util.Map.of("task", java.util.Map.of("repairIntentId", first.provenance().laneId().toString(),
                "repairSnapshotId", first.snapshotId())));
        return described;
    }

    /**
     * The authors of the combined snapshots, whom an agent that cannot combine them messages (ADR-0093).
     *
     * @param store   current projection
     * @param combine the combine
     * @return their participant handles
     */
    public static List<String> authors(PredictionEventStore store, CombinePayload combine) {
        return combine.snapshotIds().stream().map(id -> store.taskCompletionProjection().findSnapshotById(id)).flatMap(Optional::stream)
                .map(snapshot -> snapshot.provenance().participant()).distinct().toList();
    }
}
