# SYN-037 real-Codex acceptance — 2026-08-03

The acceptance used a fresh temporary Git repository, the compiled stdio
Synesis MCP server, and a real `codex exec` process configured with only that
MCP server. Codex used the server-issued flow and did not use shell, direct
filesystem mutation, manual merge, or direct control-checkout edits.

Fixture:

`C:\Users\Liparakis\AppData\Local\Temp\syn037-real-codex-final-296df905c3684ae7ba7ad020babc3703`

Capture:

`C:\Users\Liparakis\AppData\Local\Temp\syn037-codex-final-events-d7010b7c61234801b4b6bbdfd3ab1e29.jsonl`

## Authority and claim

- Participant: `agt_4f3186fb-0313-3410-ba2b-5b5bfb3c88c7`
- Session: `session-935acfb9-2c5e-4c48-9fa3-b67ffcad05de`
- Lane/intent: `c5404e85-cbbd-3ad0-878d-25e438939a32`
- Claim epoch: `1`
- Claim: exactly `src/task_tracker.txt`
- Completion action: server-issued `finish_lane`, permitted after successful validation evidence

## Direct command evidence

1. `git status --short` completed with exit code `0`; stdout was ` M src/task_tracker.txt\n`.
   `stdoutBytesRead=24`, `stdoutBytesRetained=24`, `stdoutTruncated=false`;
   `stderrBytesRead=0`, `stderrBytesRetained=0`, `stderrTruncated=false`.
2. `synesis-deliberately-nonexistent-037` returned
   `command_executable_not_found` with no process start. Both streams had
   `BytesRead=0`, `BytesRetained=0`, and `Truncated=false`.
3. The configured direct PowerShell argv completed with exit code `0`.
   Both streams had `BytesRead=0`, `BytesRetained=0`, and `Truncated=false`.

All three results exposed the complete read/retained/truncation metadata. The
nonexistent executable result did not prevent the later valid validation or
completion.

## Completion, snapshot, and integration

- Pre-publication validation: `completed`, exit `0`, empty complete streams.
- Snapshot ID: `snap_76f58a1737e488a2a248413dd95d3a3e`.
- Snapshot changed-path manifest: exactly `src/task_tracker.txt`.
- No `.synesis`, `.codex`, provider-local, coordination, hook, identity,
  evidence, lock, or runtime path entered the snapshot.
- Integration attempt: `att_7c31901698014874`, completed successfully.
- Integration validation: `completed`, exit `0`, empty complete streams.
- Final lane state: `COMPLETED`; integration state: `integrated`.
- Final control `HEAD`: `0237b31e1d1423861c6ce7e4fbe99b1027315189`.
- Final `src/task_tracker.txt`: `implemented`.
- Final `git status --short`: empty.

The final clean-status check was read-only observation after Codex completed;
no cleanup, merge, republication, or direct control-checkout edit was used.
