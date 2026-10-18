package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CompletionPreparedPayload;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.ReviewValidationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Lane;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * A snapshot whose reviewers disagree is disputed and counts as rejected; the reviewer whose verdict the dispute
 * turns on may record a new one on its own grant, and a snapshot every reviewer now accepts is integrated although
 * the rejection moved its lane on (ADR-0098 step 7, designer items 6 to 8). One reviewer is a session of this
 * computer and goes through {@link ReviewValidationService}; the other is another computer, whose entries arrive
 * through its log copy.
 *
 */
final class DisputedReviewIntegrationTest {

    private static final String PROVIDER = "codex";
    private static final String SNAPSHOT = "snap_1";

    private String previousHome;

    @BeforeEach
    void isolateHome(@TempDir Path home) {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    /** The author's project with one snapshot, a local reviewer of this computer, and a reviewer on another one. */
    private static final class Dispute {

        final Project project;
        final Lane lane;
        final String localReviewer;
        final String localConnection = "local-reviewer";
        final LaneGrant localGrant;
        final LaneGrant remoteGrant;
        final NodeIdentity remote = NodeIdentity.generate();
        final PredictionEventStore remoteStore;

        Dispute(Path root, Path remoteRoot) throws Exception {
            java.nio.file.Files.createDirectories(root);
            project = new Project(root);
            ProviderTestSupport.install(project.location, PROVIDER);
            new ProviderManualService().install(PROVIDER);
            new AgentSessionService().ensureSession(new SessionResolutionRequest(root, PROVIDER, localConnection, null, false));
            ProviderSessionBindingService bindings = new ProviderSessionBindingService();
            var binding = bindings.find(project.location, PROVIDER, localConnection).orElseThrow();
            bindings.verifyWorkspaceTrust(project.location, PROVIDER, binding.sessionId(), Path.of(binding.worktreePath()));
            localReviewer = WorkspaceCollaborationService.participantHandle(binding.sessionId());

            new WorkGroupService(project.store(), project.identity).create(new WorkGroup(project.group, project.location.projectId(),
                    "goal", "acceptance", 1, WorkGroup.Status.ACTIVE));
            lane = project.announce("agt_author", "src/f.txt");
            publishForReview(project, lane);
            localGrant = new LaneGrant(UUID.randomUUID(), project.group, lane.id(), localReviewer, 1, true, SNAPSHOT);
            remoteGrant = new LaneGrant(UUID.randomUUID(), project.group, lane.id(), "agt_remote", 1, true, SNAPSHOT);
            WorkGroupService groups = new WorkGroupService(project.store(), project.identity);
            groups.issue(localGrant);
            groups.issue(remoteGrant);
            // The other computer copies the author's log now, before any verdict exists, and reviews on its own.
            remoteStore = new PredictionEventStore(remoteRoot, project.location.projectId());
            remoteStore.placeCopies(project.identity.nodeId(), project.store().entriesAfter(PredictionEventStore.OWN_LOG, 0, 10_000, Integer.MAX_VALUE));
            new WorkGroupService(remoteStore, remote).consume(remoteGrant.grantId(), "agt_remote", lane.id(), 1);
            new WorkGroupService(project.store(), project.identity).consume(localGrant.grantId(), localReviewer, lane.id(), 1);
        }

        AgentResponse localVerdict(String result, String reason) {
            return new ReviewValidationService().validate(new ReviewValidationService.ValidateRequest(project.root, PROVIDER, localConnection,
                    localGrant.grantId(), SNAPSHOT, lane.id(), 1, result, reason));
        }

        /** The other computer records its verdict and this computer takes its log. */
        void remoteVerdict(String result, String reason) throws Exception {
            ReviewValidationPayload payload = new ReviewValidationPayload(remoteGrant.grantId(), project.group, lane.id(), "agt_remote", 1,
                    lane.task(), SNAPSHOT, result, reason, "agt_author");
            remoteStore.append(UUID.randomUUID(), PredictionEventType.REVIEW_VALIDATION_RECORDED, remote.nodeId(), payload.encode(), remote);
            project.store().placeCopies(remote.nodeId(), remoteStore.entriesAfter(PredictionEventStore.OWN_LOG, 0, 10_000, Integer.MAX_VALUE));
        }

        TaskCompletionState state() throws Exception {
            return project.state(SNAPSHOT);
        }

        long laneVersion() throws Exception {
            return project.store().collaborationProjection().intent(lane.id()).orElseThrow().version();
        }

        List<PredictionEvent> localVerdictEntries() throws Exception {
            return project.store().events().stream().filter(event -> event.type() == PredictionEventType.REVIEW_VALIDATION_RECORDED)
                    .filter(event -> decode(event).grantId().equals(localGrant.grantId())).toList();
        }

        private static ReviewValidationPayload decode(PredictionEvent event) {
            try {
                return ReviewValidationPayload.decode(event.payload());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    /** As {@code Project.publish} for one file, but the snapshot needs review, so it waits at REVIEW_PENDING. */
    private static void publishForReview(Project project, Lane lane) throws Exception {
        String base = project.head();
        java.nio.file.Files.createDirectories(project.root.resolve("src"));
        java.nio.file.Files.writeString(project.root.resolve("src/f.txt"), "one\n");
        project.git("add", "src/f.txt");
        project.git("commit", "-m", SNAPSHOT);
        String commit = project.head();
        project.git("reset", "--hard", base);
        String preparedRef = "refs/synesis/prepared/cmp_" + SNAPSHOT;
        String snapshotRef = "refs/synesis/snapshots/" + SNAPSHOT;
        project.git("update-ref", preparedRef, commit);
        project.git("update-ref", snapshotRef, commit);
        String tree = project.git("rev-parse", commit + "^{tree}");
        SnapshotProvenance provenance = new SnapshotProvenance(project.group, lane.id(), WorkIntent.defaultAuthorityLineage(lane.id()), lane.participant(),
                "binding", 1, List.of(), List.of(), List.of("PATH_EXACT:src/f.txt"), snapshotRef, tree, "manifest");
        TaskSnapshotPayload snapshot = new TaskSnapshotPayload(lane.task(), SNAPSHOT, project.identity.nodeId(), "sup", "worker", "session", base, commit,
                List.of("src/f.txt"), List.of(), "snapshot", provenance, true);
        CompletionPreparedPayload prepared = new CompletionPreparedPayload(lane.task(), "cmp_" + SNAPSHOT, lane.id(), 1, base, preparedRef, tree, snapshot.changedPaths());
        PredictionEventStore store = project.store();
        store.append(UUID.randomUUID(), PredictionEventType.COMPLETION_PREPARED, project.identity.nodeId(), prepared.encode(), project.identity);
        store.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, project.identity.nodeId(), snapshot.encode(), project.identity);
    }

    private static String blockedError(AgentResponse response) {
        assertEquals(AgentStatus.BLOCKED, response.status(), response.toString());
        return String.valueOf(((Map<?, ?>) response.result()).get("error"));
    }

    @Test
    void theRejectingReviewerAcceptsAfterTheDisputeAndTheSnapshotIntegratesDespiteTheMovedLane(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("REJECTED", "needs a test").status());
        assertEquals(TaskCompletionState.REVIEW_REJECTED, dispute.state());
        assertEquals(2, dispute.laneVersion(), "the rejection moved the lane on");
        dispute.remoteVerdict("ACCEPTED", "");

        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());
        assertEquals(List.of(new SnapshotId(SNAPSHOT)), dispute.project.store().taskCompletionProjection().disputedSnapshots());
        String head = dispute.project.head();
        dispute.project.pump();
        assertEquals(head, dispute.project.head(), "a disputed snapshot counts as rejected: nothing is integrated");
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());

        AgentResponse replaced = dispute.localVerdict("ACCEPTED", "");

        assertEquals(AgentStatus.COMPLETED, replaced.status(), replaced.toString());
        assertEquals("ACCEPTED", ((Map<?, ?>) replaced.result()).get("result"));
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, dispute.state());
        assertTrue(dispute.project.store().taskCompletionProjection().everDisputed(new SnapshotId(SNAPSHOT)));
        List<PredictionEvent> entries = dispute.localVerdictEntries();
        assertEquals(2, entries.size(), "the first verdict stays on record beside the new one");
        assertNotEquals(entries.get(0).predictionId(), entries.get(1).predictionId());
        assertEquals("REJECTED", Dispute.decode(entries.get(0)).result());
        assertEquals("ACCEPTED", Dispute.decode(entries.get(1)).result());
        assertEquals(2, dispute.laneVersion());

        AgentResponse integrated = dispute.project.pump();

        assertEquals(AgentStatus.COMPLETED, integrated.status(), integrated.toString());
        assertEquals(TaskCompletionState.INTEGRATED, dispute.state());
        assertTrue(dispute.project.headHas("src/f.txt"), "the snapshot was merged, not refused as stale");
    }

    @Test
    void theAcceptingReviewerRejectsAfterTheDisputeAndTheSnapshotIsRejected(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("ACCEPTED", "").status());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, dispute.state());
        dispute.remoteVerdict("REJECTED", "wrong approach");
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());
        long versionAfterDispute = dispute.laneVersion();
        assertEquals(2, versionAfterDispute, "the arriving rejection reopened the lane");

        AgentResponse replaced = dispute.localVerdict("REJECTED", "I agree with the other reviewer");

        assertEquals(AgentStatus.COMPLETED, replaced.status(), replaced.toString());
        assertEquals(TaskCompletionState.REVIEW_REJECTED, dispute.state());
        assertEquals(versionAfterDispute, dispute.laneVersion(), "the lane is reopened for the correction once, not again");
        assertEquals(WorkIntent.Status.ANNOUNCED, dispute.project.store().collaborationProjection().intent(dispute.lane.id()).orElseThrow().status());
        String head = dispute.project.head();
        dispute.project.pump();
        assertEquals(head, dispute.project.head());
        assertFalse(dispute.project.headHas("src/f.txt"));
    }

    @Test
    void aSoleRejectionCannotBeTakenBackByItsReviewer(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("REJECTED", "needs a test").status());
        long before = dispute.project.store().headSequence();

        // The rejection moved the lane on, so the grant is stale before the conflict is reached; either way it is refused.
        assertEquals("REVIEW_GRANT_STALE", blockedError(dispute.localVerdict("ACCEPTED", "")));

        assertEquals(before, dispute.project.store().headSequence(), "nothing was appended");
        assertEquals(TaskCompletionState.REVIEW_REJECTED, dispute.state());
    }

    @Test
    void anAcceptanceCannotBeTakenBackWhenNobodyDisagrees(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("ACCEPTED", "").status());
        long before = dispute.project.store().headSequence();

        assertEquals("REVIEW_DECISION_CONFLICT", blockedError(dispute.localVerdict("REJECTED", "second thoughts")));

        assertEquals(before, dispute.project.store().headSequence());
        assertEquals(TaskCompletionState.REVIEW_ACCEPTED, dispute.state());
    }

    @Test
    void repeatingTheSameVerdictInADisputeAppendsNothing(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("REJECTED", "needs a test").status());
        dispute.remoteVerdict("ACCEPTED", "");
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());
        long before = dispute.project.store().headSequence();

        AgentResponse again = dispute.localVerdict("REJECTED", "needs a test");

        assertEquals(AgentStatus.COMPLETED, again.status(), again.toString());
        assertEquals(before, dispute.project.store().headSequence());
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());
    }

    @Test
    void anotherSessionCannotUseTheReviewersGrantToChangeItsVerdict(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("REJECTED", "needs a test").status());
        dispute.remoteVerdict("ACCEPTED", "");
        new AgentSessionService().ensureSession(new SessionResolutionRequest(dispute.project.root, PROVIDER, "other-session", null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var other = bindings.find(dispute.project.location, PROVIDER, "other-session").orElseThrow();
        bindings.verifyWorkspaceTrust(dispute.project.location, PROVIDER, other.sessionId(), Path.of(other.worktreePath()));
        long before = dispute.project.store().headSequence();

        AgentResponse stolen = new ReviewValidationService().validate(new ReviewValidationService.ValidateRequest(dispute.project.root, PROVIDER,
                "other-session", dispute.localGrant.grantId(), SNAPSHOT, dispute.lane.id(), 1, "ACCEPTED", ""));

        assertEquals("REVIEW_GRANT_TARGET_MISMATCH", blockedError(stolen));
        assertEquals(before, dispute.project.store().headSequence());
        assertEquals(TaskCompletionState.REVIEW_DISPUTED, dispute.state());
    }

    @Test
    void aSnapshotThatNeverHadADisputeIsStillStaleOnceItsLaneMovedOn(@TempDir Path temp) throws Exception {
        Dispute dispute = new Dispute(temp.resolve("author"), temp.resolve("remote"));
        assertEquals(AgentStatus.COMPLETED, dispute.localVerdict("REJECTED", "needs a test").status());
        assertEquals(2, dispute.laneVersion());
        assertFalse(dispute.project.store().taskCompletionProjection().rejectionWithdrawn(
                dispute.project.store().taskCompletionProjection().findSnapshotById(SNAPSHOT).orElseThrow(), 2));
        String head = dispute.project.head();

        dispute.project.pump();

        assertEquals(head, dispute.project.head(), "a rejected snapshot is not integrated");
        assertNotEquals(TaskCompletionState.INTEGRATED, dispute.state());
    }
}
