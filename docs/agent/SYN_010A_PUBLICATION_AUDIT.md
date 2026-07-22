# SYN-010A public GitHub publication audit

## Starting state

- Starting checkpoint: CP-0110.
- Starting commit: `5a80fed898736a203310429cb98f09791c9f5b3b`.
- Starting branch: `master`; the working tree was clean at task start.
- `master` was 42 commits ahead of `origin/master`; the remote was not ahead.
- The configured remote is an older `synesis-link` URL; authenticated GitHub
  metadata identifies the canonical private repository as `Liparakis/Synesis`
  with default branch `master`.
- No push, visibility change, release, tag, or external announcement was made.

## License gate

The owner supplied an intentional license decision: GNU Affero General Public
License v3.0 only (`AGPL-3.0-only`). `LICENSE` now contains the canonical GNU
license text with the SPDX identifier; see
[`docs/legal/LICENSE_DECISION_REQUIRED.md`](../legal/LICENSE_DECISION_REQUIRED.md).
The unfinished-license gate is cleared.

## Secret and privacy scan

Dedicated `gitleaks`, `trufflehog`, `git-secrets`, and `detect-secrets`
executables were unavailable. An equivalent focused scan covered the current
tree, reachable Git history, tracked paths, and GitHub-facing configuration:

- No GitHub tokens, cloud access keys, private-key blocks, OAuth tokens, API-key
  assignments, or credential files were found.
- The only password-pattern match is an intentional interactive TLS keystore
  prompt in `link/src/main/java/org/synesis/link/transport/DemoCli.java`.
- Secret-looking environment variable names in workflow/bootstrap code are
  names only; no values are present.
- No `.env`, private-key, credential, local-state, generated-hook, transcript,
  or prompt files are tracked.
- No personal absolute machine paths were found in source or documentation.
- Commit metadata contains a personal-looking author email. It is not a source
  credential, but public history would expose it. No history rewrite or
  anonymization was authorized.

The scan covered reachable history. Unreachable local Git objects were observed
by `git fsck`; they are not part of the intended push and were not rewritten or
deleted.

## Preparation changes

- `.gitignore` now covers nested build output, distribution/release output,
  Synesis local state, and generated provider/agent hooks.
- README, security reporting guidance, and contribution guidance now describe
  the developer-preview status and avoid open-source or production claims.
- No release artifacts or production signing assets were added.

## Workflow review

`.github/workflows/release.yml` was read end-to-end. It does not use
`pull_request_target`, does not grant write permissions, does not create a
GitHub Release automatically, and scopes the signing secret to the protected
tag-signing step. Actions currently use mutable major-version tags, so pinning
to immutable action SHAs remains a prepublication hardening item. The reported
Ubuntu failure was caused by `gradlew` being tracked as mode `100644`; it is now
tracked as `100755`. Git Bash executed `./gradlew check
--dependency-verification=strict` successfully after the mode fix.

## Verification

- `gradlew.bat check --dependency-verification=strict`: PASS; `BUILD
  SUCCESSFUL`.
- Go 1.26.5 `bootstrap` test and vet: PASS. The current Codex process PATH did
  not include the newly installed tool, so verification invoked
  `C:\Program Files\Go\bin\go.exe` directly; no Go source changed.
- `agent-resume.ps1`, `agent-doctor.ps1`, `agent-validate-fixtures.ps1`, and
  `agent-validate-deferred.ps1`: PASS.
- `git diff --check`, README link checks, ignore-rule checks, current-tree and
  reachable-history secret scans, and workflow security checks: PASS.
- Linux-wrapper mode correction: `gradlew` `100644` -> `100755`; wrapper
  version smoke and full strict check under Git Bash: PASS.
- Linux Netty QUIC verification: added the Maven Central SHA-256 for
  `netty-codec-native-quic-4.2.16.Final-linux-x86_64.jar`.
- Cross-platform CLI test launch: Windows continues to use `cmd.exe` and
  `synesis.bat`; Unix uses the generated `synesis` launcher directly. Native
  Windows strict clean check: PASS. Linux execution remains for CI validation.

## Publication decision

```text
PUBLICATION_STATUS=NOT_PUBLISHED
REASON=EXPLICIT_PUSH_AUTHORIZATION_REQUIRED
SECONDARY_REVIEW=PERSONAL_COMMIT_METADATA_AND_REMOTE_TARGET
```

Before any public push, the owner must decide whether existing author metadata
may remain public, confirm the target repository/default branch, rerun the
scans, and review the final GitHub metadata. No push is authorized by this
audit.
