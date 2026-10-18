package org.synesis.workspace.application.control;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.RemergeSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;

/**
 * The commits an entry of this computer's copy of the log names, which are the only commit IDs Code over Link serves
 * or fetches (ADR-0098 step 3): a lane's snapshot, a capability's published implementation, an integration commit
 * and the head a {@code CONTROL_BRANCH_ADVANCED} entry names. For each it keeps the nodes whose entries name it,
 * which are the computers that hold it first, and the refs those entries give it: a snapshot's own ref, and the
 * prepared ref of the completion of the same lane and claim, which the merge pass checks against the snapshot.
 *
 * <p>Entries are signed and checked when the log is read, so the node that signed one is the computer that made the
 * commit it names. A payload that cannot be decoded names nothing here; the projections refuse it on their own.</p>
 */
final class LogNamedCommits {

    private final Map<String, Naming> byCommit;

    private LogNamedCommits(Map<String, Naming> byCommit) {
        this.byCommit = byCommit;
    }

    /** Who named one commit, and under which refs. */
    private record Naming(Set<String> writers, Set<String> refs) {
    }

    /**
     * Reads what a log names.
     *
     * @param events the verified entries, in order
     * @return what they name
     */
    static LogNamedCommits of(List<PredictionEvent> events) {
        Map<String, Naming> byCommit = new HashMap<>();
        Map<List<Object>, String> preparedRefs = new HashMap<>();
        for (PredictionEvent event : events) {
            try {
                switch (event.type()) {
                    case COMPLETION_PREPARED -> {
                        CompletionPreparedPayload prepared = CompletionPreparedPayload.decode(event.payload());
                        preparedRefs.put(List.of(prepared.taskId(), prepared.laneId(), prepared.claimEpoch()), prepared.preparedRef());
                    }
                    case TASK_SNAPSHOT_CREATED -> {
                        TaskSnapshotPayload snapshot = TaskSnapshotPayload.decode(event.payload());
                        Set<String> refs = name(byCommit, snapshot.commitSha(), event.actorNodeId()).refs();
                        refs.add(snapshot.provenance().snapshotRef());
                        String prepared = preparedRefs.get(List.of(snapshot.taskId(), snapshot.provenance().laneId(), snapshot.provenance().claimEpoch()));
                        if (prepared != null) {
                            refs.add(prepared);
                        }
                    }
                    case REMERGE_SNAPSHOT_CREATED -> {
                        // A lane's re-merge commit, served under the re-merge snapshot's own ref (ADR-0098 step 5).
                        TaskSnapshotPayload remerge = RemergeSnapshotPayload.decode(event.payload()).snapshot();
                        name(byCommit, remerge.commitSha(), event.actorNodeId()).refs().add(remerge.provenance().snapshotRef());
                    }
                    case CAPABILITY_IMPLEMENTATION_PUBLISHED ->
                            name(byCommit, ImplementationEventPayload.decode(event.payload()).commitSha(), event.actorNodeId());
                    case INTEGRATION_COMMIT_CREATED ->
                            name(byCommit, IntegrationAttemptPayload.decode(event.payload()).integrationCommitSha(), event.actorNodeId());
                    case CONTROL_BRANCH_ADVANCED -> {
                        name(byCommit, IntegrationAttemptPayload.decode(event.payload()).integrationCommitSha(), event.actorNodeId());
                    }
                    default -> {
                    }
                }
            } catch (IOException | RuntimeException undecodable) {
                // Names nothing; see the class comment.
            }
        }
        return new LogNamedCommits(byCommit);
    }

    private static Naming name(Map<String, Naming> byCommit, String commit, String writer) {
        Naming naming = byCommit.computeIfAbsent(commit, ignored -> new Naming(new LinkedHashSet<>(), new LinkedHashSet<>()));
        naming.writers().add(writer);
        return naming;
    }

    /**
     * Tells whether an entry names a commit.
     *
     * @param commit a commit ID
     * @return true when one does
     */
    boolean names(String commit) {
        return byCommit.containsKey(commit);
    }

    /**
     * The nodes whose entries name a commit, earliest first.
     *
     * @param commit a commit ID
     * @return the nodes; empty when none names it
     */
    List<String> writers(String commit) {
        Naming naming = byCommit.get(commit);
        return naming == null ? List.of() : new ArrayList<>(naming.writers());
    }

    /**
     * The snapshot and prepared refs the entries naming a commit give it, the snapshot's own first.
     *
     * @param commit a commit ID
     * @return the refs; empty when none does
     */
    List<String> refs(String commit) {
        Naming naming = byCommit.get(commit);
        return naming == null ? List.of() : new ArrayList<>(naming.refs());
    }
}
