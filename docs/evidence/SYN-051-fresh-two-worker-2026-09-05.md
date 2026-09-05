# SYN-051 fresh two-worker acceptance — 2026-09-05

## Classification

**FAIL / incomplete acceptance.** This bounded run stopped at the first
material runtime failure. It does not supersede the earlier single-worker
runtime PASS-A evidence and does not authorize A2 continuation from this run.

## Gates

- Synesis HEAD at start: `cdc5762e078b0653687417fe92d885fb280f4d7a`; worktree
  clean.
- Runtime source reference: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- JDK: Temurin `25+36-LTS`,
  `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`.
- Process-local property only:
  `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()` and minimal `HttpServer` create/start/stop passed.
- Produced and installed hashes matched for workspace
  `7b3b5066f46140cae88a3357f0156e45cfb8b432ea1e07f6844983fe5141163a`, MCP
  `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd`, CLI
  `438fbea4ffbf739a530233814db44b53f928f626b3b48b10c263ac6312ed2467`, and
  native MCP `d24737530957fbe82ac05f3d5f43588d5357fdc8eff7377712e3151e0bc25c30`.
- Global `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`, `JDK_JAVA_OPTIONS`, and
  `_JAVA_OPTIONS` were empty; no global setting was changed.
- Target baseline: `main`,
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`; only pre-existing untracked
  `probe-runtime/` evidence was present.
- No historical binding, proof, thread, or worktree was reused.

## Fresh workers

Fresh state was created through supported application flows with one shared
project-scoped `ProjectRuntimeHost` and distinct managed connections:

| | Worker A | Worker B |
|---|---|---|
| Connection | `syn051-final-ab-a-8e19e257-cc93-4699-9f80-5e32bce969b3` | `syn051-final-ab-b-4f29312e-83b4-470e-9dc5-020b0028ae55` |
| Binding | `session-d4097aa2-af29-4e9c-b8d8-15e8d9fb5292` | `session-37198834-b63e-4de9-b8ad-481d3eb5ca93` |
| Participant | `agt_f1141c00-71f5-33d5-8490-50de43ec78f0` | `agt_40743a46-20ca-3113-9eb7-d991deb1c114` |
| WorkIntent | `e53a9209-f22f-36be-885b-8d90201b4688` | `5d50f093-c444-3faa-b61f-67560a80bd91` |
| Claim | `probe-runtime/persistence-ab-a-0b2e90be-4a83-43e7-a310-539ee00aa2c4.txt` | `probe-runtime/persistence-ab-b-741f3499-9e72-47ab-b683-05c21e665f4a.txt` |
| Assigned worktree | `...\\worktrees\\session-d4097aa2-af29-4e9c-b8d8-15e8d9fb5292` | `...\\worktrees\\session-37198834-b63e-4de9-b8ad-481d3eb5ca93` |
| Generation-1 proof digest | `bd704c9d242c07f96713167fd53c58364903a5a35b3fe84e8e0b5a4b56362c52` | `c3ed6b9b2d3d02ab4c16db4c2f060c2ff3050e2c62cc6125c82570d7fb07b01a` |

Both preparations returned generation 1 `PENDING_ACTIVATION`, unresolved
thread, absent ownership, and volatile raw-proof retention. Cross-proof
probes in both directions returned `managed_attachment_rejected`.

## First-start result

Worker A produced AppServer PID `7948` and MCP PID `22228`; both were in the
same production Job, with `assignmentBeforeResume=true`.

Worker B reached a real generation-1 turn and its provider process completed,
but the disposable observer did not capture its MCP child before the process
ended. The observer therefore stopped before claiming the required complete
two-worker containment evidence.

Worker A's provider Thread A was
`01a071cd-237e-7fa1-97e5-ed0987906723`, with turn
`01a071cd-2581-7353-8102-6685908c7c23`. Its lifecycle ended `FAILED` with
`process_exit`; the trusted `turn/completed` event was absent. The generation-1
journal contains `thread/start` and `turn/start`, but no trusted completion.
The provider history row remains `inProgress`.

Worker B's provider Thread B was
`01a071cd-238c-7893-b054-4c0a1d228914`, with completed turn
`01a071cd-2582-7763-9757-5999274ae437`. Its provider `thread_turns` row is
`completed`, the rollout exists, and the exact claim file was created with
the requested marker. The run's cleanup then produced a normal managed-job
empty stop for B; this is not replacement evidence.

The first material failure was A's real turn/protocol path. The journal
records Synesis MCP startup/protocol failures and `ReadFile failed, win32=0`;
no production source was patched and no second fresh acceptance run was
started. A's exact claim was not observed as a completed in-scope mutation;
B's exact claim was the only observed claim mutation in its assigned worktree.

## Stop and hygiene

- No replacement operation was invoked.
- No A2 was created.
- No Worker C was created.
- No dependency, handoff, or full SYN-049 acceptance was run.
- No provider credentials were read, copied, logged, or modified.
- Raw attachment proofs were not printed or persisted in this evidence; only
  digests are recorded.
- The validation JVM and managed children were absent after cleanup. B's
  cleanup stop was incidental; trusted death/replacement validation was not
  attempted.
- No target control-checkout files were manually edited and no push occurred.

## Remaining blocker

The fresh two-worker acceptance is unresolved because Worker A failed to
produce trusted completion while Worker B completed, and complete B MCP/Job
observation was not captured before cleanup. Before another fresh lane, the
co-running Codex/MCP protocol failure must be diagnosed from current
production evidence without reusing these bindings or proofs.
