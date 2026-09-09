# SYN-009E installed maximum runtime-integrity gate

Date: 2026-09-09

## Change

The existing versioned bootstrap boundary now carries the selected
`PROTECTION_PROFILE` into `current.json`. A stable launcher only invokes the
full installed `doctor --install-dir` verification when the active payload is
marked `maximum-release` (or when a legacy pointer is paired with that marker).
Developer and `protection-lite` launches keep their existing fast path.

The doctor path reuses the bootstrap payload manifest and immutable-version
verification. It does not scan mutable project or provider state, modify the
host, terminate unrelated processes, or create a second trust system.

## Verification

- `go test . -run '^TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate$' -count=1` — PASS
- `go test ./...` in `bootstrap` — PASS after the fixture helper isolated
  `HOME`/`USERPROFILE` from the developer's real provider configuration.
- `git diff --check -- bootstrap/main.go bootstrap/main_test.go` — PASS
- The focused test confirmed that a newly installed maximum marker is recorded
  in the active pointer and that the generated stable launcher contains the
  doctor gate.
- A current bootstrap binary installed the existing lite bundle after a
  temporary marker-only profile substitution with `--skip-path-update`; the
  installed stable launcher started successfully, refused a modified payload
  file with `ERROR=installed pointer invalid`, and started successfully after
  the file was restored and mutable `Link` state was added.

## Extracted maximum-harness follow-up

The current extracted-candidate harness was exercised against a synthetic
archive made from the existing protection-lite bundle with only its profile
marker changed to `maximum-release`. This is deliberately not commercial
maximum evidence.

The run recorded these installed checks before the remaining host gates:

- CLI version/help, UI metadata, native installer, and native MCP launcher —
  `PASS`;
- installed stable-launcher refusal after editing an immutable payload file —
  `PASS`;
- refusal after editing the packaged `web-ui/assets/index-1xoW5_V1.js` entry
  inside its JAR — `PASS`;
- mutable `Link` state outside the immutable payload — `PASS`;
- disposable project init, Claude lifecycle, Codex installation, and doctor —
  `PASS`; and
- MCP initialize/tools-list wire framing — `PASS`.

On the default host environment, the UI/control-plane smoke was recorded as
`BLOCKED_ENVIRONMENT` because Java could not establish its loopback wakeup
connection, and MCP session admission was recorded as
`BLOCKED_RUNTIME` at `retry_required/workspace_not_ready`. The JSON result was
`PARTIAL_ACCEPTANCE_BLOCKED` and the script exited `2`.

A second run supplied only process-local compatibility overrides for the
known host boundary (`-JdkUnixDomainTempDirectory C:\t\synesis-loopback-probe`
and `-LocalAppDataOverride C:\t\synesis-maximum-harness-runtime-override-15`).
The harness also isolated `HOME`, `USERPROFILE`, `APPDATA`, and
`LOCALAPPDATA` for every captured shipped process, so provider installation
could not mutate the developer's real configuration. That installed synthetic
candidate reached `COORDINATION_SERVE_READY` and MCP `ensure_session=ready`;
all harness checks passed with result
`PASS_WITH_EXPLICIT_OPEN_GATES`. The overrides are recorded as
`PROCESS_LOCAL_ONLY; NOT_SHIPPED_LAUNCHER_CONFIGURATION` and do not change the
shipped launcher or establish commercial Seven Rings evidence.

## Evidence boundary

This is implementation and generated-launcher contract evidence. It is not a
commercial maximum-artifact result: the runtime probe used the existing lite
bundle with a temporary marker only. A real maximum archive, installed
licensed protector output, and a disposable install run are still required to
prove the full commercial release boundary, protected loader behavior, and
mutable project/provider-state acceptance. The maximum profile therefore
remains `PARTIAL` and the commercial transformation rings remain blocked.
