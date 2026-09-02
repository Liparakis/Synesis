# ADR-0050: Require Git Before User-Facing Project Initialization

Status: Accepted

Date: 2026-08-31

Task: SYN-044

## Decision

The user-facing `synesis init` command requires the target directory to
already be a usable Git repository. It fails with `GIT_REQUIRED` before
creating `.synesis` state when Git metadata is absent or unreadable.

An unborn Git repository created by `git init` remains valid. Synesis may use
its existing managed-baseline transaction to create the initial baseline
commit. Synesis does not silently run `git init`, because that is a repository
mutation the user should explicitly choose.

The lower-level project service retains its internal initialization overloads
for deterministic migration and fixture work. The CLI is the user-facing
boundary and applies the fail-fast precondition before calling initialization.

Generated `AGENTS.md` guidance names the matching provider integration for the
responding harness: Claude Code uses `synesis provider install claude`, and
Codex uses `synesis provider install codex`. Provider installation configures
Synesis's integration; it does not install the external provider executable.

## Consequences

- A successful user-facing `synesis init` now implies a Git-backed project
  boundary before provider/session admission is attempted.
- Users must install Git repository state explicitly, but they retain the
  existing convenient first-baseline behavior after `git init`.
- Existing internal service callers that intentionally construct non-Git
  project fixtures are not silently changed into Git-mutating callers.
- Provider setup remains harness-specific and cannot be satisfied by
  installing the other provider's integration.
