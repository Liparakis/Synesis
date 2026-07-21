# 17. Clean SDR2 Typed Constraint Model, Removal of Legacy Compatibility, and Guardrail Validation Experiment

- Status: Accepted
- Date: 2026-07-22
- Deciders: Core Architecture Team

## Context

Prior to SYN-007, project constraints relied on transitional string parsing (`kind="constraint:v1"`, `reference="effect=BLOCK|scope=..."`) and legacy title-prefix fallbacks (`CONSTRAINT:`). Because Synesis has not had a public release, carrying unreleased development compatibility debt produces delimiter ambiguity and fragile schema evolution.

SYN-007 removes all legacy compatibility behavior, introduces explicit `SDR2` canonical record versioning, and establishes a reproducible baseline-versus-Synesis guardrail experiment.

## Decision

1. **Removal of Legacy Constraint Fallbacks**:
   - Removed `LEGACY_INFERRED` source classification and title prefix matching (`CONSTRAINT:`).
   - Removed string-encoded evidence reference splitting (`effect=BLOCK|scope=...`).
   - Ordinary decision records (`RecordType.DECISION`) are never evaluated as blocking constraints regardless of title or evidence content.

2. **Canonical SDR2 Record Format (`0x53445232`, VERSION = 2)**:
   - Introduced explicit `RecordType` byte enum: `DECISION` (0) vs `PROJECT_CONSTRAINT` (1).
   - Embedded explicit `ConstraintPayload` into `PROJECT_CONSTRAINT` binary serialization containing:
     - `ConstraintEffect effect` (`BLOCK`, `WARN`)
     - `ConstraintStatus status` (`ACTIVE`, `INACTIVE`, `SUPERSEDED`)
     - `List<String> scopes` (bounded, normalized, unique)
     - `List<UUID> supersedes` (superseded constraint record IDs)
   - Invalidated unreleased development `SDR1` records with explicit version errors (`IOException("unsupported decision record version: expected SDR2")`).

3. **Observable Adapter Outcomes (`ClaudeCodeHookAdapter`)**:
   - Differentiates `ALLOWED`, `WARNING`, `BLOCKED`, `UNSUPPORTED`, and `INVALID_INPUT` outcomes.
   - Emits structured stderr diagnostics for `WARNING` and `UNSUPPORTED` tool events while allowing tool execution.
   - Centralized supported tool aliases (`Edit`, `Write`, `str_replace_editor`, `write_file`, `file_edit`, `file_write`, `NotebookEdit`).

4. **Baseline vs. Synesis Guardrail Experiment (`scripts/run-synesis-guardrail-experiment.ps1`)**:
   - Automated baseline vs. Synesis experiment runner comparing file edit attempts with and without Synesis hook adapter.
   - Measures and outputs machine-readable metrics:
     - `BASELINE_PROTECTED_FILE_CHANGED=true`
     - `SYNESIS_PROTECTED_FILE_CHANGED=false`
     - `SYNESIS_ACTION_RESULT=BLOCKED`
     - `SYNESIS_GUARDRAIL_LATENCY_MS=176`

## Consequences

- **Pros**: Clean, typed, cryptographic constraint model with zero legacy debt, explicit binary framing, and proven pre-action guardrail enforcement.
- **Cons**: Unreleased development `SDR1` record stores are unsupported and must be recreated using SDR2.
