package org.synesis.workspace.application.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.task.TaskSnapshotPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.agent.session.AgentSessionService;
import org.synesis.workspace.application.agent.session.SessionResolutionRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.IntegrationValidationRefusalTest.Project;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.test.ProviderTestSupport;

/**
 * This computer ("here": a Git project with one session, the taker) and another one ("away") that put a group and a
 * lane on the log, which reach here by copy. Tests set what the member record says about the away computer.
 */
final class AwayComputerFixture {

    static final String PROVIDER = "codex";
    static final String TAKER = "taker-connection";

    final Project project;
    final NodeIdentity away = NodeIdentity.generate();
    final PredictionEventStore awayStore;
    final WorkIntent awayLane;
    final String takerParticipant;
    final String base;

    AwayComputerFixture(Path root, Path awayRoot) throws Exception {
        Files.createDirectories(root);
        project = new Project(root);
        base = project.head();
        ProviderTestSupport.install(project.location, PROVIDER);
        new ProviderManualService().install(PROVIDER);
        new AgentSessionService().ensureSession(new SessionResolutionRequest(root, PROVIDER, TAKER, null, false));
        ProviderSessionBindingService bindings = new ProviderSessionBindingService();
        var binding = bindings.find(project.location, PROVIDER, TAKER).orElseThrow();
        bindings.verifyWorkspaceTrust(project.location, PROVIDER, binding.sessionId(), Path.of(binding.worktreePath()));
        takerParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());

        awayStore = new PredictionEventStore(awayRoot, project.location.projectId());
        new WorkGroupService(awayStore, away).create(new WorkGroup(project.group, project.location.projectId(), "goal", "acceptance", 1, WorkGroup.Status.ACTIVE));
        UUID laneId = UUID.randomUUID();
        awayLane = new WorkIntent(laneId, project.location.projectId(), "agt_away", PROVIDER, UUID.randomUUID(), "away goal", "acceptance", base,
                List.of(ResourceSelector.pathExact("src/away.txt")), 1, project.group, WorkIntent.Status.ANNOUNCED);
        new WorkIntentService(awayStore, away).announce(awayLane);
        sync();
    }

    Path worktree() throws Exception {
        return Path.of(new ProviderSessionBindingService().find(project.location, PROVIDER, TAKER).orElseThrow().worktreePath());
    }

    /** Away's whole log reaches here, as the exchange would copy it. */
    void sync() throws Exception {
        project.store().placeCopies(away.nodeId(), awayStore.entriesAfter(PredictionEventStore.OWN_LOG, 0, 10_000, Integer.MAX_VALUE));
    }

    /** Sets what this computer's record of member sessions says about the away computer. */
    void awayIs(LeaderReplication.MemberConnection.State state) throws Exception {
        Files.writeString(project.location.coordinationDirectory().resolve(LeaderReplication.MEMBERS_FILE), away.nodeId() + " " + state + " " + Instant.now() + "\n");
    }

    /** The away lane's snapshot: a commit adding src/away.txt on the base, kept in this repository when it reaches here. */
    String snapshot(String snapshotId, boolean reachesHere) throws Exception {
        project.git("checkout", "-q", "--detach", base);
        Files.createDirectories(project.root.resolve("src"));
        Files.writeString(project.root.resolve("src/away.txt"), "away work\n");
        project.git("add", "src/away.txt");
        project.git("commit", "-m", snapshotId);
        String commit = project.head();
        project.git("checkout", "-q", "-");
        String ref = "refs/synesis/snapshots/" + snapshotId;
        if (reachesHere) {
            project.git("update-ref", ref, commit);
        }
        SnapshotProvenance provenance = new SnapshotProvenance(project.group, awayLane.intentId(), awayLane.authorityLineageId(), "agt_away", "away-session", 1,
                List.of(), List.of(), List.of("PATH_EXACT:src/away.txt"), ref, "integrity", "manifest");
        String recorded = reachesHere ? commit : "f".repeat(40);
        awayStore.append(UUID.randomUUID(), PredictionEventType.TASK_SNAPSHOT_CREATED, away.nodeId(), new TaskSnapshotPayload(awayLane.taskId(), snapshotId, away.nodeId(),
                "sup", "worker", "away-session", base, recorded, List.of("src/away.txt"), List.of(), snapshotId, provenance, true).encode(), away);
        sync();
        return commit;
    }
}
