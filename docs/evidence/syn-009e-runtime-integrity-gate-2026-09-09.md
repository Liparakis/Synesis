# SYN-009E installed maximum runtime-integrity gate

Date: 2026-09-09

## Change

The existing versioned bootstrap boundary now carries the selected
`PROTECTION_PROFILE` into `current.json`. A stable launcher only invokes the
full installed `doctor --install-dir` verification when the active payload is
marked `maximum-release` (or when a legacy pointer is paired with that marker).
Developer and `protection-lite` launches keep their existing fast path.

The doctor path reuses the bootstrap payload manifest and immutable-version
verification. It does not scan mutable project or provider state, modify the
host, terminate unrelated processes, or create a second trust system.

## Verification

- `go test . -run '^TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate$' -count=1` — PASS
- `git diff --check -- bootstrap/main.go bootstrap/main_test.go` — PASS
- The focused test confirmed that a newly installed maximum marker is recorded
  in the active pointer and that the generated stable launcher contains the
  doctor gate.
- A current bootstrap binary installed the existing lite bundle after a
  temporary marker-only profile substitution with `--skip-path-update`; the
  installed stable launcher started successfully, refused a modified payload
  file with `ERROR=installed pointer invalid`, and started successfully after
  the file was restored and mutable `Link` state was added.

## Evidence boundary

This is implementation and generated-launcher contract evidence. It is not a
commercial maximum-artifact result: the runtime probe used the existing lite
bundle with a temporary marker only. A real maximum archive, installed
licensed protector output, and a disposable install run are still required to
prove the full commercial release boundary, protected loader behavior, and
mutable project/provider-state acceptance. The maximum profile therefore
remains `PARTIAL` and the commercial transformation rings remain blocked.
