# SYN-050 stock Codex isolated-runtime feasibility — 2026-09-03

## Scope

This disposable spike tests whether stock `codex-cli 0.145.0` can provide a
protected per-worker MCP attachment through one isolated `CODEX_HOME` and
App Server process per logical worker. It does not implement production
continuity, patch Codex, change Synesis authority, reopen SYN-049, or mutate
historical fixtures or durable Synesis state.

The earlier child-launch investigation remains valid for its narrower claim:
stock Codex does not provide dynamic thread/proof/extra-handle input at the
stdio launcher boundary. This spike tests the separate hypothesis that a
dedicated runtime can use static, worker-specific MCP configuration instead.

## Source and runtime

- Synesis source starting HEAD: `f17a4628ee86ad4dea5bc6ea23b393b7465e2847`.
- Runtime: `codex-cli 0.145.0` at
  `C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`.
- Provider SHA-256:
  `83751F15CB6A0A7B97DF67752C001E3FE1C20E18FFBFEC3FF63567296205EB6C`.
- Supported home override observed locally: `CODEX_HOME` environment variable.
- Supported profile mechanism observed in CLI help: `--profile`, layering
  `$CODEX_HOME/<name>.config.toml` on the base configuration.
- Upstream configuration source describes the configuration stack, including
  `${CODEX_HOME}/config.toml`, profiles, cwd/repository configuration, and
  runtime overrides. The local runtime was started with isolated homes and
  disposable workspaces, so no project-global Codex configuration was used.

## Disposable experiment

Run root:

`C:\Users\Liparakis\AppData\Local\Temp\syn050-isolated-runtime-20260903-05`

The disposable Java probe compiled with the local JDK and launched stock:

`codex app-server --stdio`

Each worker had a separate home, workspace, config, App Server, wrapper, and
MCP child. Raw markers were generated only in memory and are not recorded
here; the report contains SHA-256 digests only.

### Static MCP environment

Worker A configuration contained a unique static `SYN050_ATTACHMENT_PROOF`
value and Worker B contained a different one. Both children received their
own digest and not the other worker's digest:

- A1 digest:
  `0a738e720a130eae9216cb89abc2ac03ec536678e2754f4bcb1b542ddfe0c2d5`.
- B1 digest:
  `2c662fd75b0130f96648b10d17a0db84b029915d5d3db27ebbb864e3e49b7d1e`.
- A App Server PID: `21396`; MCP child PID: `16232`.
- B App Server PID: `18284`; MCP child PID: `9796`.
- Thread A:
  `01a06778-84f4-7591-a41d-81485435fb00`.
- Thread B:
  `01a06778-859f-7982-9a26-672ab9e929c3`.
- A/B App Server processes and thread IDs were distinct and concurrent.
- A's thread ID sent to B's App Server was rejected with `-32600` / `thread
  not found`.

The static proof was present in the worker-specific `config.toml` files in
this disposable experiment. The files used inherited Windows ACLs rather
than owner-only ACLs, so this is not a kernel-isolated secret. That is within
the accepted threat boundary only for a non-compromised local OS account.

### Selected parent-environment forwarding

Each worker's isolated config instead contained:

`env_vars = ["SYN050_ATTACHMENT_PROOF"]`

The parent App Server process received its unique value only in its own
process environment. Stock Codex forwarded the allow-listed variable to the
MCP child even though unrelated parent environment values were cleared:

- env-A digest:
  `f5b7166ed1370446e91ad46ac6a95cdd29f231ebd3b489f6b7c2c35f1588eec6`.
- env-B digest:
  `97639ae04f5c398aeac261ca9ca6a312fd9cec07c273f03cde6116cd93a18f42`.
- env-A App Server PID: `18640`; MCP child PID: `8856`.
- env-B App Server PID: `21928`; MCP child PID: `16520`.
- Each child received only its own digest; cross-worker digest checks passed.
- The env-A/env-B config files contained no raw proof value.

This is process-scoped delivery, not a shared-process thread credential. The
dedicated-process topology is what makes the selected environment meaningful
for one logical worker.

## Persistence and restart

The A1 worker started a non-ephemeral exact thread and a disposable no-op
turn. The no-op turn was accepted and then interrupted during controlled
shutdown; it did not edit the workspace.

Worker A2 reused the same isolated home, loaded a replacement config with a
new proof, and resumed the exact A thread. Results:

- same-home exact resume: PASS;
- A2 child received digest
  `ac23d3540f658ef515cdbc8e87068c7d6381d57200b61ff2c840db464b7f5cfc`;
- A1 digest was absent from the A2 response and child log;
- A1→A2 proof rotation: PASS;
- deleting `config.toml` after the child loaded it did not change the live
  child's configured value;
- restarting App Server without restoring `config.toml` resumed the thread but
  could not call the configured MCP server, proving restart requires config
  recreation.

The isolated home contained `sessions/YYYY/MM/DD/rollout-...jsonl` and
`state_5.sqlite`; a newly created isolated home with its own MCP config could
not resume Thread A and returned `no rollout found`. Therefore thread history
is tied to the runtime home in this stock configuration. SQLite placement can
be configured separately through `CODEX_SQLITE_HOME`, but this spike did not
claim that rollout/thread storage is independently relocatable.

The default file-based auth location is under the Codex home (`auth.json`),
with Codex also supporting keyring/ephemeral modes. No auth file was copied or
created in the disposable homes, and the user's real auth state was not
modified. App Server/thread/MCP startup and the no-op turn request were
exercised; authenticated model completion was not required or claimed.

## Secret and boundary checks

- No raw marker was emitted by the probe, report, wrapper, MCP response, or
  logs; only digests and presence flags were recorded.
- A recursive scan found zero raw-marker hits outside the expected static
  proof config files.
- No proof was placed on a command line, in project files, thread metadata,
  AGENTS guidance, or generated instructions.
- No disposable probe processes remained after shutdown.
- Direct static `env` proves worker-specific config delivery but stores the
  proof in plaintext on disk.
- `env_vars` avoids plaintext proof in `config.toml`, but the value exists in
  the managed App Server and child process environments. Same-user processes
  may inspect those environments; this does not claim protection from a
  compromised local OS account.

## Classification

**PASS-A — stock Codex isolated runtime solves the conservative v1 delivery
experiment.** The required evidence is present for isolated A/B configured
delivery, concurrent process isolation, exact thread start/resume, proof
rotation, no raw proof in model-visible probe output, and no global static
configuration mutation. The recommended carrier for a future implementation
is allow-listed `env_vars` supplied to a dedicated managed App Server process,
with private runtime homes and fresh proof material per generation.

This is a feasibility result only. No production continuity code is changed
or authorized by this record. The earlier dynamic child-launch `FAIL` remains
the reason shared-process or provider-thread dynamic injection is not claimed.

## Reproducibility

- Probe source:
  `C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\IsolatedRuntimeInvestigation.java`.
- Wrapper source: `C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\BoundaryWrapper.java`.
- MCP source: `C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\BoundaryMcp.java`.
- Report: `C:\Users\Liparakis\AppData\Local\Temp\syn050-isolated-runtime-20260903-05\isolated-runtime-report.txt`.
- Report SHA-256:
  `AF8430AE9850A3C61540FC15E469D2A64663E58C0AD20A4419329A8E69DD11A3`.
- `ISOLATED_RUNTIME_INVESTIGATION=PASS`.
