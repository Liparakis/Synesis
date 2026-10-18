package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
import org.synesis.coordination.replication.LeaderReplication.MemberConnection.State;
import org.synesis.workspace.application.collaboration.AwayLanes;

/**
 * Which lanes of other computers this computer may take over, and which reviews of away reviewers it releases, judged
 * by this computer alone (ADR-0098 step 7, designer items 9 to 11 and 17).
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
        AwayLanes.releaseStalledReviews(fixture.project.store(), fixture.project.location.coordinationDirectory(), fixture.project.identity);
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
        LaneGrant grant = new LaneGrant(UUID.randomUUID(), fixture.project.group, fixture.awayLane.intentId(), participant, 1, true, snapshot);
        UUID reviewerLane = UUID.randomUUID();
        WorkIntent introduced = new WorkIntent(reviewerLane, fixture.project.location.projectId(), participant, "codex", UUID.randomUUID(), "review", "acceptance",
                fixture.base, List.of(ResourceSelector.pathExact("src/review-" + participant + ".txt")), 1, fixture.project.group,
                WorkIntent.defaultAuthorityLineage(reviewerLane), WorkIntent.Status.ANNOUNCED);
        var awayServices = new WorkIntentService(fixture.awayStore, fixture.away);
        awayServices.announce(introduced);
        awayServices.release(reviewerLane, participant);
        fixture.awayStore.append(grant.grantId(), PredictionEventType.LANE_GRANT_ISSUED, fixture.away.nodeId(), CollaborationCodec.encodeLaneGrant(grant), fixture.away);
        fixture.awayStore.append(grant.grantId(), PredictionEventType.LANE_GRANT_CONSUMED, fixture.away.nodeId(), CollaborationCodec.encodeLaneGrant(grant), fixture.away);
        fixture.sync();
        return grant;
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

    @Test
    void releaseNeverThrowsWhenTheLogCannotBeUsed() {
        AwayLanes.releaseStalledReviews(null, temp.resolve("nowhere"), fixture.project.identity);
    }
}
