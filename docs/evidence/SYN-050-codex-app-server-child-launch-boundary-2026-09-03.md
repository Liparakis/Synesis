# SYN-050 Codex App Server child-launch boundary investigation — 2026-09-03

## Disposition

**Child-boundary classification: FAIL for `codex-cli 0.145.0`.** The
disposable probe proves that Codex App Server can host exact threads, launch
MCP children, and resume an exact thread in a replacement App Server process.
It does not expose a supported route for Synesis to inject a private,
per-worker attachment proof into the exact MCP child. Static MCP configuration
reaches the child, but parent process-private context is cleared and no thread
or attachment context is supplied. This is the missing join in SYN-050.

The isolated protected-carrier model remains a separate PASS. This result does
not invalidate that model and does not authorize production managed-continuity
implementation. SYN-050 remains `ACTIVE / DESIGN_APPROVED /
PROTOTYPE_PARTIAL / IMPLEMENTATION_BLOCKED`.

## Scope and preservation

- Starting source HEAD: `189d7198bfd710c997f3ac27b4e1263638a77960`.
- Branch: `master`.
- Starting working tree: clean.
- SYN-049 remains `PARTIAL` and was not reopened.
- `GOONSQUAD`, the preserved task-tracker fixture, historical `.synesis`
  state, provider configuration, and the unrelated stash were not touched.
- No production Java, Codex source, Synesis durable state, provider lifecycle,
  MCP catalog, or authority rule was changed.
- The Synesis source checkout was not initialized or self-hosted as a customer
  project. No Synesis MCP calls, manual Synesis identifiers, metadata edits,
  worktree copies, forced lifecycle actions, or bypasses were used.

Evidence classifications in this record are:

- **SOURCE FACT** — read from the version-matched upstream Codex source.
- **LOCAL OBSERVATION** — observed in the disposable Windows probe.
- **INFERENCE** — a bounded conclusion from those facts; it is not presented
  as a provider guarantee beyond the tested version and topology.

## Provider and disposable provenance

The provider used by the real probe was:

```text
C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe
codex-cli 0.145.0
SHA-256 83751F15CB6A0A7B97DF67752C001E3FE1C20E18FFBFEC3FF63567296205EB6C
```

The version-matched upstream source was the official `rust-v0.145.0` tag
(tag commit `25af12f7e61572b0bc18ddb1008be543b91519b0`). The relevant sources
are [`codex-mcp/runtime.rs`](https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/codex-mcp/src/runtime.rs),
[`codex-mcp/rmcp_client.rs`](https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/codex-mcp/src/rmcp_client.rs),
[
`rmcp-client/stdio_server_launcher.rs`](https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/rmcp-client/src/stdio_server_launcher.rs),
[
`codex-mcp/connection_manager.rs`](https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/codex-mcp/src/connection_manager.rs),
and
the [App Server thread lifecycle](https://github.com/openai/codex/blob/rust-v0.145.0/codex-rs/app-server/src/request_processors/thread_lifecycle.rs).

The final disposable run is under:

```text
C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-05
```

It contains only temporary homes/workspaces, compiled helper classes, safe
presence flags, process relationships, naturally returned provider thread
IDs, and redacted response summaries. The helper sources were compiled with:

```text
C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\javac.exe
```

Final disposable source hashes:

| File                               | SHA-256                                                            |
|------------------------------------|--------------------------------------------------------------------|
| `BoundaryLaunchInvestigation.java` | `9622DCE3E62E8DF5E7FD414C8C4466AA3ACCA526A3FB24A00EED51416354C9EA` |
| `BoundaryMcp.java`                 | `FFFC5DDC611C372A72242C0A0B3284A8FD238CA3D538DDA5649EAD020023A513` |
| `BoundaryWrapper.java`             | `D4F8C46C864B9D4C9182CE81D6A9EECE8B22AE7604CC5592A2BD2361F1CEDA6F` |

The final report hash is
`13399470EE0AE6A90915591DD587197102D591870483E38510124B2077A9F8A4`,
with the full report at
`C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-05\boundary-investigation-report.txt`.

## Exact child-launch boundary

### Version-matched source facts

`codex-mcp::rmcp_client::make_rmcp_client` receives a configured
`McpServerTransportConfig::Stdio` containing only `command`, `args`, `env`,
`env_vars`, and `cwd`. It selects `LocalStdioServerLauncher` for a local
environment and calls `RmcpClient::new_stdio_client`.

`rmcp-client::StdioServerLauncher` is the process-placement boundary. Its
public trait is sealed to the module's implementations and its input is only
`StdioServerCommand`. `LocalStdioServerLauncher::launch_server` resolves the
configured executable, calls `env_clear()`, applies the configured environment
overlay, sets `current_dir(cwd)`, applies the configured arguments, and creates
standard piped stdin/stdout/stderr. It enables `kill_on_drop`; on Windows the
local terminator records the child PID. The source exposes no thread ID,
attachment proof, per-thread callback, extra-handle parameter, or private
carrier parameter at this boundary.

The `McpRuntime` source describes the runtime as owning the current MCP
connection set for one Codex thread. `McpConnectionManager` owns the configured
clients for that runtime, keyed by MCP server name. This is the source basis
for the per-thread client expectation; the process relationships below are the
local confirmation for `codex-cli 0.145.0`.

### Local topology observations

The disposable MCP server recorded its parent PID and the safe presence of
parent-only and static-config markers:

| Scenario                    |    App Server | Wrapper starts | MCP starts | Observation                                                                           |
|-----------------------------|--------------:|---------------:|-----------:|---------------------------------------------------------------------------------------|
| One App Server, two threads |             1 |              2 |          2 | each exact thread call caused its own wrapper/MCP chain under the same App Server PID |
| Dedicated A/B               |  2 concurrent |         1 each |     1 each | distinct App Server, wrapper, and MCP processes                                       |
| A successor after restart   | replacement A |              1 |          1 | new wrapper/MCP chain under replacement A                                             |

The shared run returned two naturally created distinct provider thread IDs and
called the disposable MCP tool with each exact thread. The dedicated run
returned distinct thread IDs for A and B, called both while their App Servers
were live, and rejected a request sent to B's App Server using A's thread ID.

The observed process chains were:

```text
shared App Server PID 16760
  ├─ wrapper PID 21176 → MCP PID 11484
  └─ wrapper PID 24536 → MCP PID 19480

dedicated A1 PID 19392
  └─ wrapper PID 9092 → MCP PID 10824
dedicated B PID 14152
  └─ wrapper PID 11412 → MCP PID 15644
dedicated A2 PID 21044
  └─ wrapper PID 6652 → MCP PID 16188
```

PIDs are diagnostics only and are not authority. The source and observation
together establish that the tested path is not one permanently shared MCP
child for all threads; it creates separate MCP client processes for the
thread-owned runtimes. They do not establish that an MCP child receives the
thread ID.

## Wrapper and context results

The App Server was configured to launch `BoundaryWrapper`, which launched
`BoundaryMcp` and forwarded the standard byte streams. The wrapper proved that
interposition is operationally possible. It did not solve authentication.

The App Server parent was launched with safe parent-only markers. The static
MCP configuration supplied a separate safe marker. Every wrapper and MCP child
recorded:

```text
parentSentinelPresent=false
staticConfigPresent=true
appLabelPresent=false
threadContextPresent=false
attachmentProofPresent=false
```

This is the decisive local environment observation. The configured static
environment reaches the child, while the App Server's parent environment is
not forwarded through the local launcher. No thread ID, App Server label, or
attachment proof reached the wrapper or child. The wrapper sees static argv,
static configured environment, its working directory, and its parent process;
that context is insufficient to authenticate the originating thread.

The exact App Server request boundary did enforce thread selection: sending A's
thread ID to B's App Server returned `thread not found`. That is a useful
protocol routing property, not proof that the MCP child knows or authenticates
that thread.

## Handles, pipes, sockets, and restart

### Inherited handles

**SOURCE FACT:** the version-matched launcher creates only standard piped
stdin/stdout/stderr for the configured child and has no public extra-handle or
handle-selection input. The source contains no provider extension point for a
Synesis-created handle.

**LOCAL OBSERVATION:** the wrapper successfully forwarded the standard MCP
stdio streams and the MCP child received the protocol. The probe did not claim
or use an extra OS handle, because the App Server boundary offers no place to
inject one without patching the provider. Whether an arbitrary externally
created Windows handle happens to survive an undocumented OS/process spawn
configuration is therefore not treated as proven capability.

An inherited handle cannot be scoped to exact thread T through the current
public launch contract. At most, an undocumented process-scoped inheritance
behavior could belong to the App Server process, which is insufficient for a
shared process with multiple threads and is not a supported Synesis carrier.

### Private pipe or local socket

The isolated Synesis prototype's anonymous inherited child-stdin pipe remains
valid as a carrier model, but the real App Server owns the child stdio handles.
The current App Server JSONL protocol and static MCP configuration provide no
private per-thread pipe/socket attachment input. A wrapper-owned endpoint could
be named in static config, but endpoint possession would not identify the
originating thread and the current provider does not deliver a protected
per-thread proof or nonce to that wrapper. A local endpoint therefore remains
an unproven transport, not an authentication fix.

### Process-private environment

The tested process-private parent environment is **not viable at the current
boundary**: `env_clear()` removes it before the local MCP child is launched.
Putting a secret in static MCP configuration would make it configuration
state, not a per-worker managed attachment, and was not done. A future
provider-managed launcher could deliberately inject a unique rotated value
into a dedicated App Server/MCP child, but that is an upstream/provider
feature, not current behavior.

### App Server restart

The dedicated run established this lifecycle:

```text
A1 → exact thread T_A → MCP child C1
A1 stops
A2 → thread/resume T_A → MCP child C2
```

`thread/resume` succeeded for the exact naturally returned thread ID, the
replacement MCP call returned `boundary-ok`, and B's App Server and thread
continued to answer a call while A restarted. The no-op turn completed before
the probe's interrupt request won the race; this does not affect the exact
resume result and is not model-work evidence.

The replacement child received no fresh attachment proof. Therefore fresh
successor proof delivery is **FAIL/NOT PROVEN**, even though exact provider
thread resume is **PASS**.

No full host H1→H2 restart was performed. The process-level result cannot be
promoted to a host-restart trust result.

## Security and leakage results

- No raw authority proof was generated or sent through the real App Server
  boundary. The safe `attachmentProofPresent` flag remained false.
- No proof appeared in App Server input, MCP protocol payloads, wrapper/MCP
  output, configured files, project files, or the disposable logs.
- No proof was placed on a command line, so the command-line audit is a
  negative result for this candidate, not evidence that command-line secrets
  would be safe.
- No proof was placed in an environment, so no environment dump or process
  listing could expose one. The parent-only environment test specifically
  showed that the child did not receive those markers.
- No crash report was produced; crash-report leakage is not claimed tested.
- Static labels, paths, PIDs, and provider thread IDs in the report are
  diagnostics and not secrets.

The wrong-thread result proves provider request routing only. No stale proof,
replay, generation, or live-old proof test can be run at this boundary until a
real private carrier exists. The isolated harness already proves those
properties for the Synesis-side carrier model.

## Trust-chain decision

The tested chain is only:

```text
managed App Server process
→ exact thread/start or thread/resume
→ thread-scoped MCP request routing
→ configured MCP child
```

The missing link is:

```text
exact managed thread T
→ private, non-model-visible, per-worker proof
→ exact MCP child C
```

Exact `thread/resume` is not sufficient by itself to mint a fresh proof. It
would become one input to that decision only after a provider-managed launch
contract proves that Synesis controls the exact App Server process/thread and
can inject a rotated, private, generation-scoped carrier into the child.
The current evidence proves process control and exact thread correlation, but
not that final authority-bearing attachment.

## Classification and smallest unblock

- Shared-process thread hosting: **operationally PASS; secure carrier NOT
  PROVEN**.
- Dedicated one-process-per-worker topology: **operationally PASS; private
  proof delivery FAIL at the current boundary**.
- Concurrent A/B process/thread/child isolation: **PASS for topology and
  protocol routing; private proof A/B isolation NOT PROVEN because injection
  is unavailable**.
- Current Codex App Server child-launch boundary: **FAIL** under SYN-050's
  definition.
- Production managed-continuity implementation: **not unblocked**.

The smallest upstream/provider feature required is a provider-managed,
non-model-visible attachment input at the actual MCP child-launch boundary.
It should carry an opaque per-managed-thread or per-dedicated-process
attachment context, bind it to the exact App Server thread and generation, and
make it available only to that child launch. It must not be a static config
value, model-visible protocol field, command-line bearer, or global environment
secret. The provider must define restart behavior so A2 receives a fresh
generation without requiring raw P1 to survive.

The exact upstream boundary is:

```text
codex-rs/rmcp-client/src/stdio_server_launcher.rs
  StdioServerLauncher
  LocalStdioServerLauncher::launch_server

plumbing from:
codex-rs/codex-mcp/src/rmcp_client.rs
  make_rmcp_client

thread ownership context:
codex-rs/codex-mcp/src/runtime.rs
  McpRuntime
```

No Codex source was patched in this pass. A conservative v1 should retain one
Synesis-managed App Server process and one exact thread per logical worker,
then prove the new carrier under that topology before considering shared
process multiplexing.

## Validation record

Final disposable command:

```powershell
javac -encoding UTF-8 -d C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-05\classes C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\BoundaryMcp.java C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\BoundaryWrapper.java C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-01\src\BoundaryLaunchInvestigation.java
java -cp C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-05\classes BoundaryLaunchInvestigation C:\Users\Liparakis\AppData\Local\Temp\syn050-child-boundary-20260903-05 C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe
```

Result: `BOUNDARY_INVESTIGATION=PASS` for the disposable observer. The
observer's findings are the bounded FAIL above: static configured context
reached the child, parent-private context and proof did not.

The report records:

- one App Server with two exact threads and two independent MCP child chains;
- two concurrent dedicated App Servers with distinct exact threads and child
  chains;
- wrong-thread request rejection;
- A1 stop, A2 exact-thread resume, fresh C2 launch, and B continuity;
- absent parent/process/thread/proof context at every wrapper and MCP child;
- no raw proof or secret-bearing command/config/log material.

The temporary helper and all compiled material remain outside Git. This record
is the only retained production-repository artifact from this pass.
