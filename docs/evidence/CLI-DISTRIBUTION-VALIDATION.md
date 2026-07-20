# CLI development distribution validation

## Status

`TWO_PROCESS_VERIFIED`; physical generated-launcher validation remains
unclaimed.

## Build and launcher evidence

- `./gradlew.bat clean check --dependency-verification=strict` — PASS.
- `./gradlew.bat :cli:installDist --dependency-verification=strict` — PASS.
- Generated `synesis.bat` and `synesis` launchers exist — PASS.
- Generated `--help`, `--version`, `identity show`, and `doctor` — PASS with
  exit code `0`.
- Generated two-profile host/join process test — PASS with exit code `0` on
  both processes; invitation handoff, `CONTROL_READY=true`, `LIVENESS=LIVE`,
  `WORK_RESULT=OK`, and `SESSION_CLOSED` were asserted.
- `:link:dependencies` contains no Picocli — PASS.

The process test captures the invitation only in memory and does not include
the full link in failure output.

## Local failure and lifecycle evidence

- A generated-launcher `doctor` run against a corrupt `identity.bin` returned
  exit `10`, emitted `IDENTITY=FAIL` and `DOCTOR=FAIL`, and did not repair the
  profile.
- A generated-launcher `join invalid-link` run returned exit `11` and emitted
  `FAILURE=INVITE_INVALID`; the human explanation was redacted and written to
  stderr.
- The generated host/join test was rerun as a fresh second session after the
  first graceful close — PASS. This proves repeatable new-session startup, not
  transparent reconnect. Transparent reconnect remains deferred in the
  repository.
- Link-level `NettyQuicLoopbackTest.reportsAbruptProcessLossWithDocumentedTerminalCategory`
  — PASS; terminal classification was `TRANSPORT_CLOSED` or
  `LIVENESS_EXPIRED`.
- Link-level `NettyQuicLoopbackTest.rejectsWrongIdentityAndIncompatibleVersionBeforeSessionExposure`
  — PASS; wrong identity was rejected before session exposure.
- A generated-launcher early-kill attempt terminated the join after
  `INVITE_VERIFIED`, but the host did not reach a terminal status within the
  30-second bounded observation window. It is not claimed as generated CLI
  abrupt-loss evidence. No protocol behavior was changed to force this case.

The local scenarios use two processes and isolated temporary profiles on one
Windows machine. They do not claim physical two-machine generated-launcher
validation.

## Boundary

This validates the development distribution and local launcher behavior. It
does not claim physical generated-launcher onboarding, production installation,
signing, permanent PATH changes, MSI, native image, or package publishing.
