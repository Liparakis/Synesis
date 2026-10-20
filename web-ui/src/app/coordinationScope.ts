import type {CapabilitySnapshot, ClaimSnapshot, DisputeSnapshot, OwnershipSnapshot, Snapshot, TaskSnapshot} from "../api/controlPlane";

/** The coordination records one WorkGroup shows. Capabilities, conversations and operator requests are not tied to a WorkGroup, so they stay project-wide. */
export interface WorkGroupRecords {
  claims: ClaimSnapshot[];
  tasks: TaskSnapshot[];
  capabilities: CapabilitySnapshot[];
  ownerships: OwnershipSnapshot[];
  disputes: DisputeSnapshot[];
}

/**
 * Narrows the snapshot to one WorkGroup. Only claims name their WorkGroup; a task, ownership or dispute reaches one
 * through its task id, which an agent's current work or a trash entry pairs with a WorkGroup. A task the snapshot
 * pairs with no WorkGroup is shown under every group rather than hidden.
 */
export function recordsOfWorkGroup(snapshot: Snapshot, workGroupId: string): WorkGroupRecords {
  const groupsOfTask = new Map<string, Set<string>>();
  const link = (taskId: string | undefined, groupId: string | undefined) => {
    if (!taskId || !groupId) return;
    const groups = groupsOfTask.get(taskId) ?? new Set<string>();
    groupsOfTask.set(taskId, groups.add(groupId));
  };
  for (const agent of snapshot.agents ?? []) link(agent.currentWork?.taskId, agent.currentWork?.workGroupId);
  for (const item of snapshot.trash ?? []) link(item.taskId, item.workGroupId);
  const shown = (taskId: string | undefined) => {
    const groups = taskId === undefined ? undefined : groupsOfTask.get(taskId);
    return !groups || groups.has(workGroupId);
  };
  return {
    claims: snapshot.claims.filter((claim) => claim.workGroupId === workGroupId),
    tasks: snapshot.tasks.filter((task) => shown(task.id)),
    capabilities: snapshot.capabilities,
    ownerships: snapshot.ownerships.filter((ownership) => shown(ownership.taskId)),
    disputes: snapshot.disputes.filter((dispute) => shown(dispute.taskId)),
  };
}

export const claimKey = (claim: ClaimSnapshot) => claim.intentId;
export const taskKey = (task: TaskSnapshot) => task.id;
export const capabilityKey = (capability: CapabilitySnapshot) => capability.handle;
export const ownershipKey = (ownership: OwnershipSnapshot) => ownership.capability + "/" + ownership.taskId;

/** The record a details view opens: the one the person picked, or the first shown when none is picked or the pick left the list. */
export function chosenRecord<T>(rows: T[], keyOf: (row: T) => string, chosenKey: string | null): T | undefined {
  return rows.find((row) => keyOf(row) === chosenKey) ?? rows[0];
}
