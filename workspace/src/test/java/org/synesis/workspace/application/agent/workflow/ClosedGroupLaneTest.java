package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.completion.AgentTaskCancellationService;
import org.synesis.workspace.application.collaboration.CombineFixture;

/**
 * A lane whose work group was closed while this computer was cut off is told to end itself, and is then free for other
 * work (ADR-0098 step 5 item 33; s5-run18, where a reviewer lane in a group the other computer had completed stayed
 * open and kept its agent from the combine).
 */
final class ClosedGroupLaneTest {

    private static final String STRANDED = "closed-stranded";

    @TempDir
    Path temp;

    private CombineFixture fixture;

    private WorkIntent lane;

    @BeforeEach
    void setUp() throws Exception {
        fixture = CombineFixture.create(temp);
        fixture.session(STRANDED);
        lane = fixture.collaboration.announce(fixture.root, CombineFixture.PROVIDER, STRANDED, "change g", "g changed", List.of(ResourceSelector.pathExact("src/g.txt"))).intent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private AgentResponse nextAction(boolean completionRequested) {
        return new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, STRANDED, completionRequested));
    }

    private void closeLanesGroup(WorkGroup.Status status) throws Exception {
        var store = fixture.store();
        WorkGroup group = store.workGroupProjection().group(lane.workGroupId()).orElseThrow();
        WorkGroup closed = new WorkGroup(group.workGroupId(), group.projectId(), group.goal(), group.acceptance(), group.version() + 1, status, group.reviewPolicy());
        store.append(group.workGroupId(), PredictionEventType.WORK_GROUP_STATUS_CHANGED, fixture.identity.nodeId(), CollaborationCodec.encodeWorkGroup(closed), fixture.identity);
    }

    private void assertToldToEndTheLane(AgentResponse response) {
        assertEquals(AgentStatus.BLOCKED, response.status(), response.toJson());
        Map<String, Object> result = map(response.result());
        assertEquals(NextActionProtocol.STATE_LANE_GROUP_CLOSED, result.get(NextActionKeys.STATE), response.toJson());
        assertEquals(NextActionProtocol.REASON_WORK_GROUP_NOT_ACTIVE, result.get(NextActionKeys.REASON));
        assertTrue(String.valueOf(result.get("instruction")).contains("cancel_lane"), response.toJson());
    }

    @Test
    void aLaneInACompletedGroupIsToldToEndAndThenIsOfferedTheCombine() throws Exception {
        assertFalse(nextAction(false).toJson().contains(NextActionProtocol.STATE_LANE_GROUP_CLOSED), "an active group keeps its lane");
        closeLanesGroup(WorkGroup.Status.COMPLETED);

        assertToldToEndTheLane(nextAction(false));
        assertToldToEndTheLane(nextAction(true));
        assertTrue(fixture.store().collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.intentId().equals(lane.intentId())), "the lane is not ended behind the agent's back");

        AgentResponse ended = new AgentTaskCancellationService().cancelTask(new AgentTaskCancellationService.CancelTaskRequest(fixture.root, CombineFixture.PROVIDER, STRANDED,
                "the work group was closed"));
        assertEquals(AgentStatus.COMPLETED, ended.status(), ended.toJson());

        var store = fixture.store();
        assertTrue(store.collaborationProjection().activeIntents().stream().noneMatch(intent -> intent.intentId().equals(lane.intentId())));
        assertEquals(WorkGroup.Status.COMPLETED, store.workGroupProjection().group(lane.workGroupId()).orElseThrow().status(), "a closed group never reopens");
        AgentResponse freed = nextAction(false);
        List<?> candidates = (List<?>) map(freed.result()).get("combineCandidates");
        assertNotNull(candidates, freed.toJson());
        assertEquals(fixture.combine.combineId(), map(candidates.getFirst()).get("combineId"));
    }

    @Test
    void aCancelledGroupEndsTheLaneTheSameWay() throws Exception {
        closeLanesGroup(WorkGroup.Status.CANCELLED);

        assertToldToEndTheLane(nextAction(false));
    }
}
