package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The real runs sbx-run4 and s5-run31 (VM, 2026-10-27): the owner never signed a policy, because {@code init} cannot ask
 * for a passphrase, and the member's every {@code ensure_session} with a task failed with {@code internal_failure}. A
 * member admitted by the owner applies the same rule as the owner: with no baseline in the log, the committed file is the
 * policy.
 */
@Timeout(240)
final class MemberWithoutSignedPolicyScenarioTest {

    @TempDir
    Path temp;
    private ScenarioLab lab;

    @BeforeEach
    void linkedComputersWithNoSignedPolicy() throws Exception {
        lab = ScenarioLab.open(temp, false);
    }

    @AfterEach
    void stop() {
        lab.close();
    }

    @Test
    void aMemberOfAProjectWithNoSignedPolicyClaimsAndFinishesATask() throws Exception {
        ScenarioAgent agent = lab.agent(lab.friend, "member-agent");

        agent.claim("Write member.txt", "member.txt");
        agent.create("member.txt", "from the member\n");
        Map<String, Object> finished = agent.finish();

        assertTrue(!"internal_failure".equals(String.valueOf(finished.get("reason"))), "the member's finish is not an internal failure: " + finished);
        lab.await("the member's file is on both computers' main", () -> {
            try {
                return "from the member".equals(GitProcessRunner.run(lab.owner.location.root(), "show", lab.branch + ":member.txt").strip())
                        && "from the member".equals(GitProcessRunner.run(lab.friend.location.root(), "show", lab.branch + ":member.txt").strip());
            } catch (Exception notYet) {
                return false;
            }
        });
        assertEquals("from the member", GitProcessRunner.run(lab.owner.location.root(), "show", lab.branch + ":member.txt").strip());
    }
}
