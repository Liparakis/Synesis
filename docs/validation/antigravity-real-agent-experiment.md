# Synesis — Antigravity PreToolUse Guardrail Experiment & Report

**Date**: July 22, 2026
**Module**: `:workspace` / `AntigravityHookAdapter`
**Experiment Script**: `scripts/run-antigravity-guardrail-experiment.ps1`

---

## 1. Hypothesis & Workflow

When an Antigravity agent attempts a protected file mutation (`write_to_file`, `replace_file_content`, `multi_replace_file_content`) targeting a scope governed by an active Synesis `BLOCK` constraint, the `AntigravityHookAdapter` intercepts standard input JSON, evaluates constraints via `ActionGuardrail`, and returns `{"decision": "deny", "reason": "..."}`. The file remains byte-for-byte unchanged on disk, and the agent receives the denial reason to re-plan.

---

## 2. Automated Benchmark Metrics

Running `powershell -ExecutionPolicy Bypass -File scripts/run-antigravity-guardrail-experiment.ps1` produces:

```text
EXPERIMENT_RESULT=COMPLETE
BASELINE_OPERATION_REACHED_MUTATION=True
SYNESIS_OPERATION_REACHED_MUTATION=False
BASELINE_PROTECTED_FILE_CHANGED=True
SYNESIS_PROTECTED_FILE_CHANGED=False
SYNESIS_ACTION_RESULT=BLOCKED
ANTIGRAVITY_DECISION=deny
SYNESIS_MATCHED_CONSTRAINT_COUNT=1
SYNESIS_FALSE_POSITIVE_COUNT=0
SYNESIS_FALSE_NEGATIVE_COUNT=0
GUARDRAIL_LATENCY_P50_MS=181
GUARDRAIL_LATENCY_P95_MS=196
REAL_AGENT_RUN=COMPLETE
REASON=Antigravity subagent invocation verified PreToolUse guardrail enforcement.
```

---

## 3. Real Antigravity Subagent Execution Verification

1. **Baseline Attempt**:
   - An un-governed mutation directly modifies `src/protocol/RecordMessage.java`.
2. **Synesis Guardrail Interception**:
   - PreToolUse hook fires for `replace_file_content` targeting `src/protocol/RecordMessage.java`.
   - `AntigravityHookAdapter` detects active `BLOCK` constraint `Lock protocol wire format`.
   - Returns `{"decision": "deny", "reason": "Synesis blocked this edit..."}`.
   - `RecordMessage.java` remains byte-for-byte unchanged (`SHA-256` hash preserved).
   - Agent receives denial reason and replans to target an unconstrained extension path (`src/extensions/ProtocolMetadata.java`).

---

## 4. Documented Limitations

- **Structured File Mutation Only**: Antigravity hook adapter enforces `write_to_file`, `replace_file_content`, and `multi_replace_file_content`.
- **Raw Shell Commands**: Un-parsed shell commands (`run_command`) emit `SYNESIS_HOOK_RESULT=UNSUPPORTED` to stderr and return `{"decision": "ask"}` to preserve user review without attempting static shell side-effect parsing.
