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

## 2026-07-20 — local interface candidate scan

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Enumerate local interfaces for the physical demo using the existing provider.
- Expected result: Reach the live Ethernet adapter and advertise `192.168.1.100`.
- Observed result: `CANDIDATES=0` even though Windows reported Ethernet up with `192.168.1.100`.
- Command or evidence: Java interface enumeration showed many up/down virtual adapters; the provider stopped at the first down adapter.
- Root cause: `LocalInterfaceCandidateProvider` used `break` for a down interface, abandoning all later interfaces.
- Retry prohibition: Do not stop the whole scan on one down adapter; skip it and continue.
- Evidence required before retry: Targeted candidate tests and a direct gather check must return candidates on a host with live Ethernet.
- Next hypothesis: The corrected scan will produce usable same-family candidates; network reachability still requires a shared/direct topology.

## 2026-07-20 — transient full-suite test class loading

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Run the full strict clean check after restoring package metadata.
- Expected result: All checks and tests pass.
- Observed result: One full run failed in `NettyQuicLoopbackTest` with `NoClassDefFoundError` for its anonymous `$2` class; the focused test rerun and immediate full rerun passed.
- Command or evidence: `gradlew.bat clean check --dependency-verification=strict`; test XML under `link/build/test-results/test`.
- Root cause: Undetermined transient test-worker/class-loading failure; no reproduction on rerun.
- Retry prohibition: Do not change production or test code based on this single non-reproducible failure.
- Evidence required before retry: A reproducible failure or repeated test-worker logs.
- Next hypothesis: Treat as environmental/transient unless it recurs.

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
