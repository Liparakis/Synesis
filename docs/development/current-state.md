# Synesis Repository State Audit

**Date**: July 22, 2026
**Repository Branch**: `master`
**Latest Checkpoint**: `CP-0092`
**Build Status**: `PASS` (`.\gradlew.bat clean check --dependency-verification=strict`)

---

## 1. Executive Summary & Task Status Audit

### Active Capability Summary
- **`:project-record` Module**:
  - `ProjectConstraint`: Domain model supporting typed project constraints (`constraint:v1`) with `BLOCK` / `WARN` effects, `ACTIVE` / `INACTIVE` / `SUPERSEDED` statuses, and `LEGACY_INFERRED` fallback for historical `CONSTRAINT:` records.
  - `ScopeMatcher`: Deterministic path normalization and wildcard scope matching engine (`*` and `**` glob support, rejection of `..` traversal and absolute paths).
  - `ProjectReconciliationSync` (PRP1): Project-wide bidirectional reconciliation protocol (`0x50525031`).
- **`:workspace` Module**:
  - `WorkspaceCli`: Added `constraint create`, `hook claude-code`, updated `check-action` to evaluate typed constraints against normalized scope paths.
  - `ClaudeCodeHookAdapter`: Pre-tool execution hook adapter translating Claude Code pre-tool hook events into Synesis action checks, returning JSON `{"decision": "deny", "reason": "..."}` to block protected file edits before file modification.

---

## 2. Module & Capability Breakdown

### A. Implemented and Tested (`:link`, `:cli`, `:project-record`, `:workspace`)

| Category | Module | Implementation Classes | Verified Guarantees |
| :--- | :--- | :--- | :--- |
| **Node Identity & Crypto** | `:link` | `NodeIdentity`, `Ed25519Signer` | Ed25519 keypair generation, local persistence, canonical signing, public-key verification, safe node ID derivation. |
| **Session & Transport** | `:link` | `PeerSession`, `SessionAuthenticator`, `NettyControlStream` | QUIC session establishment, ALPN negotiation, replay guard, reciprocal CONTROL_READY, graceful close. |
| **Domain & Constraints** | `:project-record` | `DecisionRecord`, `ProjectConstraint`, `ScopeMatcher` | Immutable canonical signed SDR1 records, typed constraint evidence (`constraint:v1`), legacy fallback, deterministic scope path matcher. |
| **Local Store & PRP1 Sync**| `:project-record` | `DecisionStore`, `ProjectReconciliationSync` | File-based record store, magic prefix `0x50525031` PRP1 project-wide reconciliation over authenticated Link application streams. |
| **Workspace & Guardrails** | `:workspace` | `WorkspaceCli`, `ClaudeCodeHookAdapter` | Single-link guided onboarding (`sync host`/`sync join`), `constraint create`, `check-action` guardrail, `hook claude-code` pre-action harness adapter. |

### B. Documented Limitations & Harness Enforcement Boundaries

- **Harness Integration Scope**: Synesis enforces constraints at integration points that invoke its guardrail (`check-action` or `hook claude-code`). It does not force an LLM to obey constraints outside those integration points.
- **Claude Code Adapter Limitation**: Enforces supported structured file-edit tools (`Edit`, `Write`, `str_replace_editor`, `write_file`). It does not guarantee static interception of arbitrary shell mutations executed via raw bash commands.
- **Protocol Status**: PRP1 and SDR1 are frozen.
