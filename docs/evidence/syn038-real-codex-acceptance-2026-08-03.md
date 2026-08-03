# SYN-038 real-Codex acceptance status

Status: partial acceptance. The complete evidence, exact identities, owner
records, control outcomes, duplicate replay, interaction-required boundary,
and remaining unproven gates are recorded in
[syn038-real-codex-app-server-acceptance-2026-08-03.md](syn038-real-codex-app-server-acceptance-2026-08-03.md).

The production owner, deterministic lifecycle fixtures, real START, WAIT
control, STEER, INTERRUPT, passive exact-thread resume, explicit continuation,
and duplicate STATUS/WAIT replay are evidenced. MCP command cancellation and
Codex-driven MCP command cancellation remains unproven because Codex 0.145.0
required an explicit MCP elicitation approval and SYN-038 correctly fails
closed rather than guessing approval. The existing ten-tool Synesis MCP path
was separately exercised after attachment stop: it validated the fixture,
published a snapshot, and completed `finish_lane`; that is not attributed to
Codex and does not replace the missing command-cancellation evidence.

The sequential repository gate `./gradlew.bat check --no-daemon
--max-workers=1 --console=plain` also completed with `BUILD SUCCESSFUL` after
the long Git-heavy fixtures; the full module reports contain zero test
failures/errors. This does not change the partial real-Codex classification.
