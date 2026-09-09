# SYN-049 fresh unattended two-worker acceptance — run #54

Date: 2026-09-06
Classification: **PARTIAL**

## Provenance and host

The source checkout was clean at `f1ac32e5d7dfb4dd02307fdc2309d21b427571ca`.
The production runtime source provenance remained
`a7697bbb5de83ced8b61b275056f9204e4467fbc`. The current JDK25 host was
Temurin `25+36-LTS` at
`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot`.

Installed and build artifacts were hash-equal before the run:

- workspace: `0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3`
- MCP: `029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f`
- CLI: `7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994`
- native MCP: `4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17`

Selector and minimal HttpServer preflight passed with only the process-local
property `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
The property was not persisted or added to Synesis configuration.

## Fresh lawful fixture

The target was a new `main` checkout at final immutable lane snapshot
`aec2608e5dc3508a5fdf74f69be524d07ce2b4f6`, with a clean control checkout.
No historical lane, old provider binding, copied state, substitute worker,
replacement, or manual `.synesis` edit was used.

Project: `487bd72c-21f9-4ba5-b819-84cd8d9d871d`
WorkGroup: `e390a2d9-56c7-3b92-8c98-0d22a7c41ec8`

Worker A:

- binding `session-c3a67b7e-84c5-478a-ae5b-f0921b26504f`
- participant `agt_bb795d72-d4b9-348c-883b-d5981ef0386a`
- WorkIntent `85006b86-3b38-3931-b285-e10494dd7910`
- provider Thread A `01a075ad-0c0c-7981-a769-c2e7698fad9f`
- claims: `src/main/java/tasktracker/domain`, `src/main/java/tasktracker/persistence`, and their
  focused tests
- proof digest only: `83a5bd7242112ea065e86d55bbfe808df61c3f9f5039566246d4094166239a1b`

Worker B:

- binding `session-141e0d2e-ef8a-4e84-b813-6c01bfb8c942`
- participant `agt_e93fc4b9-5311-3b7d-8af6-35f56684925d`
- WorkIntent `7914887a-239f-346e-867b-93a462c9d998`
- provider Thread B `01a075ac-8c83-7d63-ae49-a8b1a89dabfb`
- claims: `src/main/java/tasktracker/application`, `src/main/java/tasktracker/api`, and their
  focused tests
- proof digest only: `18795ddd3a157cf71f6d8d31c11c97a91f65def09a83a0d5a97b51c9b211aa577`

## Runtime and collaboration evidence

One live validation JVM carried both production launchers through generation-1
`prepareFirst → START`. Both bindings were `PENDING_ACTIVATION` with
unresolved provider threads and no ownership before START, then reached
ACTIVE through the same-App-Server production path. No second generation or
second proof was created.

Both workers completed real provider turns on their exact original threads.
B reached `NEEDS_CAPABILITY`; A implemented and published the exact
`tasktracker.domain.persistence` capability. B then consumed the capability
on the original Thread B. The provider-native wake was observed with
`sameThread=true`, initial turn
`01a075ac-8d68-77f2-b043-d4914121591e`, wake turn
`01a075b1-8ccc-71b3-9792-8da42a3e26b4`, revision `10`.

The capability state advanced through `AWAITING_OWNER → ACCEPTED →
IMPLEMENTATION_AVAILABLE → VALIDATED`. A and B performed real claimed work;
the A worktree contains only domain/persistence changes and the B worktree
contains only application/API changes. No out-of-claim mutation was observed.

The bounded harness then reached the final coordination boundary. Its
supported status at timeout was:

- A participant `COMPLETED`
- B participant `ACTIVE`
- review request `3aa7ebd6-54e2-4596-8104-c6fed268199d`, A → B, `ACCEPTED`, kind `REVIEW`
- WorkGroup `ACTIVE`
- coordination status `PASS`

The harness exited with `SYN-049 workgroup terminalization timeout`. It did
not prove B's final review/finish projection, B lane terminalization, or
WorkGroup terminalization. The run-specific parent and managed worker
processes then exited normally; no replacement or manual lifecycle action
was invoked.

## Cleanup and configuration hygiene

Provider uninstall succeeded through the supported command and reported
`MANAGED_HOOK_REMOVED=true` and `UNRELATED_CONFIGURATION_PRESERVED=true`.
Credentials were never read. Raw managed proofs were never logged or
persisted; only digests above were recorded.

The process-local AF_UNIX property was not propagated globally. The Codex
`mcp.json` and `hooks.json` snapshots remained equal to their known
pre-run values. The supported install/uninstall flow did not restore
`config.toml` byte-for-byte: its pre-run hash was
`67380dfd26e56c3f71cf3f89cd977664e80057c7e4f748c9980900e4dbf7ca43` and its
post-run hash was
`442f654cb693dee1c26a02e59b4a509d0d0dfc5e4c92b0c7df05f102bc05e85d`.
This is recorded as configuration drift, not silently classified as clean
restoration.

SYN-049 remains **ACTIVE / PARTIAL**. SYN-051 remains **COMPLETE / PASS-A**.
No push was performed.
