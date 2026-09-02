# ADR-0054: Preserve structured capability dependencies through admission

Status: Accepted for SYN-049.

## Context

The current source already contains the intended dependency path:

```text
McpProtocolHandler.parseTaskIntent
→ MCP admission
→ WorkspaceCollaborationService.announce
→ WorkIntent.knownDependencies
→ WORK_INTENT_ANNOUNCED
→ CollaborationCodec replay
→ AgentNextActionService / CapabilityRequestProjection
→ NEEDS_CAPABILITY
→ request_coordination capability_request
→ CapabilityRequestService
```

The previous two-worker fixture did not show an actionable dependency edge,
but it may have used an older installed distribution or failed to exercise the
current MCP path. No active source-level drop is proven. `knownDependencies`
contains capability/dependency identifiers, not complete capability contracts.

## Decision

Before changing dependency production code, rebuild and install the current
provider/MCP distribution, record source and artifact hashes, and run a fresh
MCP-path regression. The regression must prove parsing, admission, WorkIntent
construction, durable event encoding, restart reconstruction, and the existing
`NEEDS_CAPABILITY` projection.

If the regression passes, no duplicate forwarding or new dependency system is
added; the old fixture is classified as stale/provenance-mismatched evidence.
If it fails, patch only the first concrete failing boundary in the existing
path. Admission never fabricates a `CapabilityContract` or capability request.
The requester uses the existing typed capability-request lifecycle, and
provider/owner routing remains based on the current capability semantics rather
than participant identity. Dependencies remain authoritative only when supplied
as structured admission data; prose, files, imports, mutations, and participant
identity do not imply a dependency.

## Consequences

The durable coordination model stays a capability relationship. Existing
request, implementation, publication, review, integration, and requester
continuation behavior is reused. Independent lanes remain independent, and
malformed dependency input continues to fail closed under existing bounds.
Artifact provenance becomes a permanent acceptance requirement for MCP
behavior, preventing stale installed bundles from being mistaken for source
regressions.

## Verification

Focused tests and one fresh real task-tracker acceptance must demonstrate
dependency survival through the real rebuilt MCP distribution, actionable
`NEEDS_CAPABILITY`, lawful capability request and satisfaction, durable
publication/review/integration, and requester continuation. No production
dependency edit is justified without a failing boundary and a regression.
