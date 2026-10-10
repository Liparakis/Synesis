package org.synesis.workspace.application.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;

/**
 * Decides which task snapshots may enter an integration attempt and in what order. Every function
 * reads projections only; the attempt lifecycle, Git access and event appends stay with
 * {@link IntegrationOrchestrationService}.
 */
final class IntegrationCandidatePolicy {

    private IntegrationCandidatePolicy() {
    }

    /** Holds the readiness verdict and structural failures for one candidate. */
    record CandidateResolution(boolean ready, List<String> structuralFailures) {

        CandidateResolution {
            structuralFailures = List.copyOf(structuralFailures);
        }
    }

    /**
     * Returns the eligible snapshots that hold the exact durable authority required for
     * integration, oldest first.
     */
    static List<TaskSnapshotRecord> reviewAcceptedInOrder(PredictionEventStore store) {
        return store.taskCompletionProjection().eligibleSnapshots().stream().filter(snapshot -> reviewAccepted(snapshot, store))
                .sorted(Comparator.comparingLong(TaskSnapshotRecord::createdAtMillis).thenComparing(TaskSnapshotRecord::snapshotId)).toList();
    }

    /** Returns whether one candidate has the exact durable authority required for integration. */
    private static boolean reviewAccepted(TaskSnapshotRecord snapshot, PredictionEventStore store) {
        if (!snapshot.reviewRequired()) {
            return true;
        }
        if (store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE) != TaskCompletionState.REVIEW_ACCEPTED) {
            return false;
        }
        return store.workGroupProjection().reviewValidationForSnapshot(snapshot.snapshotId()).filter(validation -> "ACCEPTED".equals(validation.result()))
                .filter(validation -> validation.taskId().equals(snapshot.taskId())).filter(validation -> validation.workGroupId().equals(snapshot.provenance().workGroupId()))
                .filter(validation -> validation.targetIntentId().equals(snapshot.provenance().laneId())).filter(validation -> validation.claimEpoch() == snapshot.provenance().claimEpoch())
                .filter(validation -> validation.sourceParticipant().equals(snapshot.provenance().participant())).isPresent();
    }

    /**
     * Resolves a candidate's capability dependencies into structural failures or a pending/ready verdict.
     *
     * @param carriesContent whether an owner snapshot holds the content a requester validated; the
     *                       only question the policy cannot answer from projections
     */
    static CandidateResolution resolveCandidate(TaskSnapshotRecord candidate,
            List<TaskSnapshotRecord> allSnapshots,
            CapabilityRequestProjection capProj,
            TaskCompletionProjection completion,
            BiPredicate<ImplementationRevisionRecord, TaskSnapshotRecord> carriesContent) {
        List<String> structuralFailures = new ArrayList<>();
        boolean pendingDependency = false;
        for (String dependencyHandle : candidate.capabilityDependencies()) {
            CapabilityRequestRecord capability = capProj.findByHandle(dependencyHandle).orElse(null);
            if (capability == null) {
                structuralFailures.add("MISSING_CAPABILITY_DEPENDENCY:" + dependencyHandle);
                continue;
            }
            if (!capability.state().bindsLanes()) {
                structuralFailures.add("STALE_CAPABILITY_DEPENDENCY:" + dependencyHandle);
                continue;
            }
            if (capability.state() != CapabilityLifecycleState.VALIDATED) {
                pendingDependency = true;
                continue;
            }
            var implementation = capProj.findLatestImplementation(dependencyHandle).orElse(null);
            if (implementation == null) {
                pendingDependency = true;
                continue;
            }
            if (!capability.authorityLineageId().equals(implementation.authorityLineageId())) {
                structuralFailures.add("CAPABILITY_LINEAGE_MISMATCH:" + dependencyHandle);
                continue;
            }
            TaskSnapshotRecord ownerSnapshot = ownerSnapshotCarrying(implementation, allSnapshots, carriesContent);
            if (ownerSnapshot == null) {
                // Validation alone does not put the implementation in the
                // integration history. Keep the consumer pending until the
                // owner lane publishes a task snapshot that carries it.
                pendingDependency = true;
                continue;
            }
            TaskCompletionState ownerState = completion.snapshotState(ownerSnapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
            if (ownerState == TaskCompletionState.INTEGRATED) {
                continue;
            }
            pendingDependency = true;
        }
        return new CandidateResolution(structuralFailures.isEmpty() && !pendingDependency, structuralFailures);
    }

    /**
     * Finds the owner-lane snapshot that puts a validated implementation into the integration
     * history.
     *
     * <p>The snapshot of the validated commit itself is preferred. A lane that changed hands is
     * rebuilt from recovery evidence, so its later snapshot is a different commit even when nothing
     * in it changed; requiring the same commit would leave every consumer of a recovered owner
     * waiting forever. Such a snapshot qualifies only when {@code carriesContent} accepts it.</p>
     */
    private static TaskSnapshotRecord ownerSnapshotCarrying(ImplementationRevisionRecord implementation,
            List<TaskSnapshotRecord> allSnapshots,
            BiPredicate<ImplementationRevisionRecord, TaskSnapshotRecord> carriesContent) {
        List<TaskSnapshotRecord> ownerSnapshots = allSnapshots.stream()
                .filter(snapshot -> snapshot.provenance().authorityLineageId().equals(implementation.authorityLineageId()))
                .sorted(Comparator.comparingLong(TaskSnapshotRecord::createdAtMillis).thenComparing(TaskSnapshotRecord::snapshotId))
                .toList();
        for (TaskSnapshotRecord snapshot : ownerSnapshots) {
            if (snapshot.commitSha().equals(implementation.commitSha())) {
                return snapshot;
            }
        }
        for (TaskSnapshotRecord snapshot : ownerSnapshots) {
            if (carriesContent.test(implementation, snapshot)) {
                return snapshot;
            }
        }
        return null;
    }

    /** Returns the ready candidates that share the oldest ready candidate's work group. */
    static List<TaskSnapshotRecord> oldestWorkGroup(List<TaskSnapshotRecord> readyCandidates) {
        UUID selectedWorkGroup = readyCandidates.getFirst().provenance().workGroupId();
        return readyCandidates.stream().filter(candidate -> candidate.provenance().workGroupId().equals(selectedWorkGroup)).toList();
    }

    /** Orders snapshots so each follows the owner snapshots it depends on; fails on a dependency cycle. */
    static List<TaskSnapshotRecord> sortTopologically(List<TaskSnapshotRecord> snapshots, CapabilityRequestProjection capProj) {
        Map<UUID, List<TaskSnapshotRecord>> byLineage = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            byLineage.computeIfAbsent(s.provenance().authorityLineageId(), ignored -> new ArrayList<>()).add(s);
        }

        // Build adjacency: dependent -> dependencies
        Map<TaskSnapshotRecord, List<TaskSnapshotRecord>> dependencies = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            List<TaskSnapshotRecord> deps = new ArrayList<>();
            for (String capHandle : s.capabilityDependencies()) {
                var capOpt = capProj.findByHandle(capHandle);
                if (capOpt.isPresent()) {
                    CapabilityRequestRecord cap = capOpt.get();
                    var ownerCandidates = byLineage.getOrDefault(cap.authorityLineageId(), List.of());
                    var implementation = capProj.findLatestImplementation(cap.handle().value());
                    for (TaskSnapshotRecord ownerSnap : ownerCandidates) {
                        boolean exactPublishedImplementation = implementation.isEmpty() || implementation.get().commitSha().equals(ownerSnap.commitSha());
                        if (exactPublishedImplementation && !ownerSnap.equals(s) && !deps.contains(ownerSnap)) {
                            deps.add(ownerSnap);
                            break;
                        }
                    }
                }
            }
            dependencies.put(s, deps);
        }

        // Depth-first topological sort
        List<TaskSnapshotRecord> sorted = new ArrayList<>();
        Set<TaskSnapshotRecord> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TaskSnapshotRecord> inStack = Collections.newSetFromMap(new IdentityHashMap<>());

        for (TaskSnapshotRecord node : snapshots) {
            if (!visited.contains(node)) {
                visitDfs(node, dependencies, visited, inStack, sorted);
            }
        }

        return List.copyOf(sorted);
    }

    private static void visitDfs(TaskSnapshotRecord node,
            Map<TaskSnapshotRecord, List<TaskSnapshotRecord>> dependencies,
            Set<TaskSnapshotRecord> visited,
            Set<TaskSnapshotRecord> inStack,
            List<TaskSnapshotRecord> sorted) {
        if (inStack.contains(node)) {
            throw new IllegalStateException("Dependency cycle detected involving snapshot " + node.snapshotId());
        }
        if (!visited.contains(node)) {
            inStack.add(node);
            List<TaskSnapshotRecord> deps = dependencies.getOrDefault(node, List.of());
            for (TaskSnapshotRecord dep : deps) {
                visitDfs(dep, dependencies, visited, inStack, sorted);
            }
            inStack.remove(node);
            visited.add(node);
            sorted.add(node);
        }
    }

    /** Returns the lane intent a snapshot was published under, or the later intent that replaced it. */
    static WorkIntent currentIntentForSnapshot(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        WorkIntent exact = store.collaborationProjection().intent(snapshot.provenance().laneId()).orElse(null);
        if (exact != null) {
            return exact;
        }
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.taskId().equals(snapshot.taskId())).filter(intent -> intent.workGroupId().equals(snapshot.provenance().workGroupId()))
                .filter(intent -> intent.authorityLineageId().equals(snapshot.provenance().authorityLineageId())).filter(intent -> intent.version() > snapshot.provenance().claimEpoch())
                .min(Comparator.comparingLong(WorkIntent::version)).orElse(null);
    }

    /**
     * Keeps integration blocked only for a pending request that still has an authorized participant
     * capable of responding. Requests owned entirely by terminal participants are historical
     * evidence; leaving them pending must not make an active work group permanently unintegrable.
     *
     * @param store   current project event store
     * @param request pending coordination request
     * @return {@code true} when the request still belongs to a live collaboration edge
     */
    static boolean liveCoordinationRequest(PredictionEventStore store, CoordinationRequest request) {
        return liveParticipant(store, request.requester()) && liveParticipant(store, request.target());
    }

    /** Treats unknown participants conservatively while excluding only explicit terminal states. */
    private static boolean liveParticipant(PredictionEventStore store, String participant) {
        return store.collaborationProjection().participantState(participant)
                .map(state -> state != Participant.State.REVOKED && state != Participant.State.COMPLETED && state != Participant.State.CANCELLED && state != Participant.State.DETACHED).orElse(true);
    }
}
