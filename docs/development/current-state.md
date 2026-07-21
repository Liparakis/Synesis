# Synesis Repository State Audit

**Date**: July 22, 2026
**Repository Branch**: `master`
**Latest Checkpoint**: `CP-0093`
**Build Status**: `PASS` (`.\gradlew.bat clean check --dependency-verification=strict`)

---

## 1. Executive Summary & Task Status Audit

### Active Capability Summary
- **`:project-record` Module**:
  - `DecisionRecord`: Canonical signed SDR2 record format (`0x53445232`, `VERSION = 2`) with explicit `RecordType` (`DECISION` vs `PROJECT_CONSTRAINT`) and binary-embedded `ConstraintPayload`.
  - `ProjectConstraint`: Domain model supporting typed project constraints with `BLOCK` / `WARN` effects, `ACTIVE` / `INACTIVE` / `SUPERSEDED` statuses, and explicit supersedes lists. Legacy title-prefix fallbacks have been removed.
  - `ScopeMatcher`: Deterministic path normalization and wildcard scope matching engine (`*` and `**` glob support).
  - `ProjectReconciliationSync` (PRP1): Project-wide bidirectional reconciliation protocol (`0x50525031`).
- **`:workspace` Module**:
  - `WorkspaceCli`: Updated `constraint create`, `check-action`, and `hook claude-code` commands to evaluate SDR2 typed constraints.
  - `ClaudeCodeHookAdapter`: Pre-tool execution hook adapter with observable `WARNING` and `UNSUPPORTED` diagnostics, returning JSON `{"decision": "deny", "reason": "..."}` for `BLOCK` constraints.
- **Validation Suite**:
  - `scripts/run-synesis-guardrail-experiment.ps1`: Automated baseline vs. Synesis experiment runner proving protected file preservation and zero false positives.
  - `docs/validation/baseline-vs-synesis-experiment.md`: Experiment specification and metrics report.

---

## 2. Module & Capability Breakdown

### A. Implemented and Tested (`:link`, `:cli`, `:project-record`, `:workspace`)

| Category | Module | Implementation Classes | Verified Guarantees |
| :--- | :--- | :--- | :--- |
| **Node Identity & Crypto** | `:link` | `NodeIdentity`, `Ed25519Signer` | Ed25519 keypair generation, local persistence, canonical signing, public-key verification, safe node ID derivation. |
| **Session & Transport** | `:link` | `PeerSession`, `SessionAuthenticator`, `NettyControlStream` | QUIC session establishment, ALPN negotiation, replay guard, reciprocal CONTROL_READY, graceful close. |
| **Domain & Constraints** | `:project-record` | `DecisionRecord` (SDR2), `ProjectConstraint`, `ScopeMatcher` | Immutable canonical signed SDR2 records, explicit typed constraint payloads, clean lifecycle, deterministic scope path matcher. |
| **Local Store & PRP1 Sync**| `:project-record` | `DecisionStore`, `ProjectReconciliationSync` | File-based record store, magic prefix `0x50525031` PRP1 project-wide reconciliation over authenticated Link application streams. |
| **Workspace & Guardrails** | `:workspace` | `WorkspaceCli`, `ClaudeCodeHookAdapter` | Single-link guided onboarding (`sync host`/`sync join`), `constraint create`, `check-action` guardrail, `hook claude-code` pre-action harness adapter. |

### B. Documented Limitations & Harness Enforcement Boundaries

- **Harness Integration Scope**: Synesis enforces constraints at integration points that invoke its guardrail (`check-action` or `hook claude-code`).
- **Claude Code Adapter Scope**: Enforces supported structured file-edit tools (`Edit`, `Write`, `str_replace_editor`, `write_file`, `file_edit`, `file_write`, `NotebookEdit`). It emits `UNSUPPORTED` diagnostics for raw un-parsed shell commands (`Bash`).
- **Protocol Status**: PRP1 and SDR2 are canonical and active. Unreleased `SDR1` development records fail explicitly.
