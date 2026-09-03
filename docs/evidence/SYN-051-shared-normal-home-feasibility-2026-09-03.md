# SYN-051 shared normal Codex home feasibility — 2026-09-03

## Classification

**FAIL for the full shared-home managed-continuity hypothesis.**

The normal Codex home can be reused operationally by concurrent dedicated App
Server processes. Codex authentication remains provider-owned, launch-time
--config overrides can select a process-local MCP environment variable, and
short concurrent A/B real model turns completed. Those are useful narrow
results.

The safety hypothesis fails at the provider thread-store boundary. While A1
was live and had Thread A loaded, B1 could not address or resume Thread A. Once
A1/A2 had stopped, however, a fresh B3 process using the same normal home
resumed the persisted Thread A and called the disposable MCP boundary with a
fresh B3 proof. The provider did not enforce worker ownership of the persisted
thread. A Synesis proof can therefore be process-private without being a
provider-thread authority boundary.

This is not a same-user filesystem-secrecy claim. It is ordinary provider
lifecycle behavior that permits a different managed worker to recover another
worker's provider thread after the original process is gone. The shared-home
architecture is consequently not safe for SYN-051 worker isolation.

The current UNSAFE_FILE_AUTH hard stop remains unchanged. No production
continuity code or authentication policy was modified.

## Scope and explicit boundary

This was a disposable provider/runtime feasibility investigation only. It did
not run full task-tracker acceptance, restart a historical fixture, initialize
or mutate .synesis state, invoke Synesis coordination/MCP tools, copy a
worktree, read provider credential contents, or push.

The turn-level boundary “no Synesis in the Synesis source” was honored as
direct repository documentation/evidence work only: the runtime probe used a
disposable boundary-observer MCP child rather than the current Synesis MCP
server. Therefore the current production managed-attachment admission path was
not bypassed or claimed as accepted. The provider-side failure is sufficient
to reject the full hypothesis; a real Synesis attachment probe remains an
explicitly unperformed item.

## Security boundary

The accepted local threat boundary excludes a fully compromised same-user OS
account. This experiment therefore did not attempt to prove that a malicious
same-user process cannot inspect another process's provider files or
environment.

It did require normal managed workers to avoid accidental authority confusion.
The intended separation was:

dedicated App Server process + exact provider thread + random proof +
managed generation + existing Synesis binding

The result is mixed:

- process-private proof delivery worked;
- exact A/B provider threads were distinct while each was loaded;
- the provider did not make the proof a property of the persisted thread;
- a fresh process could recover another worker's persisted thread after owner
  shutdown.

## Starting state and provider

- Repository: C:\Users\Liparakis\Desktop\Synesis
- Starting HEAD: b84d041cd066a2f6d9e8f13b6ff093fac5a3a869
- Branch: master
- Starting Git state: clean
- Active task: SYN-051
- Provider: codex-cli 0.145.0
- Executable:
  C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe
- Normal provider home: C:\Users\Liparakis\.codex
- Test working directory: C:\Users\Liparakis\Desktop\SSMP, already trusted
- Java used by the disposable launcher: JDK 25 short path
- No provider environment credential variables were present for the probe.

Provider source/documentation anchors inspected:

- Codex core configuration:
  https://github.com/openai/codex/blob/main/codex-rs/core/src/config/mod.rs
- Codex App Server README for rust-v0.145.0:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/app-server/README.md
- Codex authentication storage:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/login/src/auth/storage.rs
- Codex Windows/local secrets:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/secrets/src/local.rs
- Codex MCP runtime:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/codex-mcp/src/runtime.rs
- Codex stdio MCP launcher:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/rmcp-client/src/stdio_server_launcher.rs
- Codex App Server thread lifecycle:
  https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/app-server/src/request_processors/thread_lifecycle.rs

The normal home was inspected only through non-secret metadata and redacted
provider diagnostics. The auth payload itself was not read.

## Experiment topology

The successful run used:

- normal shared CODEX_HOME;
- dedicated App Server A1 and B1 processes;
- exact non-ephemeral Thread A and Thread B;
- fresh random proof per process;
- per-launch App Server --config overrides;
- a disposable Java wrapper and disposable boundary-observer MCP child;
- only the selected SYNESIS_ATTACH_PROOF variable forwarded to the child.

Initial identities:

- Thread A:
  01a06842-9b59-7573-96f2-b876839017d9
- Thread B:
  01a06842-9bdf-73c3-a3d0-2c9c55ad112b
- A1 App Server PID: 21648
- B1 App Server PID: 13636
- A1 MCP child PID: 2108
- B1 MCP child PID: 23864
- A2 MCP child PID: 24940
- B3 App Server PID: 24900
- B3 MCP child PID: 24300

The exact PIDs are diagnostic evidence, not durable worker identity.

## Required 49-field report

| # | Field | Result |
|---:|---|---|
| 1 | Starting HEAD | b84d041cd066a2f6d9e8f13b6ff093fac5a3a869 |
| 2 | Codex version | codex-cli 0.145.0 |
| 3 | Normal CODEX_HOME | C:\Users\Liparakis\.codex |
| 4 | Normal auth mode | File-backed auth.json; redacted Doctor reported File; codex login status reported logged in with ChatGPT |
| 5 | Synesis read credentials | No. The auth payload was never read by Synesis or the disposable probe |
| 6 | Synesis copied credentials | No. No auth file, token, secret, or provider credential was copied, moved, migrated, or placed in environment/config |
| 7 | Shared-home concurrent App Server result | Operational PASS: A1 and B1 initialized concurrently, stayed live, created distinct threads, and completed their own real model turns |
| 8 | Shared-home thread-store concurrency result | Operationally stable in this bounded run: own reads, MCP calls, concurrent turns, and survivor operation passed; no SQLite/lock/corruption error was observed. It failed as a worker-ownership boundary because B3 later resumed A's persisted thread |
| 9 | Per-process MCP override mechanism | codex app-server --stdio with launch-local --config mcp_servers.synesis.command=..., args=..., and env_vars=["SYNESIS_ATTACH_PROOF"]; no per-worker static config rewrite was used in the successful run |
| 10 | Global config modified | Yes, transiently by the first disposable attempt: Codex auto-added two probe workspace trust entries. Those exact entries were removed, and the file was verified byte-for-byte at the pre-probe SHA-256 9F4453712E9EDAE0C4A5AB662946F5B078F378812C459E641FA85A4A1F6BFDEB and length 29091 bytes at the cleanup point. A later read found length 28747 and SHA-256 62EE90E1828B270C552DCB1E8BA79C5BCA84E6E7EB6611A5920AE69F8EC95B4A with no probe references; no intervening repository command wrote it, attribution is unknown, and it was not overwritten blindly. The successful corrected run itself made no further mutation |
| 11 | Worker A proof delivery | PASS at disposable boundary: A1 child saw only its own proof digest a98c892c86ab2a96cfe36308b73bc0fae1c9fd6698e86a182b3b6a57bc07b23e; B1 digest was absent; raw proof was absent |
| 12 | Worker B proof delivery | PASS at disposable boundary: B1 child saw only its own proof digest fa5fdb4a334b0115acb6eb9dd5fb9ccd7340da69fcfd6cf94c4e3cd4719d7001; A1 digest was absent; raw proof was absent |
| 13 | A/B cross-proof result | Current Synesis cross-proof admission was not run under the explicit no-Synesis boundary. Provider-level evidence is negative for isolation: B1 could not address A while A was loaded, but B3 with a fresh proof resumed A's persisted thread after A stopped and reached the disposable MCP boundary |
| 14 | Thread A | 01a06842-9b59-7573-96f2-b876839017d9 |
| 15 | Thread B | 01a06842-9bdf-73c3-a3d0-2c9c55ad112b |
| 16 | App Server A/B PIDs | A1 21648; B1 13636 |
| 17 | MCP child A/B PIDs | A1 child 2108; B1 child 23864 |
| 18 | Real authenticated model turn A | PASS; completed with marker SYN051_A_TURN_OK, no JSON-RPC response error, 22 events |
| 19 | Real authenticated model turn B | PASS; completed with marker SYN051_B_TURN_OK, no JSON-RPC response error, 22 events |
| 20 | Proof model visibility | No raw proof appeared in model-turn events/results or the disposable MCP response; the prompt did not contain the proof. This is bounded observer evidence, not an adversarial model guarantee |
| 21 | Proof log leakage | PASS for captured artifacts: raw-proof scan found 0 hits in the disposable root; logs/reports contained digests/presence flags only; command-line reports redacted proof values |
| 22 | Provider credential leakage | PASS for the bounded audit: no credential content was read or emitted; auth metadata stayed unchanged; no provider credential env variable was used |
| 23 | Restart A result | A1 stopped cleanly; B1 remained live and usable; A2 later started from the same normal home |
| 24 | Exact Thread A resume result | PASS: A2 resumed the exact Thread A after A1's loaded state was gone |
| 25 | Fresh proof A2 result | PASS: A2 child saw fresh digest 8caab0947955bca3a1b8ae9ca4af316516c9019a966c628d495b34e6bdd7bb5d; A1 digest was absent |
| 26 | Stale proof A1 presence | A1 raw proof was not persisted. A2 did not need it; A1 digest was absent from the A2 child log. The old digest remains only in the old diagnostic A1 log |
| 27 | Model turn after A resume | PASS; A2 completed marker SYN051_A2_TURN_OK with no JSON-RPC response error |
| 28 | Worker B unaffected | PASS: B1 stayed live, read its own thread, called its own MCP child, and remained unaffected during A2 |
| 29 | Shared SQLite/thread-store errors | No SQLite, lock, rollout-corruption, or session-store error appeared in the bounded stderr summaries. A1/B1 also emitted pre-existing models-cache and hook-format warnings. A2's truncated auxiliary stderr summary flagged an unspecified 401 string even though resume and model turn succeeded; this remains a diagnostic caveat, not a clean-auth claim |
| 30 | Config race result | No concurrent global rewrite was attempted. The first run's automatic trust-entry mutation demonstrates that the shared global config is not a safe per-worker mutable layer |
| 31 | Process-local override viability | PASS: Codex accepted launch-local dotted overrides and forwarded the selected variable to each dedicated MCP child |
| 32 | Whether CODEX_HOME is needed as worker identity | No. It is provider storage. Logical identity must be the durable Synesis binding plus exact provider thread, proof, and generation. The current production dedicated-home design remains necessary for provider-side isolation under this evidence |
| 33 | Current SYN-051 assumptions invalidated | The assumption that process-private proofs make a shared provider store safe is invalid. Shared normal auth removes the isolated-home credential blocker operationally, but does not create provider thread ownership or proof binding |
| 34 | UNSAFE_FILE_AUTH semantic assessment | Keep it unchanged. It remains correct for the current isolated/dedicated-home implementation, where file-backed auth would otherwise require copying or sharing provider credential state. This experiment is not evidence to weaken it for shared mode |
| 35 | Minimum production policy change required | No narrow safe auth-policy toggle is established. A future shared-home mode would require provider-enforced per-worker/thread ownership or an equivalent single-writer/state-partitioned broker plus a real Synesis binding probe. That is outside this pass |
| 36 | Full process recreation result | A2 and B2 independently recreated App Server processes using the same normal home and exactly resumed A/B threads with fresh proofs and successful model turns. Full host H1/H2 reboot was not performed. B3 additionally resumed A after all A processes had stopped |
| 37 | Raw proof persistence requirement | None. Fresh proof per process; raw value existed only in trusted process environment/memory. Durable production design remains hash-only |
| 38 | Architecture A versus B comparison | A isolated home: strongest provider/runtime separation and PASS-A feasibility, but current safe auth gate fails. B shared home: normal auth, override, concurrency, and basic resume pass, but provider-side cross-worker thread ownership fails. A remains the conservative architecture; B is rejected |
| 39 | Threat-boundary assessment | Same-user malicious inspection was out of scope as agreed. The observed failure is benign normal-worker authority confusion after owner shutdown, so it is inside the required safety boundary and is disqualifying |
| 40 | Final classification | FAIL for the complete shared normal home/auth/thread store plus process/thread/proof/generation hypothesis. Narrow process-local override and operational concurrency are PASS results only |
| 41 | SYN-051 managed acceptance unblocked in principle | No. The safe isolated-auth gate remains blocked, and shared-home reuse does not supply the missing worker-ownership guarantee |
| 42 | Remaining unknowns | Current Synesis managed MCP admission/cross-proof behavior was not run; no long-duration stress test, adversarial same-user test, machine reboot, or full task-tracker acceptance; A2's truncated 401 diagnostic needs a fresh isolated diagnosis if pursued; no model-initiated MCP call was required; the later unattributed normal-config drift was not reconstructed |
| 43 | Planning/evidence changes | This evidence record plus SYN-051 CURRENT.md, STATE.md, NEXT_SESSION.md, SESSION_LOG.md, and ADR-0057 state the rejection. No production source changed |
| 44 | Prototype artifacts | Report: C:\Users\Liparakis\AppData\Local\Temp\syn051-shared-home-20260903-05\shared-home-report.txt; logs under its logs directory; B3 script CrossResumeAfterStop.ps1; disposable Java sources/wrapper under the syn051-shared-home-20260903-01 temp root; no raw proof persisted |
| 45 | Validation results | Provider probe completed; deferred validator PASS; fixture validator PASS; direct catalog check PASS with count 10 and the existing ten raw names; git diff --check PASS. The first targeted Gradle invocation failed on a spaced Java-home override; the corrected invocation then failed before test execution with Unable to establish loopback connection. No full task-tracker acceptance ran |
| 46 | Commits created | This evidence and SYN-051 state update are contained in the final documentation-only commit; no push |
| 47 | Final HEAD | The commit containing this record; the exact SHA is returned in the completion report. Starting HEAD remains the value in field 1 |
| 48 | Final Git status | Clean master after the documentation-only commit; no production source changes. The normal Codex config is outside this Git tree and had no probe references at final read |
| 49 | Exact next action | Preserve UNSAFE_FILE_AUTH and do not run managed acceptance. If shared-home continuity is reconsidered, first obtain an explicit provider-enforced worker/thread ownership contract and authorization for a real fresh Synesis attachment probe; otherwise continue only with the dedicated retained-home design |

## Provider and Synesis conclusions

The experiment separates two questions that must not be conflated:

1. Can normal Codex processes use the user's existing provider-owned auth and
   thread store concurrently? In this bounded run, yes operationally.
2. Does that shared provider store preserve Synesis worker authority when
   proofs are process-private? No. Provider thread loading is not an ownership
   fence, and a fresh process can resume another worker's persisted thread.

A per-process SYNESIS_ATTACH_PROOF is therefore a useful carrier but not a
provider-native assertion that the exact process owns the exact persisted
thread. The current Synesis binding/generation model can only be authoritative
if the provider cannot independently rebind the same persisted thread to a
different managed worker. The shared-home experiment does not satisfy that
condition.

No production auth-policy change is authorized by this evidence. Keep the
existing dedicated-home architecture and its safe-authentication hard stop
until a provider-supported cross-home mechanism or a provider-enforced
worker/thread ownership contract is available.

## Reproduction and integrity notes

- The successful A/B run used the already trusted SSMP working directory to
  prevent Codex from adding new probe trust entries.
- An earlier disposable run did add two trust entries to the normal global
  config. They were identified by exact path, removed, and followed by
  byte-level verification against the pre-probe hash. This is part of the
  evidence, not suppressed.
- A later read found a different normal-config length/hash with no proof,
  probe path, or probe marker. No intervening repository command in this
  investigation wrote that file, and the unknown difference was not
  overwritten because doing so could delete unrelated user configuration.
- No process from the disposable roots remained after the controlled shutdown
  and B3 recreation check.
- No historical .synesis data, Synesis project initialization, provider
  credential payload, or worktree was changed.
