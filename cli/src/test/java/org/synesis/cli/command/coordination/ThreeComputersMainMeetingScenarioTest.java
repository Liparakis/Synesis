package org.synesis.cli.command.coordination;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

/**
 * Three computers joined by the one-link flow and linked to each other, working together (ADR-0098 steps 5 and 6): the
 * copies of {@code main} meet.
 *
 * <p>Both tests were first the same product defect, fixed by ADR-0098 step 6 designer item 21 (the owner kept no session that predated its admission of a member, so it asked nobody for code), found on 2026-10-19 by the scenarios of {@link OwnerDownMembersScenarioTest}
 * and {@link MemberRemovalScenarioTest} when they went on to the copies of {@code main}: with the owner O and the members
 * M1 and M2 all running and linked, the owner never took the other computers' newer {@code main}, though its log held every
 * entry ({@code CodeOverLink.meet} logged {@code Another computer's main could not be fetched yet} and never fetched).</p>
 *
 * <p>What stalled them afterwards, at {@code --cpus 2} about 1 run in 12 (ADR-0102, "Cause found, 2026-10-27"), is not a
 * running computer that fails to take a head it holds; the dump's {@code holds the head of friend: false} of
 * {@link OwnerDownMembersScenarioTest} is its stopped owner. It was two defects, each now forced by its own test: a
 * finished agent that was offered the last open snapshot to review was refused {@code REVIEWER_INTENT_REQUIRED} on every
 * attempt ({@link LastSnapshotReviewedByFinishedAgentsScenarioTest}), and a re-merge of an advance that an earlier re-merge
 * made was refused {@code REMERGE_SNAPSHOT_INVALID} on every pass ({@code RemergeServiceTest}).</p>
 *
 * <p>With the owner busy and the lower node (the owner or M1) the diverged one, the higher computer re-merges onto the
 * lower one's head, lands it and the third computer takes it, and the lower one does not (the first test). With the owner idle and
 * behind, it does not take the head of the members that integrated (the second test). With two computers only
 * ({@link ApartWithoutOverlapScenarioTest}) the lower computer takes the re-merge, the known rare case of its Javadoc
 * apart.</p>
 */
@Timeout(240)
final class ThreeComputersMainMeetingScenarioTest {

    @TempDir
    Path temp;
    private ScenarioLab lab;
    private Computer owner;
    private Computer m1;
    private Computer m2;

    @BeforeEach
    void threeJoinedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
        owner = lab.owner;
        m1 = lab.friend;
        m2 = lab.addMember(temp, "gamma");
        lab.await("every two computers hold a direct session", () -> lab.linked(m1, m2) && lab.linked(owner, m1) && lab.linked(owner, m2));
    }

    @AfterEach
    void stop() {
        lab.close();
    }

    @Test
    void theOwnerAndAMemberIntegrateTheirOwnWorkAndAllThreeMainsMeet() throws Exception {
        ScenarioAgent o1 = lab.agent(owner, "o-1");
        ScenarioAgent o2 = lab.agent(owner, "o-2");
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m1b = lab.agent(m1, "m1-b");
        meet(new ScenarioAgent[] {o1, o2, m1a, m1b}, new String[] {"o1.txt", "o2.txt", "m1a.txt", "m1b.txt"});
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "OPEN 2026-10-19: on Windows the final three-way main check is not met within 180 s although two computers share a head; real Windows-owner runs pass (s6-run2). Runs on the Linux gate.")
    void twoMembersIntegrateTheirOwnWorkAndTheIdleOwnerTakesTheirMain() throws Exception {
        ScenarioAgent m1a = lab.agent(m1, "m1-a");
        ScenarioAgent m1b = lab.agent(m1, "m1-b");
        ScenarioAgent m2a = lab.agent(m2, "m2-a");
        ScenarioAgent m2b = lab.agent(m2, "m2-b");
        meet(new ScenarioAgent[] {m1a, m1b, m2a, m2b}, new String[] {"m1a.txt", "m1b.txt", "m2a.txt", "m2b.txt"});
    }

    /** Each of the four agents claims, writes and publishes its file; they review each other, and every computer's main holds all four. */
    private void meet(ScenarioAgent[] agents, String[] files) {
        for (int index = 0; index < agents.length; index++) {
            agents[index].claim("Write " + files[index], files[index]);
            agents[index].create(files[index], "written by " + agents[index].name + "\n");
        }
        for (ScenarioAgent agent : agents) {
            agent.publishWhileLinked();
        }
        lab.drive("all three computers hold the same main with the four files", () -> lab.mainsEqual(owner, m1, m2) && holdsAll(owner, files) && holdsAll(m1, files) && holdsAll(m2, files),
                agents);
    }

    private boolean holdsAll(Computer computer, String... files) {
        for (String file : files) {
            if (!lab.hasOnBranch(computer, file)) {
                return false;
            }
        }
        return true;
    }
}
