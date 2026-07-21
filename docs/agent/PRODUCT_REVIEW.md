# Product Review & Future Planning (through CP-0084)

This document evaluates the Synesis Link project as a product rather than an architectural exercise, analyzing the current value, operator friction points, comparing three potential next directions, and choosing the next product milestone.

---

## 1. Current Product Capability Summary

Through CP-0084, Synesis implements the following user-facing capabilities:
1. **Cryptographic Identity Bootstrap**: Operators can automatically create, store, and reuse secure Ed25519 node identities (`sl1-...`).
2. **Listener-First Onboarding**: Hosts can generate signed connectivity invitations (`synesis://join/<invitation>`) to establish secure sessions with peers on the same local network.
3. **Guided Workspace Demo Flow (SYN-004)**: Hosts can parameterize invitations with `project`, `record`, and `host` parameters, forming a single convenience URI. Clients parse this URI, perform cryptographic host identity pinning, and complete the connection.
4. **Trust Bootstrapping Safeguard**: For unconfigured projects, clients reject automatic configuration from the convenience URI unless the operator explicitly confirms the host identity fingerprint using `--expect-host <host-node-id>`.
5. **Separated Stderr Diagnostics**: Stderr prints diagnostic `ERROR=` and `HINT=` outputs to keep stdout strictly machine-readable.
6. **Signed Decision Records (SDR1)**: Operators can create signed, versioned, immutable decision records (`PROPOSED`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`) containing title, rationale, and a logical evidence digest (SHA-256).
7. **One-Shot Sync**: Operators can request synchronization of a specific decision record from a configured host, reporting outcomes (`APPLIED`, `DUPLICATE`, `REJECTED`, `UNKNOWN`).
8. **On-Demand Search & Inspection**: Operators can search verified decision heads locally using query terms, status, owner, or limit, and inspect the complete validated revision chain of any single record.

---

## 2. Milestone Comparison Table

| Evaluation Aspect | Direction 1: Project-wide Reconciliation | Direction 2: Product Packaging | Direction 3: First Agent-Work Foundation |
| :--- | :--- | :--- | :--- |
| **Visible User Value** | Operators sync the entire project at once. Eliminates generating/copying invitations for every single decision. | External developers can download, install, and run the demo via single-command launchers. | Proves that agents can cooperatively work on sandboxed tasks without mutating canonical records directly. |
| **Alignment with Vision** | Core requirement for cooperative workspaces: shared, synchronized project history. | Improves onboarding and readability for external evaluators. | Core to the Synesis goal of automated, verifiable agent-based cooperation. |
| **Required Architectural Changes** | Introduce a bulk sync protocol/message layer over the existing authenticated Link stream. | Build system packaging scripts, distribution zips, and clean-room installation validation. | Define new task schemas, sandbox/worktree isolation runner, and proposal receipt logic. |
| **Security & Trust Implications** | Reuses existing cryptographic peer allowlists. Low security risk. | Medium risk (requires secure distribution signing, launcher sanity, and path sanitization). | High risk (requires secure sandbox boundaries to prevent arbitrary code execution on host machines). |
| **Implementation Risk** | Low. Built entirely on top of the current tested Link seam and storage engine. | Low. Primarily gradle packaging and documentation scripting. | High. Sandbox escape, task state conflicts, and environment dependencies. |
| **Demo Impact** | High. Reduces a multi-step copy-paste demo to a single bulk project update command. | Medium. Eases installation but does not change the runtime features. | High. Shows the first automated agent cooperating with a human in the workspace. |
| **What Becomes Possible** | Real-time multi-record workspaces, conflict detection, and history gap filling. | Simple distribution and deployment testing by external teams. | Fully automated, sandboxed agent execution of project tasks. |
| **What Remains Deferred** | Background sync, daemons, multi-peer federation, gossip protocols. | GUI installers, installer registry keys, system tray integrations. | Multi-worker coordination, direct worker write access, complex task dependencies. |

---

## 3. Selected Milestone and Justification

### Selected Milestone: **Direction 1: Project-wide Reconciliation**

### Justification
While **Direction 3 (Agent-Work Foundation)** is the ultimate destination of the Synesis vision, implementing it now represents a premature leap in complexity. An agent-work system requires a reliable, synchronized shared state of the project to retrieve tasks and verify results. Currently, syncing multiple decision records is extremely painful and manual—each record requires its own invitation link and separate connection. 

**Direction 1 (Project-wide Reconciliation)** is the smallest, most secure, and lowest-risk milestone that directly advances Synesis from a single-record state-sync demo toward a useful cooperative workspace. Reconciling all missing or divergent record heads under a project in one single session eliminates the manual invitation loop entirely, providing the robust multi-record synchronization foundation required before any automated task execution can be safely built.

---

## 4. Exact Operator-Visible Outcome

After completing this milestone, Operator B will be able to synchronize the entire project history from Operator A with a single command:

```powershell
& $ws --profile $profileB project sync --project <PROJECT_UUID>
```

**Expected output:**
```text
AUTHENTICATED_REMOTE=sl1-<A_NODE_ID_HEX>
PROJECT_ID=<PROJECT_UUID>
RECONCILED_RECORDS=5
ADDED_RECORDS=3
DUPLICATE_RECORDS=2
SYNC_RESULT=SUCCESS
```

If any conflicts or validation failures occur, they will be reported cleanly to `stderr` with diagnostics:
```text
ERROR=SYNC_CONFLICT
HINT=One or more record heads have divergent signatures. Verify author keys or resolve manually.
```

---

## 5. Proposed Task/Checkpoint Breakdown

1. **SYN-005-PLAN**: Plan the project-wide reconciliation protocol. Define the bulk-sync message format (e.g. `PROJECT_SYNC_REQUEST`, `PROJECT_SYNC_RESPONSE`, `BATCH_RECORD_TRANSFER`) over the existing Link seam.
2. **SYN-005-CP-W5**: Implement bulk project state reconciliation logic in `:project-record` to compute missing or divergent heads between two peers.
3. **SYN-005-CP-W6**: Implement the network protocol and CLI changes. Integrate the batch sync message exchange into the workspace launcher commands.
4. **SYN-005-CP-W7**: Add generated-launcher integration tests proving that multi-record histories with history gaps, matching heads, and corrupt/tampered records are safely and deterministically reconciled.

---

## 6. Invariants and Non-Goals

- **Invariants**:
  - The synchronization session remains strictly single-use and operator-triggered (one-shot).
  - All records must pass signature validation before being written to local storage.
- **Non-Goals**:
  - No background sync daemons, file watchers, or continuous replication loops.
  - No automatic multi-peer routing or gossip (restricted strictly to two allowlisted peers).
  - No network auto-discovery or local network scanning.

---

## 7. Risks and Invalidation Conditions

- **Storage Bloat / DOS Risk**: An allowlisted but compromised peer could send an infinite batch of signed records, exhausting disk space.
  - *Mitigation*: Impose a strict batch size limit (e.g. max 100 records per session) and validate total record count against project configuration limits.
- **Divergent Heads / Conflict Resolution**: If both peers have written different revision 2s to the same record, a state conflict arises.
  - *Mitigation*: Divergent heads that cannot be ordered chronologically must be quarantined immediately to prevent pollution of the active heads view.

---

## 8. Updated Near-Term Roadmap

1. **SL-011 (ACTIVE)**: CLI and release verification.
2. **SYN-005 (READY)**: Project-wide reconciliation (bulk sync of all decision heads).
3. **SYN-006 (DEFERRED)**: Product packaging (installable zip and clean-room launcher verify).
4. **SYN-007 (DEFERRED)**: First agent-work foundation (sandboxed task runner).
