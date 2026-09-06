# SYN-049 fresh unattended two-worker acceptance — runs #55–#58

Date: 2026-09-06  
Classification: **PARTIAL / ACTIVE**  
Production source changes in these runs: **none**

## Scope and provenance

The runs used fresh targets created from the clean SYN-049 fixture commit
`3f824dd` and did not reuse a historical lane, binding, participant, intent,
provider thread, worktree, or `.synesis` state. The source checkout remained at
`3f91c593a471b4e8e6f574cccf3cea8ab868a54c`, clean, with runtime source
provenance `a7697bbb5de83ced8b61b275056f9204e4467fbc`.

The hash-locked artifacts remained unchanged:

- workspace: `0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3`
- MCP: `029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f`
- CLI: `7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994`
- native MCP: `4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17`

The JDK25 validation host used the process-local property
`-Djdk.net.unixdomain.tmpdir=C:\\t\\synesis-loopback-probe`. Selector and
minimal HttpServer preflight remained passing. No global Java or network
setting was changed.

## Run #55

Fresh target: `SynesisTaskTrackerRealAcceptance-20260906-55`  
Project: `6e449fe9-ebad-4cbb-9f4f-23f2dd9e1de6`  
WorkGroup: `d574de5c-f188-3b04-83f7-dad0c15bd315`  
A binding/participant/intent:
`session-1c87c586-d0b8-4ccd-8475-ef058f4e05a3` /
`agt_f6b05649-4c50-3192-93c1-c8b783f60496` /
`54793fa1-dd48-36e9-b3fe-bf15a7488eb2`  
B binding/participant/intent:
`session-e193a5a6-46c2-4b16-8a24-ca1b1c2aa9eb` /
`agt_857ce96e-f16a-3929-a46a-303dbe111ac9` /
`d70b94e5-6083-350b-86c5-5ad91557e56b`

Run #55 passed fresh generation-1 same-process preparation/START, original
Thread-B provider-native wake, exact capability validation, and A/B finish
continuations. The harness then stopped with A `COMPLETED`, B `ACTIVE`, an
accepted A-to-B REVIEW request, and WorkGroup `ACTIVE`. The first correction
was that the harness required a stale A lifecycle revision before sending B's
post-review continuation.

## Run #56

Fresh target: `SynesisTaskTrackerRealAcceptance-20260906-56`  
Project: `a63c9c10-0943-4416-aae8-c3a1405267ef`  
WorkGroup: `69a5c047-97df-31a4-9919-e312c0cb5d1e`  
A binding/participant/intent:
`session-8b76fa0d-0801-4dd3-b447-33067a228c73` /
`agt_04aa9117-413e-3296-b1a9-763252e92458` /
`8f5bcbeb-4541-3383-b4e5-127830267ad6`  
B binding/participant/intent:
`session-50b98687-35f0-4005-b4d2-26516b6b9d12` /
`agt_f7b460d6-d2b6-337c-b1f0-cf3f31a05066` /
`c9308bfe-b7e2-3467-a851-0ea7d3a102d4`

Run #56 proved the corrected accepted-review gate: B's post-review
continuation was sent on the original Thread B and its lifecycle reached
`COMPLETED`. It nevertheless stopped with B `ACTIVE` and WorkGroup `ACTIVE`;
the final B worktree was clean and the control checkout contained only A's
immutable snapshot. The remaining failure was therefore not a stale review
gate: B's post-review provider turn did not produce downstream claimed work or
a publishable snapshot.

## Run #57

Fresh target: `SynesisTaskTrackerRealAcceptance-20260906-57`  
Project: `7ee87935-5d82-41d8-baa6-311fb00cc47f`  
WorkGroup: `091a6010-9394-3682-ac6d-4149d88cece5`  
A binding/participant/intent:
`session-a35e92cc-6953-445a-83e6-2fa5607ac598` /
`agt_cefd2b6d-4d84-3351-a5f8-ddc9c1ba9506` /
`ef3e847e-c4be-3676-be53-254285c5df6c`  
B binding/participant/intent:
`session-59934748-5516-4f1b-810e-88b8a8fa065e` /
`agt_56091e39-c13b-3bdd-8888-9612a9a15cc7` /
`f6ee6015-d59a-3846-9c9e-9e84b40a348c`

Both fresh bindings prepared as generation 1 `PENDING_ACTIVATION` with null
provider threads and no ownership, then START succeeded in the live launcher
process. Thread B was
`01a07616-6bd9-7e60-a208-55bc6468fc31`; Thread A was
`01a07617-07ee-7ac0-b2cd-2a13e7d521f0`. No raw proof was recorded; only proof
digests were observed.

The initial B turn and A turn completed, and one same-thread A continuation was
sent, but A produced no claimed file, capability request, or publication. B's
provider-native wake was therefore not observed. The bounded harness ended
with `B provider-native wake not observed`. Cleanup was normal and supported;
the target and both worker worktrees were clean.

The disposable harness was then tightened to require visible A and B claimed
files before a provider turn could be considered useful. No production source
was changed.

## Run #58

Fresh target: `SynesisTaskTrackerRealAcceptance-20260906-58`  
Project: `26fb3a84-e0f3-47a0-a76b-cd05e20cb623`  
WorkGroup: `d212eec4-03c4-37ad-a292-89f0ef34d28c`  
A binding/participant/intent:
`session-2b2eaa22-e32a-4bf4-ac4b-c92729eac4e3` /
`agt_34bd1e78-9066-32fa-9f67-2003ac23c119` /
`d0dcf920-973b-3683-97cc-2241d6db3b38`  
B binding/participant/intent:
`session-35a823ff-4e70-4e5b-ab6d-8ead073ea32e` /
`agt_273ef498-b7c2-30b2-8369-429a696fd842` /
`9c7fb449-76f1-3c80-a5f9-a6e5fa317f79`

Both bindings again prepared as generation 1 `PENDING_ACTIVATION` with null
provider threads and no ownership, and both same-process START operations
succeeded. Thread B was
`01a07635-7120-7fb0-bedf-0b3ff2da7799`; Thread A was
`01a07636-4eea-7512-a981-32121e899fd9`. The initial B turn, initial A turn,
and one A continuation all reported trusted lifecycle completion, but neither
worker worktree acquired a claimed file and no capability request appeared.

During the live run, no run-scoped `synesis-mcp.exe` child appeared for either
App Server. The run was stopped through the harness cleanup path as a
material provider-turn failure; provider uninstall then returned
`SUCCESS`, `MANAGED_HOOK_REMOVED=true`, and
`UNRELATED_CONFIGURATION_PRESERVED=true`. No replacement, A2, substitute B,
manual state mutation, credential access, or push occurred.

## Global hygiene and classification

The supported install/uninstall flow preserved `mcp.json` and `hooks.json`
byte-for-byte. `config.toml` is not byte-for-byte restored by that flow; the
post-run hash was
`921EC4C3265133B4B5421B2192A43F5737B505082A4A9F5C0B6FC60D4C2ED97E`. The
provider manual manifest remained at the current runtime version and was not
read for credentials. No raw managed proof was logged or persisted in this
evidence.

Runs #55–#58 remain **PARTIAL / ACTIVE**. SYN-049 is not complete. The exact
remaining blocker is a reproducible provider-turn/tool-admission boundary in
fresh runs #57–#58: trusted turn completion occurs, but no claimed mutation or
Synesis MCP capability request is observed, so B wake, downstream work,
review, integration, and WorkGroup terminalization cannot be lawfully reached.

The next action is a bounded read-only diagnosis of the run-scoped Codex
App-Server-to-Synesis-MCP admission/tool-visibility boundary before another
fresh target. Do not bypass MCP, copy worktrees, reuse a lane, invoke
replacement, start A2, start Worker B independently, or patch production
speculatively.
