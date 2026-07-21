# Synesis Repository State Audit

**Date**: July 22, 2026
**Repository Branch**: `master`
**Latest Checkpoint**: `CP-0096` (SYN-009A implementation verified)
**Build Status**: `PASS` (`.\gradlew.bat clean check --dependency-verification=strict`)

---

## 1. Executive Summary & Task Status Audit

### Active Capability Summary
- **Unified CLI / project state (`SYN-009A`)**:
  - `:cli` is the sole application distribution and exposes `synesis init`,
    project/constraint/sync/check-action/hook commands.
  - `:workspace` is a library with application services and no launcher.
  - Project discovery walks upward through `.synesis/project.json`; local
    profile and runtime state live below `.synesis/local`.
- **`:project-record` Module**:
  - `DecisionRecord`: Canonical signed SDR2 record format (`0x53445232`, `VERSION = 2`) with explicit `RecordType` (`DECISION` vs `PROJECT_CONSTRAINT`) and binary `ConstraintPayload`.
  - `ProjectConstraint`: Domain model with `filterEffectiveActive` excluding superseded constraints; scope evaluation via `ScopeMatcher`.
- **`:workspace` Module**:
  - Application services own orchestration; public command ownership is now in `:cli`.
  - `ClaudeCodeHookAdapter`: Conforms to official Claude Code v2.1+ `PreToolUse` contract. Emits `hookSpecificOutput` with `permissionDecision: "deny"`, handles absolute paths via `resolveRelativePath`, and emits `additionalContext` for warnings.
- **Integration & Validation Suite**:
  - `docs/integration/claude-code-hook.json`: Project-local Claude Code hook configuration example.
  - `scripts/run-synesis-guardrail-experiment.ps1`: Automated experiment runner proving PreToolUse hook denial and target file preservation.
  - `docs/validation/baseline-vs-synesis-experiment.md`: Experiment specification and metric results.
  - Provider lifecycle is available through `synesis provider`; Antigravity is
    `BETA`, Claude Code is `EXPERIMENTAL`, and provider metadata is local-only.

---

## 2. Module & Capability Breakdown

### A. Implemented and Tested (`:link`, `:cli`, `:project-record`, `:workspace`)

| Category | Module | Implementation Classes | Verified Guarantees |
| :--- | :--- | :--- | :--- |
| **Node Identity & Crypto** | `:link` | `NodeIdentity`, `Ed25519Signer` | Ed25519 keypair generation, local persistence, canonical signing, public-key verification, safe node ID derivation. |
| **Session & Transport** | `:link` | `PeerSession`, `SessionAuthenticator`, `NettyControlStream` | QUIC session establishment, ALPN negotiation, replay guard, reciprocal CONTROL_READY, graceful close. |
| **Domain & Constraints** | `:project-record` | `DecisionRecord` (SDR2), `ProjectConstraint`, `ScopeMatcher` | Immutable canonical signed SDR2 records, explicit typed constraint payloads, supersession filtering, deterministic scope path matcher. |
| **Local Store & PRP1 Sync**| `:project-record` | `DecisionStore`, `ProjectReconciliationSync` | File-based record store, magic prefix `0x50525031` PRP1 project-wide reconciliation over authenticated Link application streams. |
| **Workspace & Guardrails** | `:workspace` | application services, hook adapters | Guided onboarding (`sync host`/`sync join`), typed constraints, `check-action` guardrail, official provider hook adapters (exit 0 for JSON responses). |

### B. Documented Limitations & Harness Enforcement Boundaries

- **Real-Agent Run**: Authenticated real-agent run status is `NOT_RUN` (CLI session requires interactive login).
- **Harness Integration Scope**: Synesis enforces constraints at integration points that invoke its guardrail (`check-action` or `hook claude-code`).
- **Claude Code Adapter Scope**: Enforces supported structured file-edit tools (`Edit`, `Write`, `str_replace_editor`, `write_file`, `file_edit`, `file_write`, `NotebookEdit`). It emits `UNSUPPORTED` diagnostics on stderr for raw un-parsed shell commands (`Bash`).
