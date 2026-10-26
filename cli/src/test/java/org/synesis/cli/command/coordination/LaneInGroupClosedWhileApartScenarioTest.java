package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.domain.collaboration.WorkGroup;

/**
 * Scenario from the real run s5-run18 (ADR-0098 step 5 item 33): while the Link is cut, one computer completes a work
 * group and the other announces a reviewer lane into it. After the heal that lane can never finish, because every finish
 * path refuses a group that is not active, so its next action tells it to end itself, which keeps what it recorded and
 * frees the agent.
 */
@Timeout(300)
final class LaneInGroupClosedWhileApartScenarioTest {

    @TempDir
    Path temp;
    private ScenarioLab lab;

    @BeforeEach
    void linkedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
    }

    @AfterEach
    void stop() {
        lab.close();
    }

    @Test
    void aReviewerLaneAnnouncedIntoAGroupTheOtherComputerCompletedWhileApartIsToldToEndAfterTheHeal() throws Exception {
        Computer writing = lab.owner;
        Computer reviewing = lab.friend;
        ScenarioAgent writer = lab.agent(writing, "writer");
        ScenarioAgent here = lab.agent(writing, "reviewer-here");
        ScenarioAgent stranded = lab.agent(reviewing, "reviewer-there");

        writer.claim("Write gcd.txt", "gcd.txt");
        writer.create("gcd.txt", "def gcd(a, b): return a\n");
        writer.publish();
        lab.await("the other computer holds the writer's lane", () -> activeLanes(reviewing) >= 1);

        lab.split();
        stranded.joinAsReviewer("reviews/there.md", "gcd.txt");
        here.joinAsReviewer("reviews/here.md", "gcd.txt");
        here.reviews(ScenarioAgent.Review.READ_THEN_ACCEPT, "gcd.txt");
        lab.drive("this computer's reviewer records its verdict", () -> verdicts(writing) == 1, writer, here);
        here.create("reviews/here.md", "accepted\n");
        here.finish();
        lab.drive("the group is completed without the other computer's lane", () -> groupStatus(writing) == WorkGroup.Status.COMPLETED, writer, here);

        lab.heal();
        lab.await("the reviewer lane of the other computer sees the completed group", () -> groupStatus(reviewing) == WorkGroup.Status.COMPLETED);
        Map<String, Object> told = stranded.next();
        assertTrue(told.toString().contains("LANE_GROUP_CLOSED"), "told to end the lane: " + told);
        assertTrue(told.toString().contains("cancel_lane"), told.toString());

        Map<String, Object> ended = stranded.call("cancel_lane", Map.of("reason", "the work group was closed while the computers were apart"));
        assertEquals("completed", ended.get("status"), ended.toString());
        assertFalse(stranded.next().toString().contains("LANE_GROUP_CLOSED"), "the lane is ended");
        assertEquals(WorkGroup.Status.COMPLETED, groupStatus(reviewing), "a closed group never reopens");
    }

    private static long verdicts(Computer computer) {
        try {
            return computer.store().workGroupProjection().reviewValidations().stream().filter(verdict -> "ACCEPTED".equals(verdict.result())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    private static long activeLanes(Computer computer) {
        try {
            return computer.store().collaborationProjection().activeIntents().size();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    private static WorkGroup.Status groupStatus(Computer computer) {
        try {
            var store = computer.store();
            return store.workGroupProjection().groups().stream().findFirst().orElseThrow().status();
        } catch (Exception unreadable) {
            return null;
        }
    }
}
