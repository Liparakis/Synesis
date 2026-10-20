import {recordsOfWorkGroup} from "./coordinationScope";
import type {Snapshot} from "../api/controlPlane";
import {mockSnapshot} from "../dev/mockSnapshot";

describe("the WorkGroup of a record", () => {
  const snapshot = (overrides: Partial<Snapshot>): Snapshot => ({...mockSnapshot, agents: [], trash: [], ...overrides});

  it("uses the WorkGroup the snapshot names for a task, an ownership and a dispute, whatever an agent's work says", () => {
    const records = recordsOfWorkGroup(snapshot({
      // The agent's work pairs task-1 with wg-b, but the snapshot knows exactly that it belongs to wg-a.
      agents: [{...mockSnapshot.agents[0], currentWork: {...mockSnapshot.agents[0].currentWork!, taskId: "task-1", workGroupId: "wg-b"}}],
      tasks: [
        {id: "task-1", title: "In a", capability: "c", owner: "", workGroupId: "wg-a"},
        {id: "task-2", title: "In b", capability: "c", owner: "", workGroupId: "wg-b"},
      ],
      ownerships: [
        {capability: "c1", taskId: "task-1", owner: "", protectedScopes: [], intentVersion: 1, workGroupId: "wg-a"},
        {capability: "c2", taskId: "task-2", owner: "", protectedScopes: [], intentVersion: 1, workGroupId: "wg-b"},
      ],
      disputes: [
        {snapshotId: "snap-a", taskId: "task-1", workGroupId: "wg-a", verdicts: {}, state: "review_disputed"},
        {snapshotId: "snap-b", taskId: "task-2", workGroupId: "wg-b", verdicts: {}, state: "review_disputed"},
      ],
    }), "wg-a");

    expect(records.tasks.map((task) => task.id)).toEqual(["task-1"]);
    expect(records.ownerships.map((ownership) => ownership.capability)).toEqual(["c1"]);
    expect(records.disputes.map((dispute) => dispute.snapshotId)).toEqual(["snap-a"]);
  });

  it("falls back to the agent's work for a record with no WorkGroup, and shows a task paired with none under every group", () => {
    const records = recordsOfWorkGroup(snapshot({
      agents: [{...mockSnapshot.agents[0], currentWork: {...mockSnapshot.agents[0].currentWork!, taskId: "task-1", workGroupId: "wg-b"}}],
      tasks: [
        {id: "task-1", title: "Paired with wg-b", capability: "c", owner: "", workGroupId: null},
        {id: "task-2", title: "Paired with none", capability: "c", owner: ""},
      ],
      ownerships: [],
      disputes: [],
    }), "wg-a");

    expect(records.tasks.map((task) => task.id)).toEqual(["task-2"]);
  });
});
