# ADR-0014: Workspace bootstrap for the first two-person demo

## Status

ACCEPTED — planning and CP-W1 implementation boundary, 2026-07-21.

## Context

CP-0075 verifies signed decision storage and the bounded local searchable view.
The existing `:cli` can bootstrap Link identities and run fixed demo onboarding,
but it cannot create a project configuration or a signed decision. The
project-record APIs can do both, but only from tests or hand-written code.

The first usable demo therefore needs a small operator-facing composition layer.
It must not duplicate Link or project-record authority and must not start the
next networking slice.

## Decision

Add a JDK-only `:workspace` application with the generated
`synesis-workspace` launcher. It owns only bounded argument parsing, profile
layout, orchestration of existing APIs, and safe stable output.

Each profile is rooted at one operator-selected directory:

```text
<profile>/link/          Link identity and private key
<profile>/project.conf   existing ProjectConfig format
<profile>/records/       existing DecisionStore format
```

CP-W1 exposes only identity inspection, one-peer project creation, and signed
revision-1 decision creation. It creates no network session and changes no
wire format. Project creation refuses an existing configuration and never
silently replaces a mismatched project or peer. Decision creation requires
exactly one bounded evidence reference and SHA-256 digest.

## Ownership and invariants

- Link owns identity persistence and cryptographic identity.
- `:project-record` owns configuration validation, canonical decision bytes,
  signatures, and durable record storage.
- `:workspace` owns no new durable schema; it composes those owners.
- Profile paths are bounded, local, and never printed in normal output.
- A successful project create emits one stable `PROJECT_ID` and `NODE_ID`.
- A successful decision create emits revision `1`, a stable `RECORD_ID`, and
  the canonical `DIGEST`.
- Existing state is never overwritten by a retry or mismatch.

## Alternatives rejected

- Extending `:cli`: rejected because the standalone CLI is frozen.
- Adding commands to `:project-record`: rejected because its storage and
  inspection boundary is frozen and it should not own operator bootstrap.
- Manual configuration or scripts only: rejected because the demo would not
  be reproducible or safe for a second operator.
- New transport or protocol work: rejected; CP-W2 is a separate task.

## Non-claims and invalidation

This decision does not provide onboarding, sync, membership, multi-project
profiles, background behavior, physical two-machine evidence, or production
packaging. Reopen it only if existing public APIs cannot support CP-W1 without
changing Link or project-record production code; that blocker must be recorded
before any exception.
