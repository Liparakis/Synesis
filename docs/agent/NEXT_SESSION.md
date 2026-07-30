# Next Session

- Active task: none; SYN-029 through SYN-033, SYN-028, SYN-027, and SYN-026 are complete.
- Repository branch: master
- Last checkpoint: CP-0377; SYN-029 through SYN-033 are closed with native launcher/install-time health, creation-aware reads, supported real Codex/Claude flows through integration, stable at-least-once lane actions, exact-caller inbox acknowledgement, automatic conflict inboxes, isolated workgroups, immutable snapshots, claim/epoch/contract/base checks, serialized incremental integration, and real Codex process-loss recovery through RECOVERY_HELD and a new isolated continuation lane. Antigravity native transport is verified, while model-driven noninteractive MCP invocation remains explicitly unsupported/unverified. The sequential 51-task Gradle check, strict Javadocs, MCP/workspace/CLI tests, repository hygiene, deferred validation, and bootstrap Go tests/vet all pass.
- Last passing command: `./gradlew.bat check --no-daemon --max-workers=1 --dependency-verification=strict`; bootstrap `go test -count=1 ./...`; bootstrap `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: preserve the completed roadmap and provider-boundary
  evidence; no implementation task is active.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `SL-D-037` is implemented at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred. Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md` has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
