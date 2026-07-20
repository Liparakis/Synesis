# Failed Attempts

No failed attempts recorded.

## 2026-07-20 — demo application stream classification

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Let the existing responder stream handler claim control before classifying the first frame on every new stream.
- Expected result: A second authenticated stream would reach the demo handler.
- Observed result: The application stream was rejected as `DUPLICATE_CONTROL_STREAM` and the client received `SLF1` instead of a demo result.
- Command or evidence: `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream` failed with demo-frame header `534c46310105`.
- Root cause: The responder claimed the shared control flag before checking whether the frame was an application frame.
- Retry prohibition: Do not restore claim-before-classification ordering.
- Evidence required before retry: N/A; fixed by reading the bounded frame first and routing non-control frames only after an authenticated session exists.
- Next hypothesis: Keep control ownership and application-stream routing separate at the first-frame classification boundary.

## Required entry format

- Date:
- Task ID:
- Attempted approach:
- Expected result:
- Observed result:
- Command or evidence:
- Root cause:
- Retry prohibition:
- Evidence required before retry:
- Next hypothesis:
