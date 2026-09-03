# SYN-050 provider-session continuity source trace — 2026-09-03

Status: DESIGN COMPLETE / IMPLEMENTATION BLOCKED; no production code or
historical fixture was changed.

## Scope and provenance

This trace covers only the provider-session continuity defect found after
SYN-049. SYN-049 remains PARTIAL; its Defect A and Defect B evidence and the
fixture `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260903-08`
remain untouched. The Synesis implementation baseline before SYN-050 planning
was `03ad98a0df3fb295541390d638c0e3d71c016796`. Commit `d3b6429` contains only
the SYN-050 planning and ADR updates.

The targeted architecture graph was generated from a disposable copy of 23
relevant source files. It reported 585 nodes, 1,798 edges, and no import
cycles. The graph and temporary copy are diagnostic artifacts outside this
repository; they are not runtime state or acceptance evidence.

## Verified source path

1. `SynesisMcpServer` reads the optional
   `SYNESIS_MCP_CONNECTION_INSTANCE_ID`; when it is absent, it creates
   `conn-instance-` plus a random UUID. It captures a separate random process
   nonce. See
   [`SynesisMcpServer.java`](../../mcp/src/main/java/org/synesis/mcp/SynesisMcpServer.java#L36).
2. `McpProtocolHandler.handleInitialize` extracts project roots and returns
   the MCP handshake. It does not read `clientInfo`, a thread identifier, a
   conversation identifier, or any other provider conversation metadata. The
   later `SessionResolutionRequest` contains only the connection identity.
3. The Codex hook recognizes `session_id`, `sessionId`, `conversation_id`, and
   `conversationId`, and passes the first matching value to
   `ProviderSessionBindingService.ensure`. That hook is a separate provider
   process invocation; the value is not passed to the normal MCP server.
4. `CodexTomlConfiguration` writes a static Synesis MCP command with provider
   and project arguments. It writes no dynamic conversation/thread value and
   no per-server identity environment variable. `codex mcp get synesis` also
   reported `env: -` in the local installation.
5. `ProviderSessionBindingService` hashes the supplied provider-instance
   evidence into the binding filename and durable record. The binding has no
   raw conversation/thread identity or continuity proof. After a clean
   `PARTICIPANT_DETACHED` event, `ensure` deliberately creates a new binding
   and therefore a new session identity.
6. `SessionAuthorityResolver` performs exact project/provider/connection
   fingerprint-or-session matching and requires `BOUND`; it does not select a
   latest binding. This fence must remain unchanged.
7. `SessionLeaseService` records process evidence for an exact connection,
   but an existing exact lease retains the earlier process identity during a
   normal renewal. It does not itself authenticate a replacement process or
   establish a same-conversation handoff.
8. The existing `continueFromRecovery` path requires a durable recovery-held
   source, immutable snapshot reference, single-use lane grant, exact source
   epoch, and a newly authenticated target intent/participant. It intentionally
   transfers a recovery lane; it is not a same-session MCP reattachment seam.

The separate Codex App Server lifecycle does carry exact binding-scoped
`threadId` state and launches its MCP child with an explicit connection ID,
but that is a provider-supervised App Server path. It does not provide the
identity to an ordinary Codex stdio MCP connection and cannot be silently
substituted for that path.

## External provider boundary

The installed local CLI is `codex-cli 0.145.0`. The current Codex upstream
record for [issue #19937](https://github.com/openai/codex/issues/19937) reports
that `CODEX_THREAD_ID` is available to shell/tool executions but is not
available to local stdio MCP server startup; the issue is closed as not
planned. That matches the local configuration observation. A value visible in
this operator shell is therefore not an authority-bearing MCP input.

## Design-class decision

### Option A — stable conversation-scoped MCP identity

Not selectable from current evidence. Synesis can safely consume a stable
provider identity only when the provider supplies it at the MCP trust boundary.
The ordinary Codex stdio launch currently supplies neither the hook's
conversation value nor a supported dynamic environment source. Using a
project/provider marker, newest hook record, current shell variable, or model
argument would allow unrelated or concurrent conversations to inherit
authority and would weaken the exact resolver boundary.

### Option B — explicit audited continuity/handoff

Not available through the current normal MCP surface. The existing recovery
mechanism is single-use, snapshot-backed, epoch-fenced, and creates a new
participant/intent. There is no existing generic same-session reattach command
or trusted MCP input carrying a handoff proof. Adding one would require a new
provider-to-MCP trust channel and a durable continuity protocol, not a narrow
fix at `AgentNextActionService` or a fallback in `SessionAuthorityResolver`.

## Decision and stop condition

The design-gate investigation is complete; no production design is accepted
and no production implementation is started. The hard stop is: a safe
continuity proof cannot be established at the normal
Codex stdio MCP boundary, and the existing recovery path is semantically
different. SYN-050 is blocked pending a provider integration contract that
supplies an exact per-conversation identity or an equivalent audited,
single-use continuity proof to the MCP process.

When that prerequisite exists, resume by revalidating the provider input,
designing process-generation takeover under the existing lease fencing,
handling clean/abnormal/replayed/live-original races, and only then adding
focused tests and a fresh acceptance. Do not reopen or mutate SYN-049, rewrite
historical durable state, copy worktrees, add an MCP tool, invent IDs, or
bypass a lifecycle gate.

## Verification commands

- `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` — PASS.
- `git status --short` — clean before this evidence-only update.
- Targeted `rg` source trace across MCP startup, Codex integration, hook,
  binding, resolver, lease, collaboration, and recovery code — findings above.
- `codex --version` — `codex-cli 0.145.0`.
- `codex mcp get synesis` — installed Synesis MCP command, static project
  arguments, `env: -`.
- Targeted disposable graph analysis — 23 files, 585 nodes, 1,798 edges,
  15 communities, no import cycles.
