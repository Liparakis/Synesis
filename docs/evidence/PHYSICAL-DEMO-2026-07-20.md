# Physical demo evidence — 2026-07-20

## Classification

`TWO_MACHINE_VERIFIED` for Scenario A normal operation only.

## Sanitized run record

- Topology: two independent computers on the same LAN; direct LAN candidate selected.
- Host A node ID: `sl1-6e230a410ed17d2d7a05ca9c0073e677c17d9b8189d1b51d0bde80b4228194c9`
- Host B node ID: `sl1-dbeb932c931390732944ef288744780ecbfa43db9c0e6c4f261af0cbcad11918`
- Host A candidates: `10`
- Host B candidates: `6`
- Compatible pairs: `8`
- Selected pair: `LAN/LAN/<redacted>/<redacted>`
- Session ID: `c5a97c9a-e91e-4392-9c56-75e1abca7f6e`
- Both sides authenticated the expected remote node.
- Both sides reported `CONTROL_READY=true` and `LIVENESS=LIVE`.
- Bounded cooperative request: `WORK_RESULT=OK`.
- Host A close: `REMOTE_REQUEST`; Host B close: `LOCAL_REQUEST`.
- Cleanup: `CLEANUP=true` on both sides.

## Claim boundary

This proves authenticated direct communication, expected-identity verification,
bounded LAN candidate selection, one bounded demo request/result, liveness,
graceful shutdown, and cleanup for this tested LAN topology. It does not prove
abrupt process-loss behavior or wrong-identity physical rejection; those remain
pending physical scenarios.

