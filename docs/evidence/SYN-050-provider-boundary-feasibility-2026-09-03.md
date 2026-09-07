# SYN-050 provider-boundary feasibility spike

Date: 2026-09-03

Status: feasibility complete; no provider-continuity production implementation authorized

This is a bounded design/evidence record for the provider-session continuity
question. It does not change the Result A decision, does not alter provider
configuration, and does not inspect or modify `.synesis/**` state. Completion
lifecycle and dependency admission remain separate defects and are not
redesigned by this spike.

## 1. Provenance and evidence classification

- Starting source HEAD: `8998a528734e480be0f2bb505722310134d0290f`.
- Branch: `master`.
- Starting working tree: clean.
- Codex CLI: `codex-cli 0.145.0`.
- Claude Code: `2.1.220 (Claude Code)`.
- Codex executable: `C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`.
- Claude executable: `C:\Users\Liparakis\.local\bin\claude.exe`.
- Evidence below is marked as VERIFIED (direct local observation or primary
  provider documentation), DERIVED (reasoned from verified boundaries), or
  UNKNOWN (not claimed without a provider/runtime contract).
- No source production files, provider configuration, project metadata, or
  historical fixture state was changed during the investigation.

The local source repository remains an ordinary Git/human-authority checkout.
It is not a Synesis-managed customer project.

## 2. Managed attachment definition

A managed attachment is the narrow boundary by which Synesis can prove that a
provider runtime's current conversation/session is the same authorized
attachment for the current Synesis lane. It may carry identity, a private
attachment credential, restart generation, and fencing information. It must
not depend on model-visible prompt text, a project-wide static secret, a bare
PID, an arbitrary connection identifier, or a guessed conversation mapping.

The required proof is channel-specific and provider-runtime-specific. A
logical MCP connection, WorkIntent, participant handle, or provider session
binding is not by itself proof that the provider currently speaking to MCP is
the authorized conversation.

## 3. Codex findings

### 3.1 Ordinary MCP launch boundary

VERIFIED locally:

- `codex mcp get synesis --json` reports a static stdio command:
  `synesis-mcp.exe mcp --provider codex --project <project>`.
- The configured Synesis entry has no dynamic environment variables or
  per-conversation argument. The command points at the historical acceptance
  fixture and was not changed.
- `codex mcp add --help` exposes static command, argument, and environment
  configuration. It does not expose an active conversation/session identity
  input to a normal stdio MCP child.
- `McpProtocolHandler` receives the provider, project, static connection
  instance, and process evidence. It does not receive a Codex thread ID or
  conversation identity from ordinary stdio MCP initialization.

The Codex MCP documentation describes standard MCP over stdio and configured
server launchers, while its thread RPCs are part of the separate application
server interface.
See [Codex MCP interface](https://github.com/openai/codex/blob/main/codex-rs/docs/codex_mcp_interface.md).

PRIMARY UPSTREAM ISSUE EVIDENCE: Codex issue #19937 documents that the tested
stdio MCP path does not receive an active `CODEX_THREAD_ID` and discusses
per-thread propagation as a missing capability. This is issue evidence, not a
product guarantee: [Codex issue #19937](https://github.com/openai/codex/issues/19937).

### 3.2 Wrapper and broker evaluation

DERIVED from the verified static boundary:

- A transparent launcher wrapper can replace the configured executable and
  preserve the same stdio protocol, so it is technically feasible as a
  process mediator.
- The wrapper sees only its static argv/environment and process context. It
  cannot derive a trustworthy active Codex conversation identity that the
  ordinary MCP boundary does not provide.
- A local broker has the same limitation. It can multiplex or route channels,
  but an anonymous provider connection cannot prove which conversation caused
  the request. A user-wide token or project-wide secret would authenticate a
  scope, not a conversation.
- Therefore wrapper and broker approaches do not solve continuity in the
  transparent ordinary Codex UX. They become viable only when Synesis owns the
  provider launch/attachment and establishes a protected private channel.

### 3.3 Codex App Server evaluation

VERIFIED locally in `CodexAppServerLifecycleService`:

- Synesis can launch `codex app-server`, initialize it, start an exact thread,
  record the exact provider thread ID, and resume that exact thread.
- The managed launcher passes Synesis-controlled project, provider, and
  connection values into the configured MCP server environment.
- The existing service records attachment and connection generations and
  fences lifecycle transitions.
- Those environment values are transport/binding context, not a
  provider-issued conversation proof. The current source has no generic
  RuntimeAuthenticator implementation at this boundary.

The Codex App Server protocol supports exact `thread/resume` by `threadId`; its
documentation also states that concurrent writers for the same paginated
thread are not supported.
See [Codex App Server README](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md).

This makes a Synesis-supervised App Server mode a credible managed-continuity
candidate, but it is a distinct product mode. It is not evidence that ordinary
user-started stdio MCP has continuity.

### 3.4 Codex result

- Transparent ordinary MCP: **D — provider support required**.
- Synesis-supervised App Server/managed launch: **C — only a
  Synesis-managed launch/attachment can provide the missing root**, subject to
  a future protected-channel implementation and acceptance.
- A transparent managed attachment and a one-time static setup do not meet the
  continuity proof requirement on the current ordinary boundary.

## 4. Claude Code findings

### 4.1 Ordinary MCP launch boundary

VERIFIED locally and against the provider documentation:

- `claude mcp list` currently reports no configured MCP servers and diagnoses a
  malformed user `.mcp.json` using `servers` instead of the required
  `mcpServers` shape. This local configuration was not repaired because it is
  outside this spike.
- `claude mcp add --help` supports static command arguments and static
  environment values.
- The ordinary MCP server boundary receives the configured command, arguments,
  and environment. The documented `CLAUDE_PROJECT_DIR` identifies the project
  root; it is not a per-conversation proof.
- `claude --help` exposes provider session controls such as `--resume` and
  `--session-id`, but those controls are on the Claude runtime command line,
  not an automatically supplied identity field in an ordinary MCP server
  initialization.

Claude's MCP documentation describes local stdio servers, project-root
`CLAUDE_PROJECT_DIR`, static `--env` values, and configuration/environment
expansion. See [Claude Code MCP documentation](https://code.claude.com/docs/en/mcp).

### 4.2 Hooks, wrapper, and broker evaluation

VERIFIED from the Claude hooks documentation and the local integration source:

- Claude hooks can receive a `session_id` and `cwd`; `SessionStart` also
  distinguishes new/resumed sessions.
- Command hooks receive event JSON on stdin and execute outside the model loop.
- The hook's session metadata is not automatically forwarded into a separate
  ordinary MCP stdio initialization.
- `CLAUDE_CODE_BRIDGE_SESSION_ID` is documented for Remote Control, not as a
  general ordinary local MCP conversation credential.

See [Claude Code hooks reference](https://code.claude.com/docs/en/hooks).

DERIVED:

- A wrapper can observe a hook or launch environment only if an authorized
  launcher explicitly joins those two boundaries. A wrapper placed in the
  ordinary static MCP command cannot prove that its current stdin belongs to
  the hook's conversation.
- A broker can route a provider-supplied session token if Claude exposes one
  to MCP, but it cannot manufacture that proof from `cwd`, a static secret,
  or process ancestry.

### 4.3 Claude result

- Transparent ordinary MCP: **D — provider support required**.
- A future Synesis-managed Claude launch/attachment: **C — possible only as a
  distinct managed mode**, with a protected channel joining the runtime/session
  proof to the MCP child.
- Claude hooks are useful lifecycle/session metadata and a possible future
  adapter input, but they are not currently a continuity transport.

## 5. Provider comparison matrix

| Boundary           | Per-conversation input reaches ordinary MCP?               | Wrapper alone               | Broker alone                    | Managed launch                           | Classification |
|--------------------|------------------------------------------------------------|-----------------------------|---------------------------------|------------------------------------------|----------------|
| Codex stdio MCP    | No trusted thread/conversation proof observed              | Mediates process only       | Routes anonymous channel only   | Required for a private channel           | D              |
| Codex App Server   | Exact thread is controlled by managed App Server lifecycle | Can be part of managed mode | Can be part of managed mode     | Existing lifecycle is the candidate seam | C candidate    |
| Claude stdio MCP   | No documented session proof in MCP init                    | Mediates process only       | Routes anonymous channel only   | Required to join hook/runtime proof      | D              |
| Claude hooks + MCP | Hook has session metadata, MCP does not                    | Requires explicit join      | Requires provider-carried proof | Required for a trusted join              | C candidate    |

The matrix is a boundary assessment, not a claim that all future provider
support is impossible. A provider-native per-conversation MCP credential or
equivalent authenticated attachment would move the ordinary paths out of D.

## 6. OS, process, and state mechanisms

The following mechanisms were evaluated against the threat and failure model:

- Inherited handles, named pipes, Unix sockets, and local TCP: useful only
  when a trusted Synesis-owned launcher creates and protects the endpoint;
  endpoint possession alone does not identify a conversation.
- OS ACLs and protected credential stores: reduce unauthorized access, but a
  user/project-scoped credential is not conversation-scoped. A protected
  per-attachment handle or credential is required.
- Environment variables and command-line arguments: suitable for managed
  launch metadata, but unsafe as a model-independent root if provider input is
  static, user-controlled, or logged. The current connection ID is not enough.
- Process parentage, PID, executable path, start time, and nonce: useful
  diagnostic/process evidence and stale-process detection; not a durable
  conversation authenticator. PIDs can be reused, providers can restart, and
  a process may host or launch more than one logical context.
- Provider session directories and hook metadata: useful correlation hints;
  not authoritative unless the provider signs or otherwise protects the
  association and carries it to MCP.
- Codex App Server local transport authentication: protects the App Server
  transport, but does not by itself prove that an ordinary MCP child is bound
  to the active provider conversation.

Process lineage is therefore a supporting signal, never the sole continuity
root. Per-conversation durable state can preserve a known attachment and
generation across MCP restart, provider-runtime restart, or full provider
loss, but it cannot recreate missing proof. On loss or ambiguity, the safe
behavior is fail closed and require a new managed attachment or an audited
recovery path.

## 7. Trust and survival answers

- Trust root for a feasible managed mode: a Synesis-owned provider launcher or
  App Server control plane plus a protected per-attachment channel, with
  server-side generation/fencing checks.
- Credential visibility: feasible to keep the attachment credential out of
  model-visible prompts, project files, ordinary logs, and task prose only in
  the managed protected-channel design. Current static IDs and environment
  values are not such a credential.
- Same-human-chat distinguishability: not proven for ordinary Codex or Claude
  MCP. It is possible only when the provider gives Synesis a trustworthy
  conversation identity or Synesis owns the launch boundary.
- MCP restart: survivable only when the same managed attachment can be
  re-established with a protected generation/credential; ordinary reconnect
  does not prove sameness.
- Provider-runtime restart: exact Codex App Server thread resume is available
  in the managed lifecycle candidate; generic MCP continuity remains
  unproven and must fail closed.
- Full provider loss: no transparent continuity claim. Durable state can
  preserve the obligation and recovery context, not authenticate an unknown
  replacement runtime.

## 8. Product capability profiles

The recommended profiles are:

1. `SESSION_BOUND` for ordinary Codex stdio MCP and ordinary Claude stdio MCP:
   accept only the current connection/session binding; do not claim
   conversation continuity across ambiguous restart or replacement.
2. `MANAGED_CONTINUITY_CANDIDATE` for Synesis-supervised Codex App Server:
   continue design around its exact thread/resume and generation fences, but
   require a protected attachment channel before claiming completion.
3. `MANAGED_CONTINUITY_CANDIDATE` for a future Synesis-managed Claude launch:
   join hook/runtime session evidence to the MCP child through a protected
   channel; do not treat hooks alone as authorization.
4. `PROVIDER_NATIVE_CONTINUITY` as the preferred long-term ordinary UX when a
   provider exposes an authenticated per-conversation MCP identity.

There is no provider path available today that satisfies transparent ordinary
continuity for either provider. The current source has a managed Codex App
Server lifecycle seam, but not the missing generic authenticated attachment
primitive. Claude has hook session metadata, but no verified ordinary MCP
carrier for that metadata.

## 9. Feasibility decision and bounded implementation plan

The result is **C/D**, not A/B:

- A transparent managed attachment for ordinary MCP is not feasible with the
  current provider boundaries.
- B one-time static/provider-mode setup does not add the missing trusted
  per-conversation proof.
- C a Synesis-managed launch can provide the necessary boundary, but it is a
  new managed product mode with explicit UX cost and implementation scope.
- D provider support is required to preserve ordinary provider UX with
  transparent continuity.

No provider support patch, broker, or identity redesign is justified by this
spike. If the product chooses to implement managed continuity, the bounded
order is:

1. Specify one protected attachment credential/channel and its lifecycle,
   including restart generations, revocation, and fail-closed behavior.
2. Implement one provider adapter at the managed launch boundary, beginning
   with the existing Codex App Server lifecycle seam.
3. Keep the credential outside model-visible data and project/task state.
4. Bind the channel to the existing provider/session authority and execution
   fences; do not add a parallel identity model.
5. Add provider-loss, restart, stale-generation, same-human-chat, and
   adversarial cross-attachment tests.
6. Only after that, evaluate a Claude managed-launch adapter and any provider
   upstream request for ordinary MCP continuity.

If the product does not accept managed-launch UX, the correct next action is a
provider contract/upstream request, not a transparent wrapper workaround.

## 10. Future acceptance plan

The future compliant acceptance must use fresh disposable state and record
source/build/install provenance. It must prove, for each supported managed
provider mode:

- a Synesis-owned launch creates a private attachment channel;
- the credential is not model-visible or project-persisted;
- same-human conversations receive different attachment identities;
- ordinary work remains lane-local and authority-fenced;
- MCP restart reattaches only with valid generation/credential proof;
- provider restart resumes only the exact managed conversation/thread;
- provider loss fails closed rather than rebinding to a replacement;
- stale and cross-attachment requests are rejected;
- no manual Synesis identifiers, durable-state edits, worktree copies, or
  protocol bypasses occur.

The existing two-worker task-tracking acceptance remains the acceptance for
completion lifecycle and dependency admission. Provider continuity is a
separate future gate and must not be smuggled into that acceptance.

## 11. Planning and validation outcome

- Existing `SYN-050` remains `ACTIVE` / `DESIGN_COMPLETE` /
  `IMPLEMENTATION_BLOCKED`; no status change is justified.
- Existing Result A remains valid for the ordinary provider boundary.
- The new evidence is recorded in this file and linked from the task/ADR
  state. No new broad milestone was created.
- No source production validation was required because no production code was
  changed. The local provider version/configuration probes, source boundary
  inspection, and primary provider documentation review were completed.
- `scripts/agent-resume.ps1`: PASS.
- `scripts/agent-validate-deferred.ps1`: PASS; nine deferred entries.
- `scripts/agent-validate-fixtures.ps1`: PASS, including intentional
  corruption rejection.
- `git diff --check`: PASS; only expected Git line-ending warnings were
  reported. `scripts/agent-doctor.ps1` retains the previously known vague-
  continuation error and personal absolute-path warning; neither is caused by
  provider-boundary behavior and neither was broadened or repaired here.
- No commit had been created at the time this evidence was drafted; the
  resulting documentation-only commit and final HEAD are reported in the
  session response.
