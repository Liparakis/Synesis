package org.synesis.workspace.application.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.collaboration.WorkTrash;
import org.synesis.workspace.test.TakenOverFixture;

/**
 * An agent whose lane another computer took over, and an agent whose work went to the trash, are told in every
 * next-action answer (ADR-0098 step 7, designer items 12 and 14).
 */
final class TrashNextActionTest {

    @TempDir
    Path temp;

    private TakenOverFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new TakenOverFixture(temp);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer(String connection) {
        AgentResponse response = new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, TakenOverFixture.PROVIDER, connection));
        assertInstanceOf(Map.class, response.result(), response.toJson());
        return (Map<String, Object>) response.result();
    }

    @Test
    void theDetachedFormerOwnerIsToldItsLaneWasTakenOverAndItsWorkIsInTheTrash() throws Exception {
        fixture.dirtyOwnersWorktree();
        WorkIntent successor = fixture.otherComputerTakesTheLaneOver();
        WorkTrash.sweep(fixture.location, fixture.store(), fixture.identity);

        Map<String, Object> answer = answer(TakenOverFixture.OWNER);

        Object taken = answer.get("laneTakenOver");
        assertInstanceOf(Map.class, taken, answer.toString());
        Map<?, ?> notice = (Map<?, ?>) taken;
        assertEquals(fixture.lane.intentId().toString(), notice.get("laneId"));
        assertEquals(successor.participant(), notice.get("takenOverBy"));
        assertEquals(successor.intentId().toString(), notice.get("successorIntentId"));
        Object trashed = answer.get("trashedWork");
        assertInstanceOf(List.class, trashed, answer.toString());
        Map<?, ?> work = (Map<?, ?>) ((List<?>) trashed).getFirst();
        assertEquals("TAKEN_OVER", work.get("reason"));
        assertEquals(fixture.lane.intentId().toString(), work.get("laneId"));
    }

    @Test
    void theNoticeStaysAfterTheTakersLaneIsGoneButTheTrashedWorkOfAnEmptiedItemIsNotListed() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        WorkTrash.sweep(fixture.location, fixture.store(), fixture.identity);
        WorkTrash.empty(fixture.location, fixture.identity, fixture.lane.intentId(), fixture.lane.version());

        Map<String, Object> answer = answer(TakenOverFixture.OWNER);

        assertTrue(answer.containsKey("laneTakenOver"), answer.toString());
        assertFalse(answer.containsKey("trashedWork"), answer.toString());
    }

    @Test
    void aSessionThatOwnedNothingIsToldNothing() throws Exception {
        fixture.dirtyOwnersWorktree();
        fixture.otherComputerTakesTheLaneOver();
        WorkTrash.sweep(fixture.location, fixture.store(), fixture.identity);

        Map<String, Object> answer = answer(TakenOverFixture.RESTORER);

        assertFalse(answer.containsKey("laneTakenOver"), answer.toString());
        assertFalse(answer.containsKey("trashedWork"), answer.toString());
    }

    @Test
    void nothingIsAddedBeforeAnyTakeover() throws Exception {
        Map<String, Object> answer = answer(TakenOverFixture.OWNER);

        assertFalse(answer.containsKey("laneTakenOver"), answer.toString());
        assertFalse(answer.containsKey("trashedWork"), answer.toString());
    }
}
