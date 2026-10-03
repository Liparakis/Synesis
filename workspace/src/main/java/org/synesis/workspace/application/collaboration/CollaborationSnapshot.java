package org.synesis.workspace.application.collaboration;

import java.util.List;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Public collaboration discovery projection shared by CLI and MCP adapters.
 *
 * @param intents      active and terminal work intents
 * @param requests     coordination requests
 * @param participants participant states
 * @param groups       logical work groups
 * @param grants       targeted lane grants
 * @param snapshots    immutable task snapshots available for review
 */
public record CollaborationSnapshot(List<WorkIntent> intents, List<CoordinationRequest> requests, List<Participant> participants, List<WorkGroup> groups, List<LaneGrant> grants, List<TaskSnapshotRecord> snapshots) {

}
