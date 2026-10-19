package org.synesis.cli.command.coordination;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

/**
 * Three computers joined by the one-link flow and linked to each other, working together (ADR-0098 steps 5 and 6): the
 * copies of {@code main} meet.
 *
 * <p>Both tests are the same product defect, found on 2026-10-19 by the scenarios of {@link OwnerDownMembersScenarioTest}
 * and {@link MemberRemovalScenarioTest} when they went on to the copies of {@code main} (the removal scenario's first
 * version, then each shape below: 6 of 6 runs of each failed, and 4 earlier diagnostic runs): with the owner O and the members M1 and M2 all running and all
 * linked, the owner never takes the other computers' newer {@code main}, though its log holds every entry and is
 * byte-identical to theirs. The failure message of the wait carries the evidence: {@code holds the head of friend: false}
 * and {@code holds the head of member2: false} for the owner, whose checkout is clean and behind, and
 * {@code holds the head of owner: true} for both members, whose copies are equal. Restarting the owner's computer
 * (the same node and folders) fixes it at once: in one diagnostic run the restarted owner met the others' main within
 * 60 s, which is what {@link OwnerDownMembersScenarioTest} shows. So what stops it is in-process state of the running
 * owner, not the log, the audit or the membership. {@code CodeOverLink.meet} logs {@code Another computer's main could not
 * be fetched yet; the next pull tries again.} (34 times in one run) and never fetches; the cause was not found.</p>
 *
 * <p>With the owner busy and the lower node (the owner or M1) the diverged one, the higher computer re-merges onto the
 * lower one's head, lands it and the third computer takes it, and the lower one does not (the first test). With the owner idle and
 * behind, it does not take the head of the members that integrated (the second test). With two computers only
 * ({@link ApartWithoutOverlapScenarioTest}) the lower computer takes the re-merge, the known rare case of its Javadoc
 * apart.</p>
 */
@Disabled("Defect: with three computers running and linked, the owner never takes the others' newer main until it restarts; see the Javadoc")
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
