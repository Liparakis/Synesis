# SYN-048 unpinned linked-root admission evidence

Date: 2026-09-02

## Defect reproduced

The fresh SYN-047 fixture at
`C:\Users\Liparakis\Documents\Codex\2026-09-02\kogmawcollabsmoke` initialized
Git and Synesis and installed the Codex integration in its control checkout.
Both worker MCP connections nevertheless reported
`provider_integration_required` / `NOT_INSTALLED`. Their advertised project
root was an ordinary linked Git worktree that did not contain the control
checkout's ignored provider metadata.

## Bounded correction

Unpinned MCP root discovery now resolves an advertised linked worktree through
the read-only `git worktree list --porcelain` projection. The resolver accepts
only the unique main checkout whose initialized Synesis project ID and Git
common directory match the advertised root. Normal checkouts remain direct;
ambiguous, unrelated, assigned, or unverifiable roots remain rejected or
unresolved. Provider admission and the ten-tool MCP catalog are unchanged.

No `.synesis/local` state, provider metadata, hooks, leases, claims, or
protocol identifiers were copied or manually edited.

## Verification

Passing focused regression:

```text
:mcp:test --tests org.synesis.mcp.application.CodexLinkedWorktreeRootSelectionRegressionTest.unpinnedLinkedWorktreeResolvesToMainControlRootBeforeProviderAdmission
```

The focused regression proves that the unpinned linked root resolves to the
main control checkout and that session admission reaches `ready` with an
isolated workspace. The existing guard tests for explicit linked-root
authority, missing provider integration, and legacy unambiguous discovery also
pass:

```text
:mcp:test --tests org.synesis.mcp.application.CodexLinkedWorktreeRootSelectionRegressionTest.linkedWorktreeCannotReplacePinnedControlRootDuringMcpInitialize --tests org.synesis.mcp.application.CodexLinkedWorktreeRootSelectionRegressionTest.missingIntegrationIsStillEvaluatedAtPinnedControlRoot --tests org.synesis.mcp.application.CodexLinkedWorktreeRootSelectionRegressionTest.absentExplicitPinRetainsLegacyUnambiguousDiscovery
```

The local distribution was rebuilt successfully with
`:cli:installDist --dependency-verification=strict`. The complete MCP test
class was started separately but exceeded the bounded wait and was terminated;
that run is incomplete evidence, not a passing result.

## Remaining acceptance

SYN-047 must still run once on a new fresh fixture using this rebuilt
distribution. The original KogMaw project and the blocked SYN-047 fixture are
preserved and were not repaired, reset, or otherwise modified.
