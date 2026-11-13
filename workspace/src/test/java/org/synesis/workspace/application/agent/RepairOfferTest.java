package org.synesis.workspace.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.agent.workflow.AgentNextActionService;
import org.synesis.workspace.application.agent.workflow.NextActionRequest;
import org.synesis.workspace.application.collaboration.CombineFixture;
import org.synesis.workspace.application.collaboration.RepairLaneOffer;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRequest;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.test.TestGit;

/**
 * A snapshot that could not be merged waits for a repair lane (ADR-0098 step 5 item 54, s11b-run1): an idle agent other
 * than the author is offered it, takes it once, and a second taker is refused.
 */
final class RepairOfferTest {

    private static final String AUTHOR = "repair-author";
    private static final String TAKER = "repair-taker";
    private static final String LATE = "repair-late";
    private static final String FILE = "src/repair.txt";

    @TempDir
    Path temp;

    private CombineFixture fixture;
    private WorkIntent lane;
    private TaskSnapshotRecord snapshot;

    @BeforeEach
    void setUp() throws Exception {
        fixture = CombineFixture.create(temp);
        ProviderSessionBindingService.Binding binding = fixture.session(AUTHOR);
        List<ResourceSelector> selectors = List.of(ResourceSelector.pathExact(FILE));
        lane = fixture.collaboration.announce(fixture.root, CombineFixture.PROVIDER, AUTHOR, "change repair.txt", "repair.txt changed", selectors).intent();
        Path worktree = Path.of(binding.worktreePath());
        Files.createDirectories(worktree.resolve(FILE).getParent());
        Files.writeString(worktree.resolve(FILE), "author\n");
        snapshot = new TaskSnapshotService().createSnapshot(new SnapshotRequest(lane.taskId(), "node", binding.supervisorId(), binding.workerId(), binding.sessionId(), worktree,
                fixture.root, "repair.txt", java.util.Optional.empty(), List.of(), SnapshotRequest.ClaimScope.unscoped(selectors),
                SnapshotRequest.Lane.of(lane.workGroupId(), lane.intentId(), fixture.participant(AUTHOR), binding.sessionId(), lane.version(), List.of()), false, null, null));
        PredictionEventStore store = fixture.store();
        store.append(snapshot.taskId(), PredictionEventType.TASK_SNAPSHOT_CREATED, fixture.identity.nodeId(),
                new TaskSnapshotPayload(snapshot.taskId(), snapshot.snapshotId(), snapshot.nodeId(), snapshot.supervisorId(), snapshot.workerId(), snapshot.providerSessionId(),
                        snapshot.baseCommit(), snapshot.commitSha(), snapshot.changedPaths(), snapshot.capabilityDependencies(), snapshot.summary(), snapshot.provenance(), false).encode(),
                fixture.identity);
        // main moves under the same file, so merging the snapshot conflicts.
        Files.writeString(fixture.root.resolve(FILE), "main\n");
        TestGit.run(fixture.root, "add", FILE);
        TestGit.run(fixture.root, "commit", "-q", "-m", "main moved");
        String head = TestGit.output(fixture.root, "rev-parse", "HEAD").trim();
        for (var step : List.of(new Object[] {PredictionEventType.INTEGRATION_ATTEMPT_STARTED, "started", ""},
                new Object[] {PredictionEventType.INTEGRATION_CONFLICTED, "conflict", "merge conflict"}, new Object[] {PredictionEventType.REPAIR_REQUIRED, "conflict", "merge conflict"})) {
            fixture.store().append(java.util.UUID.randomUUID(), (PredictionEventType) step[0], fixture.identity.nodeId(),
                    new IntegrationAttemptPayload("repair-attempt", fixture.location.projectId(), List.of(snapshot.snapshotId()), head, "", (String) step[1], (String) step[2]).encode(),
                    fixture.identity);
        }
        fixture.session(TAKER);
        fixture.session(LATE);
    }

    private String offered(String connection) throws Exception {
        AgentResponse next = new AgentNextActionService().getNextAction(new NextActionRequest(fixture.root, CombineFixture.PROVIDER, connection));
        return next.toJson();
    }

    @Test
    void anIdleAgentOtherThanTheAuthorIsOfferedTheRepairAndTheAuthorIsNot() throws Exception {
        var node = fixture.identity.nodeId();
        assertEquals(List.of(snapshot.snapshotId()), RepairLaneOffer.offeredTo(fixture.store(), List.of(fixture.participant(TAKER)), node).stream().map(TaskSnapshotRecord::snapshotId).toList());
        assertEquals(List.of(), RepairLaneOffer.offeredTo(fixture.store(), List.of(fixture.participant(AUTHOR)), node), "the author may not repair its own lane");
        assertEquals(List.of(), RepairLaneOffer.offeredTo(fixture.store(), List.of(fixture.participant(TAKER)), "another-computer"), "only the author's computer offers it");

        String shown = offered(TAKER);

        assertTrue(shown.contains("repairCandidates") && shown.contains(snapshot.snapshotId()) && shown.contains("repairSnapshotId"), shown);
        assertFalse(offered(AUTHOR).contains("repairCandidates"));
    }

    @Test
    void whileTheRepairLaneWorksTheAuthorIsToldToWaitNotSentToEnsureSession() throws Exception {
        fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, TAKER, lane.intentId(), snapshot.snapshotId());

        String author = offered(AUTHOR);

        assertTrue(author.contains("\"nextAction\":\"wait\"") && author.contains("repair_required"), "the author waits for the repair in flight: " + author);
    }

    @Test
    void theFirstTakerGetsTheRepairLaneAndASecondTakerIsRefused() throws Exception {
        var joined = fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, TAKER, lane.intentId(), snapshot.snapshotId());

        assertTrue(joined.acquired());
        assertEquals(fixture.participant(TAKER), joined.intent().participant());
        assertEquals(lane.selectors(), joined.intent().selectors());
        assertEquals(lane.authorityLineageId(), joined.intent().authorityLineageId());
        assertThrows(Exception.class, () -> fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, LATE, lane.intentId(), snapshot.snapshotId()));
        assertThrows(Exception.class, () -> fixture.collaboration.joinRepair(fixture.root, CombineFixture.PROVIDER, AUTHOR, lane.intentId(), snapshot.snapshotId()));
        assertEquals(List.of(), RepairLaneOffer.offeredTo(fixture.store(), List.of(fixture.participant(LATE)), fixture.identity.nodeId()), "once taken it is offered no more");
    }
}
