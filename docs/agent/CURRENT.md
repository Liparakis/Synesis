# Current Task

## Identity

- Task ID: SYN-013D
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0178
- Latest checkpoint: CP-0179
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029, ADR-0030

## Objective

Implement Synesis Stage 2B Slice 1: Durable Capability Negotiation & Request State Machine.

## Immediate slice

Stage 2B Slice 1 complete at CP-0179: Capability handle generation (`req_<random_token>`), binary payload codec, capability request projection and replay, application services (`CapabilityRequestService`, `CapabilityResponseService`), MCP tool handlers (`synesis.describe_required_capability`, `synesis.respond_to_owner_request`), and `get_next_action` projections.

## Evidence ledger

- VERIFIED: Durable handle domain types (`CapabilityRequestHandle`, `CapabilityRequestHandleGenerator`, `SecureRandomCapabilityRequestHandleGenerator`) created and unit tested in `:coordination`.
- VERIFIED: Bounded contract validation record (`CapabilityContract`) and binary event payload codec (`CapabilityRequestPayload`) created and tested in `:coordination`.
- VERIFIED: `CapabilityRequestProjection` integrated into `PredictionEventStore` replay and event append validation.
- VERIFIED: `CapabilityRequestService` and `CapabilityResponseService` created in `:workspace` handling requester initial description, owner accept/revise/reject responses, and requester counter/accept/cancel responses.
- VERIFIED: `AgentNextActionService` updated to project high-priority `RESPOND_TO_OWNER_REQUEST` and `REVISE_CAPABILITY_REQUEST` next actions for owner and requester node IDs.
- VERIFIED: MCP protocol handler registered tools `synesis.describe_required_capability` and `synesis.respond_to_owner_request` (total 7 tools in `tools/list`).
- VERIFIED: Unit and multi-process stdio negotiation tests (`CapabilityRequestHandleTest`, `CapabilityContractTest`, `CapabilityProjectionRestartTest`, `CapabilityNegotiationTest`, `McpStage2BSlice1Test`, `TwoProcessCapabilityNegotiationProcessTest`) pass cleanly without path leaks.
- VERIFIED: Full repository build verification `.\gradlew.bat check --no-daemon` passes cleanly (49 actionable tasks).

## Current limitations

- Stage 2B Slice 2 (Implementation publication, validation worktrees, safe integration) is deferred.

## Verification target

`.\gradlew.bat check --no-daemon` (49 tasks).

## Immediate next action

Awaiting next slice directive or instructions for Stage 2B Slice 2 implementation.
