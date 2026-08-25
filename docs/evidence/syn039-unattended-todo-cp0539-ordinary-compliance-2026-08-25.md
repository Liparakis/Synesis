# SYN-039 CP-0539 ordinary acceptance compliance boundary

Date: 2026-08-25

## Harness

Fresh disposable project and harness:

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0539-2026-08-25-001`
- Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0539-2026-08-25-001\logs`
- Project ID: `90e72ce7-7f62-44b4-a91d-a2f6de33ed26`
- MCP: repository-built bundled `synesis-mcp.exe`, 10 tools, project-pinned
- Agents: two independent GPT-5.6 Luna Codex sessions, ready/isolated, no
  relay or manual lifecycle mutation

## Reached state

| Item | Value |
|---|---|
| WorkGroup | `53906f49-5d99-3726-ac2d-b155af973a7e` (`ACTIVE`) |
| Todo participant / intent | `agt_ab41f497-84de-3fab-8169-3d07929a1055` / `be50e01c-dd9e-3a0b-bd13-34af1ca285de` |
| Test participant / intent | `agt_95e7d0e5-90e0-3c4f-aa4b-5684da74fe8f` / `670f985c-861c-3176-b9aa-e9e71b26c352` |
| Claims | `PATH_EXACT todo.py` and `PATH_EXACT test_todo.py`, epoch 1 |
| REVIEW requests | `b19b65a5-8eca-4a91-997d-64f2ba62c136`, `ad0d9f60-7ab7-44de-b673-ae0721f10955`, both `ACCEPTED` |
| Grants | `6d31b2ce-9d21-3316-af55-a8fcd609c1fa` to test participant; `b0c2fa3f-dd43-3c72-a641-4c8e3bf71da5` to Todo participant |
| Snapshot | `snap_686a822915f6f230c059ddb5040fab32`, Todo implementation, integrated |
| Validation | structured `ACCEPTED` for that snapshot |
| Control checkout | integration reached; final WorkGroup closure not reached |

The completed Todo lane projected `WAIT` for its pending outgoing REVIEW
request instead of terminal `COMPLETED`, proving the CP-0538 fix.

## Agent-compliance evidence

The test participant received a concrete grant-consumption projection whose
exact arguments included:

```json
{"kind":"work_group_join","payload":{"grantId":"6d31b2ce-9d21-3316-af55-a8fcd609c1fa","intentId":"be50e01c-dd9e-3a0b-bd13-34af1ca285de","claimEpoch":1,"workGroupId":"53906f49-5d99-3726-ac2d-b155af973a7e","targetParticipant":"agt_95e7d0e5-90e0-3c4f-aa4b-5684da74fe8f"}}
```

Its first call omitted `targetParticipant` and correctly failed closed with
`COORDINATION_FIELD_REQUIRED:targetParticipant`. The next call used the
unchanged projected arguments and returned `CONSUMED`. This is agent
non-compliance, not a production projection defect.

After the valid review and structured ACCEPT, the agent received repeated
`WAIT -> get_next_action({})` projections for the reciprocal grant. It stopped
without executing that continuation. The other agent likewise ended with the
WorkGroup still active. At no point did an unchanged executable projection
fail, and no required state lacked a usable projection.

## Diagnostics and verification

Fixture Doctor was `DEGRADED` with 6 warnings, 0 errors, 0 critical findings,
`CLEANUP_RECOMMENDED=false`, `RECONCILIATION_RECOMMENDED=true`,
`REPAIR_AVAILABLE=true`, and `NEXT_ACTION=prepare_repair_plan`.

The known Git subprocess stall, bootstrap migration failures, and Doctor
warnings remain separately classified. The next implementation slice is not
justified by this run; the next action is another fresh ordinary acceptance
or an exact-action diagnostic if a later run proves an unchanged protocol
failure.
