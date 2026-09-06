# SYN-049 fresh unattended two-worker acceptance — runs #68–#69

Date: 2026-09-06  
Classification: **PARTIAL / ACTIVE**  
Production source changes: **none**  
Push: **none**

## Scope and host boundary

These were fresh external acceptance targets. No historical lane, `.synesis`
state, provider credential, A2, replacement, substitute worker, or manual
state/worktree merge was reused. The disposable validation runner used one
live JVM and one production `ManagedCodexProcessLauncher`/`ProjectRuntimeHost`
across both `prepareFirst` and `START` for each worker.

The established host boundary remained valid: Temurin OpenJDK
`25+36-LTS` at
`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
with only the process-local property
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
Selector/HttpServer preflight had already passed on this host. The property
was not persisted globally or added to Synesis configuration.

The installed artifacts were not rebuilt. Hashes verified before the runs:

```text
workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar
0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3
mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar
029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f
cli/build/libs/cli-0.1.0-SNAPSHOT.jar
7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994
cli/build/native-mcp/windows-x64/synesis-mcp.exe
4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17
```

## Run #68

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-68`  
Project: `82df5b28-14e6-4a82-b9f7-5f36ed26e801`  
WorkGroup: `18eaa79f-b55d-3b4d-997a-1d1ae411345b`

Fresh A binding/participant/intent:

```text
session-a735f04b-1a1a-44cc-9420-dfbe9eab7e60
agt_c221db98-c1b4-3404-b578-7a2f52e97c21
b84e1960-d2c4-3b65-b0dc-8d8bf0abc5e6
```

Fresh B binding/participant/intent:

```text
session-068b0d10-7c9a-4539-b7fd-3e57eecae43f
agt_ce67724a-f4c4-3da3-95a4-420b4d430092
9f418923-8c58-3c07-9757-7afa2908c542
```

Claims were disjoint: A owned the domain/persistence main and test subtrees;
B owned the application/API main and test subtrees. The structured dependency
was `tasktracker.domain.persistence`.

Both bindings prepared as generation 1 `PENDING_ACTIVATION` with unresolved
provider threads and no ownership. Proofs were retained only in the live
launcher; only these digests were recorded:

```text
A 6b9cceb556e3553def39e304a22ece0022d13bd5db5ba063149d5151f34ca0284
B ec247214987f157929517f48722cadfaf50de4315371d1278a901cf021006ae30
```

B reached `ACCEPTED`, woke on the original provider thread, and reached
`VALIDATED`. A and B performed real claim-scoped implementation turns. B
published an immutable integrated snapshot; A's review/terminal path did not
complete. The bounded runner stopped with WorkGroup terminalization timeout.

Independent integrated compile of the target failed because A's domain and
persistence snapshot was absent from the control checkout; this was not
treated as acceptance success.

## Run #69

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-69`  
Project: `e0c72166-c84a-4eab-9ec9-1b83cd02941d`  
WorkGroup: `3accd81a-4696-3b27-b1b2-3bebbb474078`

The target was initialized from a new fixture commit and then used only for
this run. Fresh A binding/participant/intent:

```text
session-7bedd4be-6d48-4955-bd86-f68032802acc
agt_4edd64b0-1d12-31fc-96a8-eafb372d4b29
fc3a852b-96ec-3ed8-8d2f-54fcfb326139
```

Fresh B binding/participant/intent:

```text
session-adf1b3a0-6106-4d3a-8954-34146e0c7e7a
agt_918ef6b1-8003-3c97-95c8-3040865dd19f
0aae6f37-d9fe-3dd0-9118-6bd32f72744c
```

Claims and dependency were the same disjoint A/B contract as run #68.
Both `prepareFirst` calls returned generation 1 `PENDING_ACTIVATION` with
null provider thread and absent ownership. Recorded proof digests only:

```text
A 0d117a90fc3e44b5c8d4b0d4dc27690c332e33e0c20ff35ad12da7484802efae
B e1f3142536bd7bd55a0f3bd4ec1873411dbe3beb491646405422f707c1c4e31e
```

B START created Thread B
`01a07709-35e7-7d81-8767-8345526d3b35`; its initial trusted turn was
`01a07709-36ef-71d1-a0ec-6cda24140d28`. A START created Thread A
`01a0770a-1414-79b0-b83d-93e217058466`; its initial trusted turn was
`01a0770a-14cc-7182-9c8c-f39b0e0b7073`.

The capability progressed through `ACCEPTED`, provider-native B wake with
`sameThread=true`, and `VALIDATED`. B then produced a real claim-scoped
application/API implementation and integrated snapshot:

```text
snapshot=snap_3cf85ce7680cfa44a561504cc13f359d
snapshotState=INTEGRATED
commit=b90bac5e59560c3d734f33a05e0d5777f129be5d
```

The final durable projection was:

```text
WorkGroup=ACTIVE
A participant=ACTIVE; A intent=ANNOUNCED; no A snapshot
B participant=COMPLETED; B snapshot=INTEGRATED
review request=B -> A=ACCEPTED
review grant target=B=available/unconsumed
review validations=none
active intent=A
```

The exact boundary is an owner/reviewer ordering cycle. B's completion path
requested review of A before A could publish its own snapshot. Synesis then
correctly withheld A's completion projection because A was review-obligated;
B remained unable to consume its review grant because A's immutable snapshot
did not exist. The runner ended at its explicit 30-minute
`SYN-049 workgroup terminalization timeout`. No lifecycle action was invented
and no durable state was edited.

Independent compile of the integrated control checkout failed with missing
`tasktracker.domain` and `tasktracker.persistence` types. The checkout
contained only B's integrated application/API snapshot, so no test execution
was attempted or claimed.

## Cleanup and security

The validation host closed normally after each timeout. Supported
`provider uninstall --project ... codex` succeeded for both targets. No
intentional App Server death, replacement, A2, Worker-B substitute, or
cross-process resume was invoked; no incidental `DeathEvidence` was found.
The target worktrees showed changes only under their exact A or B claims.
No raw proof was logged or persisted by the runner, and no provider
credentials were read or copied. The global Codex configuration retained only
the target trust entry after supported cleanup; no `mcp_servers.synesis` or
native MCP command entry remained. No production source was changed.

## Classification

**PARTIAL / ACTIVE.** Generation-1 managed startup, capability publication
and validation, same-thread B wake, real claim-scoped implementation, and one
integrated B snapshot are proven. The required A snapshot, B review
validation, complete integrated checkout compile/tests, both terminal lanes,
and WorkGroup `COMPLETED` remain unproven.

Next action: diagnose the owner/reviewer snapshot ordering projection read-only
before another fresh target. Do not alter `.synesis`, copy snapshots manually,
force completion, invoke replacement/A2, or patch production without a new
root-cause decision.
