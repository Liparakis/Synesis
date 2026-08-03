# SYN-038 Codex App Server schema evidence

The installed local Codex CLI reports `codex-cli 0.145.0`. The schema used by
the lifecycle client was generated locally with:

```text
codex app-server generate-json-schema --experimental --out build/codex-schema-038
```

The checked-in `CodexAppServerProtocolSchema` projection is intentionally
Codex-only and follows that generated schema for the lifecycle subset. The
verified protocol facts used by the implementation are:

- `initialize` requires `clientInfo` and accepts `capabilities`.
- `initialized` is a client notification and is sent after the initialize
  response.
- `thread/start` returns a nested `thread.id` and emits a nested
  `thread/started` notification.
- `turn/start` returns a nested `turn.id` and emits `turn/started` with
  `threadId` plus a nested turn.
- `turn/steer` requires `expectedTurnId`, `threadId`, and bounded `input`.
- `turn/interrupt` requires `threadId` and `turnId`.
- `turn/completed` carries the terminal turn status and exact thread identity.
- Generated `RequestId` accepts a nonblank string or integral int64; the
  client preserves that type when echoing server requests.

A read-only initialize smoke run against the installed process returned a
valid initialize response and a server notification. Full real-owner evidence
is recorded separately in
`docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.
