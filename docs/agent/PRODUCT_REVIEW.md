# Product Review & Future Planning (through CP-0079)

This document evaluates the Synesis Link project as a product rather than an architectural exercise, analyzing the current value, operator friction points, and potential next steps.

---

## 1. Current Product Capability Summary

Through CP-0079, Synesis implements the following user-facing capabilities:
1. **Cryptographic Identity Bootstrap**: Operators can automatically create, store, and reuse secure Ed25519 node identities (`sl1-...`).
2. **Listener-First Onboarding**: Hosts can generate single-use, signed 10-minute connectivity invitations (`synesis://join/<invitation>`) to establish secure sessions with peers on the same local network.
3. **Control Path Liveness**: Authenticated QUIC connections monitor liveness via a heartbeat protocol with deterministic state transitions (LIVE, SUSPECT, EXPIRED) and graceful teardown.
4. **Project Configuration Enforcement**: Profiles can configure project membership allowlists to prevent unauthorized reads/writes.
5. **Signed Decision Records (SDR1)**: Operators can create signed, versioned, immutable decision records (`PROPOSED`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`) containing title, rationale, and a logical evidence digest (SHA-256).
6. **Self-Healing Storage**: The system runs crash-recovery on startup to repair head pointers based on revision history.
7. **One-Shot Sync**: Operators can request synchronization of a specific decision record from a configured host, reporting outcomes (`APPLIED`, `DUPLICATE`, `REJECTED`, `UNKNOWN`).
8. **On-Demand Search & Inspection**: Operators can search verified decision heads locally using query terms, status, owner, or limit, and inspect the complete validated revision chain of any single record.

---

## 2. Demo Usability Review

### What the Two-Person Demo Proves
The demo successfully proves **secure, cooperative state replication** across two isolated profiles without central authority. It demonstrates that:
- A peer can securely introduce themselves using an invitation.
- A peer can authorize another node for a specific project.
- A peer can create a signed truth record that is cryptographically binding.
- A remote peer can fetch, verify, and store this record with identical cryptographic proof (byte-stable outcomes).
- The synchronization outcome is deterministic.

### Developer Usability
An external developer with standard Java 25 / PowerShell tools **could run it** by following `docs/demo/FIRST_TWO_PERSON_PROJECT_DEMO.md` because the documentation lists every command step-by-step. However, it requires a high degree of care:
- The developer must run multiple commands in two different profile contexts.
- The developer must manually copy-paste Node IDs, Project UUIDs, Record UUIDs, and long Invitation strings between shell sessions.
- A single typing/copy-paste error in expected peer Node IDs leads to exit status 10 (`ERROR=AUTH_FAILED`) without interactive help.
- Therefore, the demo currently feels like a series of low-level diagnostic tools rather than a finished product.

---

## 3. Top Five Friction Points

1. **Manual Node ID Exchange**: Operators must manually share Node IDs out-of-band and supply them via the CLI before any project creation or host/join can begin.
2. **Fragile Session invitations**: If a host/join sync connection fails or liveness expires, the single-use invitation is consumed and destroyed. The host must generate a new invitation, and the joiner must copy-paste it again.
3. **Single-Record Sync Granularity**: The current protocol requires syncing each record ID one-by-one. If a project has 10 decisions, the operator must execute the `sync join` command 10 separate times, exchanging 10 invitations.
4. **Mismatched Configuration Failures**: If a profile mismatch occurs (e.g. project already configured with a different peer), the CLI aborts with exit code 10 and `ERROR=PROJECT_MISMATCH` but provides no option to override or reconfigure.
5. **Unfriendly Diagnostics**: All CLI failures collapse into raw, non-explanatory strings (like `ERROR=LOCAL_STATE_INVALID` or `ERROR=RECORD_INVALID`). There is no context-sensitive troubleshooting output (e.g. "Missing file: 1.sdr" or "Signature verification failed for key X").

---

## 4. Three Credible Next Milestones

### Milestone A: Simplified Operator Experience (Interactive CLI)
Build a wizard-based interactive setup inside the CLI. Instead of copy-pasting raw IDs, the CLI could guide the operator through bootstrap, automatically exchange node credentials during connection, allow interactive project creation, and show interactive progress bars for sync.

### Milestone B: Bulk Sync / Richer Project State
Upgrade the synchronization endpoint and message schema to support bulk synchronization of *all* mismatched decision heads under a project. A single join command would reconcile the entire local store with the remote peer's store, resolving history gaps automatically.

### Milestone C: First Isolated Agent-Alignment Flow
Introduce an autonomous agent loop that monitors project decisions for changes in declarative state or configuration (e.g. policy changes, allowlist updates, or tool version approvals). The agent aligns its local constraints and external actions to match this declared state, writing back verification evidence as a signed status/log record if needed. The decision records themselves remain strictly declarative metadata of project truth, never executable work units, task scripts, or job queues.

---

## 5. Recommendation with Justification

### Recommendation: Milestone A (Simplified Operator Experience)
Focus next on **Milestone A**.

### Justification
The core networking, cryptographic, and storage layers are already implemented, robustly verified, and self-healing. However, the barrier to product adoption is high because the CLI requires tedious, manual parameter copy-pasting. Developing a streamlined, interactive setup flow provides the largest visible improvement to usability. It makes the system feel like a finished utility and reduces the likelihood of operator errors that ruin demonstrations.

---

## 6. Capabilities to Remain Deferred

The following capabilities must remain deferred to avoid scope creep and maintain simplicity:
- **Relays and STUN/TURN**: Keep the direct LAN / UDP candidate constraint; do not implement global NAT traversal.
- **Obsidian / Markdown / GUI Integration**: The CLI remains the sole operator interface; do not build editors or file-watcher bindings.
- **Continuous Background Sync / Daemons**: Keep sync as an on-demand, operator-triggered, one-shot process. Do not run background services or system tray loops.
- **Multi-Peer Federation / Membership Protocols**: Keep the system restricted to direct two-peer configurations; do not implement gossip protocols or consensus layers.
- **Automated Retries / Reconnect**: Network loss must remain classified as session expiry/transport closure; do not add automated resumption loops.
