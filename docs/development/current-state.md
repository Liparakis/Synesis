# Synesis Repository State Audit

**Date**: July 21, 2026
**Repository Branch**: `master`
**Latest Checkpoint**: `CP-0090`
**Build Status**: `PASS` (`.\gradlew.bat clean check --dependency-verification=strict`)

---

## 1. Executive Summary & Task Status Audit

### SYN-005 Status Assessment
- **`:project-record` Module (CP-W5)**: **COMPLETE & TESTED**. The Project Reconciliation Protocol (PRP1, magic prefix `0x50525031`) is fully implemented, unit tested, and verified over 2-process Netty application stream loopbacks.
- **`:workspace` Integration (CP-W6)**: **COMPLETE & TESTED**. Guided `sync host` and `sync join` commands invoke `ProjectReconciliationSync` for project-wide reconciliation, and `check-action` evaluates action-time constraints against synchronized decision records.

---

## 2. Module & Capability Breakdown

### A. Implemented and Tested (`:link`, `:cli`, `:project-record`, `:workspace`)

| Category | Module | Implementation Classes | Verified Guarantees |
| :--- | :--- | :--- | :--- |
| **Node Identity & Crypto** | `:link` | `NodeIdentity`, `Ed25519Signer` | Ed25519 keypair generation, local persistence, canonical signing, public-key verification, safe node ID derivation. |
| **Candidate Racing** | `:link` | `CandidateDescriptor`, `CandidateRacer`, `CandidateGatherer` | Normalization, ranking, expiry, non-blocking racing across UDP/TCP/QUIC endpoints. |
| **Session & Transport** | `:link` | `PeerSession`, `SessionAuthenticator`, `NettyControlStream` | QUIC session establishment, ALPN negotiation, replay guard, reciprocal CONTROL_READY, graceful close. |
| **Liveness & Heartbeat** | `:link` | `HeartbeatMessage`, `LivenessTracker` | Periodic bounded ping/pong, deterministic SUSPECT/EXPIRED state transitions. |
| **Onboarding & Invitations** | `:link` | `Onboarding`, `Invitation`, `CompactQr` | Single-use signed invitations, automatic identity bootstrap, terminal QR glyph rendering with fallback URL. |
| **Application Stream Seam** | `:link` | `ApplicationStreamTransport`, `ApplicationStreamCodec` | Transport-neutral bounded frame exchange (`4_096` byte frames) over authenticated sessions. |
| **Standalone CLI** | `:cli` | `SynesisCli`, `HostCommand`, `JoinCommand`, `DoctorCommand` | Picocli terminal command adapter, launcher scripts, clean exit codes, readiness inspection (`doctor`). |
| **Decision Record Domain** | `:project-record` | `DecisionRecord`, `DecisionEvidence`, `DecisionStatus` | Canonical JSON/binary representation, Ed25519 signatures, immutability, evidence binding. |
| **Local Store & Recovery** | `:project-record` | `DecisionStore` | Profile-local filesystem storage (`decisions/`, `heads/`, `conflicts/`), atomic writes, crash recovery, quarantine area. |
| **Searchable Project View** | `:project-record` | `DecisionSearch` | Read-only search over validated head snapshots (`--text` query filtering, tag matching). |
| **SRP1 Single Sync** | `:project-record` | `RecordMessage`, `ProjectRecordSync` | Single-record publish/sync (magic `0x53525031`), APPLIED/DUPLICATE/REMOTE_STALE/CONFLICT outcomes. |
| **PRP1 Reconciliation Protocol** | `:project-record` | `ReconciliationMessage`, `ProjectReconciliationSync` | Project-wide bidirectional reconciliation (magic `0x50525031`), chunked inventory exchange (50 entries/chunk, max 1,000 entries), contiguous revision transfer (max 100 revs/record), 10 MB session bound, local corruption detection, conflict quarantine. |
| **Workspace Profiles** | `:workspace` | `WorkspaceCli`, `ProjectConfig`, `IdentityBootstrap` | Profile isolation (`<profile>/link`, `<profile>/records`, `<profile>/project.conf`), single-link guided host/join onboarding, `--expect-host` fingerprint verification, `check-action` constraint guardrails. |

### B. Documented but Intentionally Deferred / Postponed

- **Background Recon / Daemon**: No automatic background sync, file watcher, or persistent reconciliation daemon. Reconciliation is strictly one-shot operator/agent triggered.
- **Physical Multi-Machine Claims**: Two-machine QUIC transport verified in `PHYSICAL-DEMO-2026-07-20.md`, but physical launcher onboarding remains unverified/unclaimed.
- **Semantic Merging**: No automatic text or structural 3-way merge of divergent decision histories. Divergent heads are quarantined in `conflicts/`.
- **Distributed Scheduler**: No task queue, background worker pool, or lease manager.
