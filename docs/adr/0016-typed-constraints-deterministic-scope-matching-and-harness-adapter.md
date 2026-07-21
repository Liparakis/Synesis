# 16. Typed Project Constraints, Scope Matcher, and Claude Code Harness Adapter

- Status: Accepted
- Date: 2026-07-22
- Deciders: Core Architecture Team

## Context

Synesis prevents independently running coding agents from acting on stale or conflicting project constraints.

Previously, SYN-005 introduced `check-action` as an initial prototype using title prefix conventions (`CONSTRAINT:`) and basic string matching. To establish an enforceable security guardrail for real coding-agent runtimes, Synesis requires:
1. Typed constraint records using structured evidence schemas while preserving the immutable `SDR1` binary binary compatibility.
2. Deterministic, portable path normalization and scope matching.
3. Portable repository Gradle build defaults.
4. An enforceable pre-action adapter connecting a coding harness (Claude Code pre-tool hook) to Synesis guardrails before files are mutated.

## Decision

1. **Typed Constraint Schema (`ProjectConstraint`)**:
   - Encodes typed constraints using structured `DecisionEvidence` (`kind="constraint:v1"`, `reference="effect=<BLOCK|WARN>|scope=<pattern>"`).
   - Models constraint status (`ACTIVE`/`INACTIVE`/`SUPERSEDED`), effect (`BLOCK` vs `WARN`), target scopes, and origin source (`TYPED` vs `LEGACY_INFERRED`).
   - Legacy records starting with `CONSTRAINT:` remain fully readable as `LEGACY_INFERRED` fallback constraints without invalidating stored binary hashes or signature verification.

2. **Deterministic Scope Matcher (`ScopeMatcher`)**:
   - Normalizes path separators to canonical Unix `/`.
   - Rejects absolute paths (`/src/...`, `C:\...`) and directory traversal (`..`) as invalid input (exit 2).
   - Supports exact file matching, segment wildcards (`*`), and multi-segment glob wildcards (`**`).

3. **Portable Gradle Defaults**:
   - Default JVM heap capped conservatively at 2 GB (`-Xmx2g -XX:+UseG1GC`).
   - Test parallelism bounded safely at `coerceIn(1, 4)` forks, with optional override via `-PsynesisTestForks=<N>`.

4. **Claude Code Pre-Tool Hook Adapter (`ClaudeCodeHookAdapter`)**:
   - Subcommand `synesis-workspace --profile <dir> hook claude-code` processes JSON pre-tool-execution events from `stdin`.
   - Extracts target file paths for file-editing tools (`Edit`, `Write`, `str_replace_editor`, `write_file`).
   - Returns Claude Code hook JSON (`{"decision": "deny", "reason": "..."}` or `{"decision": "allow"}`) to block protected edits before execution.
   - Non-file tools (e.g. `Bash`) pass through with documented limitation warnings (`UNSUPPORTED`).
   - Target files are never modified by the adapter.

## Consequences

- **Pros**: Intercepts and blocks unauthorized coding agent file modifications before disk mutation, providing a clear re-planning hint to the agent harness.
- **Cons**: Pre-action enforcement depends on harness integration points calling the hook adapter before executing file tools.
