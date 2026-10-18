package org.synesis.workspace.application.control;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * How this computer's copy of {@code main} meets the other computers' copies (ADR-0098 step 5, "Getting onto
 * {@code main}"). Every computer merges its own lanes onto its own copy and records each advance in its own log
 * ({@code CONTROL_BRANCH_ADVANCED}: the head it moved from and the head it moved to). Another computer's newer head
 * is fetched over Link and taken by fast-forward, and only after an audit: every commit it adds must be the result of
 * an advance an entry names, whose snapshots were reviewed where review is required. Nothing is ever pushed into a
 * computer, and {@code main} only ever moves forward.
 */
public final class MainMeeting {

    /**
     * Longest chain of advances an audit follows; a longer one is a defect, never a real history of a handful of
     * computers between two meetings.
     */
    private static final int MAX_STEPS = 100_000;

    /**
     * One advance a log names.
     *
     * @param writer      the computer whose pass made it
     * @param from        the head the pass started from
     * @param to          the head it moved to
     * @param snapshotIds the snapshots it integrated
     */
    record Advance(String writer, String from, String to, List<String> snapshotIds) {
    }

    /**
     * What comparing this copy with another computer's head found.
     */
    public enum Relation {
        /** The other head is in this copy already. */
        HELD,
        /** This copy is an ancestor of the other head: it may fast-forward, once the audit passes. */
        BEHIND,
        /** Each holds work the other lacks: one of the two re-merges (designer item 5). */
        DIVERGED
    }

    private MainMeeting() {
    }

    /**
     * Every advance the logs this computer holds name, by the head each moved to, in the order they took effect here.
     *
     * @param events the applied entries
     * @return the advances by head
     */
    static Map<String, Advance> advances(List<PredictionEvent> events) {
        Map<String, Advance> byHead = new HashMap<>();
        for (PredictionEvent event : events) {
            if (event.type() != PredictionEventType.CONTROL_BRANCH_ADVANCED) {
                continue;
            }
            try {
                IntegrationAttemptPayload advanced = IntegrationAttemptPayload.decode(event.payload());
                byHead.putIfAbsent(advanced.integrationCommitSha(), new Advance(event.actorNodeId(),
                        advanced.expectedControlHead(), advanced.integrationCommitSha(), advanced.taskSnapshotIds()));
            } catch (IOException | RuntimeException undecodable) {
                // Names nothing; the projections judged the entry.
            }
        }
        return byHead;
    }

    /**
     * The heads other computers' passes advanced to last, as this computer's logs name them.
     *
     * @param events the applied entries
     * @return each writer's last advanced head
     */
    static Map<String, String> lastHeads(List<PredictionEvent> events) {
        Map<String, String> last = new HashMap<>();
        for (PredictionEvent event : events) {
            if (event.type() == PredictionEventType.CONTROL_BRANCH_ADVANCED) {
                try {
                    last.put(event.actorNodeId(), IntegrationAttemptPayload.decode(event.payload()).integrationCommitSha());
                } catch (IOException | RuntimeException undecodable) {
                    // Names nothing.
                }
            }
        }
        return last;
    }

    /**
     * Compares this copy's head with another head held here.
     *
     * @param repository the control checkout
     * @param own        this copy's head
     * @param other      the other head
     * @return how they relate
     * @throws IOException when Git cannot compare them
     */
    static Relation relate(Path repository, String own, String other) throws IOException {
        if (own.equals(other) || GitProcessRunner.isAncestor(repository, other, own)) {
            return Relation.HELD;
        }
        return GitProcessRunner.isAncestor(repository, own, other) ? Relation.BEHIND : Relation.DIVERGED;
    }

    /**
     * Checks the commits another head adds to this copy before they are taken (ADR-0098 step 5, item 3). The head's
     * history is followed back advance by advance, each named by an entry, until a commit this copy holds; every
     * advance on the way must name snapshots this computer has seen, each made on the advancing computer's own lane,
     * and each accepted by a reviewer who is not its author where review is required. The cherry-picked commits
     * inside one advance are that advance's; the validation command passed before its entry was written.
     *
     * @param repository the control checkout
     * @param store      this computer's logs
     * @param own        this copy's head
     * @param other      the head to take
     * @return why the head may not be taken yet; empty when it may
     * @throws IOException when Git cannot be read
     */
    public static List<String> audit(Path repository, PredictionEventStore store, String own, String other) throws IOException {
        Map<String, Advance> byHead = advances(store.events());
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String commit = other;
        for (int steps = 0; !commit.equals(own) && !GitProcessRunner.isAncestor(repository, commit, own); steps++) {
            Advance advance = byHead.get(commit);
            if (advance == null) {
                // The writer's entry may not have arrived yet: the head waits for the next pull.
                problems.add("UNNAMED_COMMIT:" + commit);
                return problems;
            }
            if (!seen.add(commit) || steps > MAX_STEPS) {
                problems.add("ADVANCE_CHAIN_INVALID:" + commit);
                return problems;
            }
            for (String snapshotId : advance.snapshotIds()) {
                Optional<TaskSnapshotRecord> snapshot = store.taskCompletionProjection().findSnapshotById(snapshotId);
                if (snapshot.isEmpty()) {
                    problems.add("SNAPSHOT_NOT_SEEN:" + snapshotId);
                    continue;
                }
                String producer = snapshot.get().provenance().participant();
                boolean ownLane = store.collaborationProjection().introducingNode(snapshot.get().provenance().participantIdentity())
                        .map(advance.writer()::equals).orElse(true);
                if (!ownLane) {
                    problems.add("NOT_THE_LANES_COMPUTER:" + snapshotId);
                }
                if (snapshot.get().reviewRequired() && store.taskCompletionProjection()
                        .verdicts(snapshot.get().snapshotIdentity()).entrySet().stream()
                        .noneMatch(verdict -> "ACCEPTED".equals(verdict.getValue()) && !verdict.getKey().equals(producer))) {
                    problems.add("REVIEW_MISSING:" + snapshotId);
                }
            }
            if (advance.from() == null || advance.from().isBlank()) {
                problems.add("ADVANCE_CHAIN_INVALID:" + commit);
                return problems;
            }
            commit = advance.from();
        }
        return problems;
    }
}
