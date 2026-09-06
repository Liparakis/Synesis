# SYN-049 fresh unattended two-worker acceptance — runs #75–#81

Date: 2026-09-06

## Result

Run #81 is **PASS / COMPLETE** for the bounded SYN-049 unattended two-worker
acceptance. It used one fresh target, one live validation JVM, two lawful
managed generation-1 bindings, the real provider-native wake path, real
claim-scoped coding, exact capability validation, immutable snapshot
publication/integration, and call-local completion requests.

SYN-051 remains **COMPLETE / PASS-A** and was not reopened. No production Java
source was changed and no push occurred.

## Provenance and host boundary

The source checkout was clean on `master` at `c9733fd9e35a97332465f7b5326fe4bb96aba075`
before these fresh runs. Runtime source remained
`a7697bbb5de83ced8b61b275056f9204e4467fbc`. The provenance-locked artifact
hashes remained exact:

```text
workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar  0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3
mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar              029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f
cli/build/libs/cli-0.1.0-SNAPSHOT.jar              7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994
cli/build/native-mcp/windows-x64/synesis-mcp.exe  4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17
```

The validation JVM was Temurin `25+36-LTS` at
`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot`. The only host
workaround was process-local:
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
The established `Selector.open()` and minimal `HttpServer` preflight passed.
No global Java, network, provider credential, or Codex configuration was
changed; supported provider install/uninstall only managed the provider
integration/manual and target-local hook, with
`UNRELATED_CONFIGURATION_PRESERVED=true` on cleanup.

## Run history and harness-only corrections

Runs #75–#77 passed fresh generation-1 managed lifecycle and exact-thread
capability handoff but exposed disposable-runner sequencing defects. Run #77
also showed B had already terminalized while the runner incorrectly required a
second B finish request. Run #78 corrected that predicate but its integrated
test exposed missing forward-transition validation. Run #79 exposed an
insertion-order contract gap. Run #80 exposed the distinction between
same-status field update and status transition. These were corrected only in
the disposable acceptance prompt/runner; production source was not patched.

## Final fresh run #81

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-81`

Project: `9a5d73ff-4261-43ba-9003-9d40bee6c4e8`

WorkGroup: `88d1871d-7de1-3ca3-b129-c36896538410`, final
`COMPLETED/version=2`, with `FINAL_INTENTS=[]`.

Worker A:

- Binding: `session-9a7d6a49-c4c0-4caa-93b4-aadde8c4df84`
- Participant: `agt_b49e7159-8d79-304a-880c-b37c298d0c81`
- Intent: `1c4fd4e4-4c8e-32f8-a24b-5a783cef368f`
- Claims: domain/persistence main and test subtrees only
- Thread: `01a077e3-5889-7d32-acd2-c0ace9c8b0ce`
- Final lifecycle: `COMPLETED/revision=11/generation=1`
- Snapshot/integration commit: `95377542acd9e1744698b4aea96607a41a8103e3`

Worker B:

- Binding: `session-7c33a76b-1ffa-4614-a9e7-0ffc1a0e00df`
- Participant: `agt_0fdc234f-e52d-359f-8569-e1b6d830fa88`
- Intent: `a00518d2-e672-37e0-9163-5abad632f5fb`
- Claims: application/API main and test subtrees only
- Thread: `01a077e1-6923-74c2-abc8-f7aba2ec9535`
- Final lifecycle: `COMPLETED/revision=20/generation=1`
- Snapshot/integration commit: `39a72692dd0dcb0ec03dd2e4529f4197ed54fe13`

Both `prepareFirst` operations were generation 1 and
`PENDING_ACTIVATION`, with digest-only proof evidence, unresolved provider
threads, and no ownership before START. Both START operations consumed the
same live launcher state. No second proof, generation, binding, bootstrap
App Server, A2, or Worker B substitute appeared.

B started first and completed its initial turn. After A published
`tasktracker.domain.persistence`, B woke through the provider-native path with
`sameThread=true`:

```text
initial B turn  = 01a077e1-6a78-7fa0-a42d-a82c329e8dd5
B wake turn     = 01a077eb-50b5-77f1-96ad-048481c5d91e
capability     = IMPLEMENTATION_AVAILABLE -> VALIDATED
```

B consumed the exact published capability, produced only its application/API
claims, and completed. A then completed its owner/integration path. Final
participants were both `COMPLETED`; no review grant or review cycle was
invented or consumed.

## Integrated target verification

The final target was clean after the immutable snapshots were integrated.
Changed paths were exactly the disjoint A and B claims. The integrated suite
passed all four executable tests:

```text
TaskApiTest passed
TaskApplicationServiceTest passed
TaskTest passed
TaskRepositoryTest passed
```

No out-of-claim source was changed by either worker. The target's final Git
state was clean on `main`; the legitimate mutation remains captured by the
immutable snapshot commits above.

## Provider durability and cleanup

Read-only Codex state database evidence in
`C:\Users\Liparakis\.codex\state_5.sqlite` contains rows for both exact
provider threads, each with its exact rollout path, provider `openai`, and
CLI `0.153.0`. The two rollout files exist under
`C:\Users\Liparakis\.codex\sessions\2026\09\06\` and contain the exact final
turn IDs with completed turn records. Synesis evidence independently records
trusted `turn_completed`/`turn/completed` events for both threads.

After evidence capture, the validation host closed normally. Both bindings
were observed `STOPPED` with `rootPid=-1` and
`terminalDiagnostic=managed_job_empty`; no replacement was invoked. Provider
uninstall succeeded and preserved unrelated configuration. No raw proof was
logged or persisted in the evidence, and no provider credential or auth file
was read.

## Classification

**PASS / COMPLETE.** SYN-049 full unattended two-worker acceptance is proven
for the bounded fixture. The next action is not another SYN-049 retry; any
A1 trusted-death → A2 exact-resume/replacement validation must be separately
authorized and separately bounded.
