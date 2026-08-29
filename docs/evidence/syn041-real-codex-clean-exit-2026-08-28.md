# SYN-041 real Codex clean-exit provider lifecycle validation

Date: 2026-08-28  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`  
Branch: `master`

## Authentication

- Codex CLI: `codex-cli 0.145.0` at
  `C:\\Users\\Liparakis\\AppData\\Local\\Microsoft\\WinGet\\Links\\codex.exe`.
- `codex login status`: `Logged in using ChatGPT`.
- `CODEX_HOME` was unset for the authenticated run, using the default
  `C:\\Users\\Liparakis\\.codex` account state. No API-key, bearer-token,
  Azure, or other authentication environment variable was present.
- Exact 401 reproduction: an empty isolated `CODEX_HOME` plus
  `codex exec --json --ephemeral --ignore-user-config --model gpt-5.6-luna
  "Reply with exactly OK."` returned exit `1` and
  `401 Unauthorized: Missing bearer or basic authentication in header` for
  WebSocket and HTTPS Responses transport.
- Isolated `codex login status` returned `Not logged in`; `codex doctor`
  reported no credentials. Cause is missing isolated-home credentials, not an
  expired or invalid default ChatGPT login. No credentials were changed.
- Independent authenticated invocation with `--ignore-user-config` returned
  `OK`, exit `0`, PID `17000`, UTC `15:09:23`–`15:09:27`.

## Official bundle and fresh project

- Bundle:
  `C:\\Users\\Liparakis\\Desktop\\Synesis\\cli\\build\\platform-bundle\\synesis-0.1.0-dev.local-windows-x64`.
- MCP SHA-256:
  `07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.
- Packaged CLI JAR SHA-256:
  `E5D10201094A99925E975DC593A8DF606DE7308A080E48652186D07DAE313329`.
- CLI version: `synesis 0.1.0-SNAPSHOT`; MCP used the official packaged path.
- Fresh project:
  `C:\\t\\syn041-real-codex-20260828-007`.
- Project ID: `cd8e64c0-8238-4526-b0f4-d3d0f474dbc5`.
- Clean baseline commit:
  `434e4d982d570329ff6403d65f1fafbc432d5703`; `verification.txt` contained
  `ok`.

## Real topology and lifecycle

| Process                                  |   PID | Parent | Start                   |
|------------------------------------------|------:|-------:|-------------------------|
| Codex `codex-x86_64-pc-windows-msvc.exe` | 17156 |   8112 | 2026-08-28 15:05:55 UTC |
| Official `synesis-mcp.exe`               | 19784 |  17156 | 2026-08-28 15:05:55 UTC |
| Packaged Java runtime                    | 11356 |  19784 | 2026-08-28 15:05:55 UTC |

Connection ID: `syn041-real-codex-connection-20260828-007`  
Session/binding ID: `session-d2915a2f-55f2-4fd0-8277-0d7cb89d643b`  
Lease file: external `admin/session-leases/syn041-real-codex-connection-20260828-007.json`

The real Codex trace proves `ensure_session` completed with `status=ready`
and an isolated worktree. The next call, `get_next_action({})`, remained
`in_progress`; no `finish_lane`, explicit completion, participant ID,
WorkIntent ID, or WorkGroup ID was surfaced. Coordination status stayed at
sequence `0`, predictions `0`, tasks `0`, ownerships `0`. The lease remained
`ACTIVE` with no heartbeat advancement.

The provider/runtime stalled. The verified disposable Codex/MCP/Java tree was
therefore ended as the permitted active-session crash control at
`2026-08-28T15:08:30Z`. Codex exit was `-1`; no MCP EOF or clean MCP exit was
observed. The lease remained `ACTIVE` after termination.

Doctor after termination reported `DEGRADED`, zero errors, zero mutations,
one fresh `stale_session_lease` warning with fingerprint
`200bc490ac1e7fef13f8cdf53382a45f1c1bb1a3ae46cf8d743189643c1585bf`, and the
two pre-existing host-wide command namespace warnings. Provider migration did
not appear for this fresh project.

## Result and disposition

Primary result: **RESULT D — inconclusive**. The required proof tuple was not
reached: lawful explicit completion, terminal binding/WorkGroup, Codex exit
0, observed MCP EOF, and MCP exit 0 are all absent. This is provider/runtime
engagement evidence, not a proven Synesis lease defect.

Optional crash-control result: expected abnormal-session behavior. An active
lease remained active after deliberate provider/runtime termination and Doctor
reported stale state. This supports retaining fail-closed stale detection.

No real-provider lease defect is proven and no implementation is recommended.
The next measurement should isolate why real Codex does not receive or
complete the first `get_next_action` response, then repeat with explicit
completion and MCP EOF/exit capture. Do not change lease or Doctor semantics.

Provider migration remained untouched; no Antigravity work was performed.
SYN-039 remained `DONE / ACCEPTED`; no generalized identity architecture was
implemented; nothing was pushed, tagged, or released.
