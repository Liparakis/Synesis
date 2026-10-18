package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.replication.LeaderReplication.MemberConnection.State;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.collaboration.AwayLanes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * Which lanes of other computers this computer may take over, and which reviews of unreachable reviewers it releases,
 * judged by this computer alone (ADR-0098 designer item 2, step 7 items 9 to 11 and 17).
 */
final class AwayLanesTest {

    private String previousHome;
    @TempDir
    Path temp;
    private AwayComputerFixture fixture;

    @BeforeEach
    void setUp(@TempDir Path home) throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        fixture = new AwayComputerFixture(temp.resolve("here"), temp.resolve("away"));
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    private List<WorkIntent> offered() throws Exception {
        return AwayLanes.offered(fixture.project.store(), fixture.project.location.coordinationDirectory(), fixture.project.identity.nodeId());
    }

    private void release() throws Exception {
        release(participant -> false);
    }

    private void release(Predicate<String> goneHere) throws Exception {
        AwayLanes.releaseUnreachableReviews(fixture.project.store(), fixture.project.location.coordinationDirectory(), fixture.project.identity, goneHere);
    }

    private long revocations() throws Exception {
        return fixture.project.store().events().stream().filter(event -> event.type() == PredictionEventType.LANE_REVOKED).count();
    }

    @Test
    void aLaneOfAnAwayComputerIsOfferedToAStalledComputer() throws Exception {
        fixture.awayIs(State.DISCONNECTED);

        assertTrue(AwayLanes.stalled(fixture.project.store(), fixture.project.identity.nodeId()));
        assertEquals(List.of(fixture.awayLane), offered());
    }

    @Test
    void aLaneOfAConnectedComputerIsNotOffered() throws Exception {
        fixture.awayIs(State.CONNECTED);

        assertEquals(List.of(), offered());
    }

    @Test
    void nobodyIsAwayWhenThereIsNoRecordOfMemberSessions() throws Exception {
        assertFalse(Files.exists(fixture.project.location.coordinationDirectory().resolve("replication.members")));

        assertEquals(List.of(), offered());
    }

    @Test
    void nothingIsOfferedWhileThisComputerHoldsAnOpenLaneInAnActiveGroup() throws Exception {
        fixture.awayIs(State.DISCONNECTED);
        new WorkIntentService(fixture.project.store(), fixture.project.identity).announce(new WorkIntent(UUID.randomUUID(),
                fixture.project.location.projectId(), "agt_here", "codex", UUID.randomUUID(), "mine", "acceptance", fixture.base,
                List.of(ResourceSelector.pathExact("src/mine.txt")), 1, fixture.project.group, WorkIntent.Status.ANNOUNCED));

        assertFalse(AwayLanes.stalled(fixture.project.store(), fixture.project.identity.nodeId()));
        assertEquals(List.of(), offered());
    }

    @Test
    void aComputerMissingFromAnExistingRecordCountsAsAway() throws Exception {
        Files.writeString(fixture.project.location.coordinationDirectory().resolve("replication.members"), "someone-else CONNECTED 2026-10-18T10:00:00Z\n");

        assertEquals(List.of(fixture.awayLane), offered());
    }

    /** The away computer announces a reviewer lane and issues and consumes a review grant for it; all reach here by copy. */
    private LaneGrant awayReviewerWithoutLane(String participant, String snapshot) throws Exception {
        return reviewerWithoutLane(fixture.awayStore, fixture.away, participant, snapshot);
    }

    /** One computer announces a reviewer lane, ends it, and issues and consumes a review grant for it. */
    private LaneGrant reviewerWithoutLane(PredictionEventStore store, NodeIdentity computer, String participant, String snapshot) throws Exception {
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), fixture.project.group, fixture.awayLane.intentId(), participant, 1, true, snapshot);
        UUID reviewerLane = UUID.randomUUID();
        WorkIntent introduced = new WorkIntent(reviewerLane, fixture.project.location.projectId(), participant, "codex", UUID.randomUUID(), "review", "acceptance",
                fixture.base, List.of(ResourceSelector.pathExact("src/review-" + participant + ".txt")), 1, fixture.project.group,
                WorkIntent.defaultAuthorityLineage(reviewerLane), WorkIntent.Status.ANNOUNCED);
        var services = new WorkIntentService(store, computer);
        services.announce(introduced);
        services.release(reviewerLane, participant);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, computer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), computer);
        store.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, computer.nodeId(), CollaborationCodec.encodeLaneGrant(grant), computer);
        fixture.sync();
        return grant;
    }

    /** This computer holds an open lane in the active group, so it is not stalled. */
    private void notStalled() throws Exception {
        new WorkIntentService(fixture.project.store(), fixture.project.identity).announce(new WorkIntent(UUID.randomUUID(),
                fixture.project.location.projectId(), "agt_here", "codex", UUID.randomUUID(), "mine", "acceptance", fixture.base,
                List.of(ResourceSelector.pathExact("src/mine.txt")), 1, fixture.project.group, WorkIntent.Status.ANNOUNCED));
        assertFalse(AwayLanes.stalled(fixture.project.store(), fixture.project.identity.nodeId()));
    }

    /**
     * Real run s5-run6: a reviewer here consumed its grant, its own lane was integrated, and it stopped without a
     * verdict. Its computer, which alone can judge its session, releases the review at once, stalled or not.
     */
    @Test
    void aReviewerOfThisComputerWhoseSessionIsGoneHasItsReviewReleasedAtOnce() throws Exception {
        fixture.snapshot("snap_rel", true);
        fixture.awayIs(State.CONNECTED);
        notStalled();
        LaneGrant pending = reviewerWithoutLane(fixture.project.store(), fixture.project.identity, "agt_gone_reviewer", "snap_rel");

        release("agt_gone_reviewer"::equals);

        assertTrue(fixture.project.store().workGroupProjection().grantRevoked(new LaneGrantId(pending.grantId())), "the gone reviewer's grant was revoked");
        assertEquals(1, revocations());
    }

    @Test
    void aReviewerOfThisComputerWhoseSessionIsLiveKeepsItsReview() throws Exception {
        fixture.snapshot("snap_rel", true);
        fixture.awayIs(State.DISCONNECTED);
        LaneGrant pending = reviewerWithoutLane(fixture.project.store(), fixture.project.identity, "agt_live_reviewer", "snap_rel");

        release();

        assertFalse(fixture.project.store().workGroupProjection().grantRevoked(new LaneGrantId(pending.grantId())));
        assertEquals(0, revocations(), "a stalled computer releases only reviews of away computers and of its own gone sessions");
    }

    @Test
    void aSessionIsGoneHereOnceNoLeaseNamesItAsLive() throws Exception {
        Path root = fixture.project.location.root();
        SessionLeaseService leases = new SessionLeaseService();
        String projectId = fixture.project.location.projectId().toString();
        leases.createOrRenewLease(root, projectId, "codex", "conn-live", fixture.project.identity.nodeId(), "session-live", new SessionLeasePolicy());
        leases.createOrRenewLease(root, projectId, "codex", "conn-closed", fixture.project.identity.nodeId(), "session-closed", new SessionLeasePolicy());
        assertTrue(leases.markClosedCleanly(root, "conn-closed"));

        Predicate<String> gone = AwayLanes.goneHere(root);

        assertFalse(gone.test(WorkspaceCollaborationService.participantHandle("session-live")), "a live lease keeps its session");
        assertTrue(gone.test(WorkspaceCollaborationService.participantHandle("session-closed")), "a cleanly closed connection's session is gone");
        assertTrue(gone.test(WorkspaceCollaborationService.participantHandle("session-replaced")), "a session no lease names any more is gone");
    }

    @Test
    void aStalledComputerRevokesAnAwayReviewersPendingGrantOnce() throws Exception {
        fixture.snapshot("snap_rel", true);
        fixture.awayIs(State.DISCONNECTED);
        LaneGrant pending = awayReviewerWithoutLane("agt_away_reviewer", "snap_rel");
        LaneGrantId id = new LaneGrantId(pending.grantId());
        assertFalse(fixture.project.store().workGroupProjection().grantRevoked(id));

        release();
        release();

        assertTrue(fixture.project.store().workGroupProjection().grantRevoked(id), "the away reviewer's grant was revoked");
        assertEquals(1, revocations(), "a second pass revokes nothing more");
    }

    @Test
    void nothingIsReleasedWhileTheAwayComputerIsConnected() throws Exception {
        fixture.snapshot("snap_rel", true);
        fixture.awayIs(State.CONNECTED);
        LaneGrant pending = awayReviewerWithoutLane("agt_away_reviewer", "snap_rel");

        release();

        assertFalse(fixture.project.store().workGroupProjection().grantRevoked(new LaneGrantId(pending.grantId())));
        assertEquals(0, revocations());
    }

    /**
     * A lease that cannot be read may be the live one: with it, a reviewer here that no readable lease names would look
     * gone. Nobody is judged gone, and nothing is released, until every lease reads.
     */
    @Test
    void anUnreadableLeaseMeansNobodyHereIsGoneAndNothingIsReleased() throws Exception {
        fixture.snapshot("snap_rel", true);
        fixture.awayIs(State.CONNECTED);
        notStalled();
        LaneGrant pending = reviewerWithoutLane(fixture.project.store(), fixture.project.identity, "agt_unnamed_reviewer", "snap_rel");
        Path root = fixture.project.location.root();
        SessionLeaseService leases = new SessionLeaseService();
        leases.createOrRenewLease(root, fixture.project.location.projectId().toString(), "codex", "conn-closed", fixture.project.identity.nodeId(), "session-closed",
                new SessionLeasePolicy());
        assertTrue(leases.markClosedCleanly(root, "conn-closed"));
        Files.writeString(SessionLeaseStore.resolveLeasesDirectory(root).resolve("corrupt.json"), "{not a lease");

        Predicate<String> gone = AwayLanes.goneHere(root);
        release(gone);

        assertFalse(gone.test(WorkspaceCollaborationService.participantHandle("session-closed")), "even a closed session is not judged gone");
        assertFalse(gone.test("agt_unnamed_reviewer"), "a session no readable lease names is not judged gone");
        assertFalse(fixture.project.store().workGroupProjection().grantRevoked(new LaneGrantId(pending.grantId())));
        assertEquals(0, revocations());
    }

    @Test
    void releaseNeverThrowsWhenTheLogCannotBeUsed() {
        AwayLanes.releaseUnreachableReviews(null, temp.resolve("nowhere"), fixture.project.identity, participant -> true);
    }
}
