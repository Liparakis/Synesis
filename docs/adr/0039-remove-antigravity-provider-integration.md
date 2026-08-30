# ADR-0039: Remove the Antigravity provider integration

Status: Accepted
Date: 2026-08-30

## Context

Antigravity was still present in the supported-provider registry and in
provider installation, project initialization, MCP workspace discovery,
migration, hook, CLI, and test paths. That left an integration surface that
was no longer part of the intended local provider boundary.

## Decision

Remove the Antigravity integration completely from active product behavior.
The supported provider set is now Claude and Codex. Remove the Antigravity
provider and hook adapters, CLI command, MCP discovery and environment
fallbacks, provider migration and initialization branches, dedicated script,
and active documentation and tests. Do not add a compatibility alias or
continue mutating Antigravity configuration.

Historical ADRs, validation reports, checkpoints, and evidence that describe
past Antigravity work remain unchanged as audit history. They are not active
provider support.

## Consequences

- Provider listing and lifecycle operations expose only Claude and Codex.
- Existing Antigravity configuration is no longer discovered, migrated, or
  modified by Synesis.
- Historical records retain their original claims and terminology, while
  current provider documentation describes only the remaining integrations.
