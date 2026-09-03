# SYN-051 provider-authentication compatibility — 2026-09-03

## Scope and boundary

This is the bounded provider-authentication compatibility pass for SYN-051.
It does not claim managed-continuity acceptance. No Synesis coordination
protocol was invoked, no `.synesis` state was initialized or changed, and no
historical fixture was used.

The pass stopped at the safe isolated-authentication gate. The managed
continuity security boundary remains fail-closed.

## Starting state

- Repository: `C:\Users\Liparakis\Desktop\Synesis`
- Starting HEAD: `592634a58ae94d908bdfbf92bb51a9ab9713604e`
- Branch: `master`
- Starting Git state: clean; local branch was 26 commits ahead of
  `origin/master`
- Active task: `SYN-051`
- Existing SYN-051 implementation/provenance source:
  `fd450495daf591959c7572f2842e99723de77963`
- Codex executable: `codex-cli 0.145.0`
- Platform: Windows 11 Professional, x86-64

The installed SYN-051 distribution was checked before any acceptance attempt.
The produced and installed workspace, MCP, CLI, and launcher hashes all
matched the recorded provenance in
`docs/evidence/SYN-051-managed-continuity-provenance-2026-09-03.md`; the
installed `synesis version` smoke command exited 0.

## Authentication model

### Provider documentation/source

The exact `rust-v0.145.0` Codex source defines these CLI authentication store
modes:

- `file`: persist credentials in `CODEX_HOME/auth.json`;
- `keyring`: persist credentials in the OS keyring and fail if unavailable;
- `auto`: use the keyring when available and otherwise fall back to a file;
- `ephemeral`: keep credentials in process memory only.

On Windows, the provider's default keyring backend is the `Secrets` backend.
It stores the Codex auth payload in the encrypted, home-local
`CODEX_HOME/secrets/codex_auth.age` file and stores the file passphrase in the
OS keyring. The keyring account is derived from the canonical `CODEX_HOME`
path. The direct keyring implementation also derives its account key from
`CODEX_HOME`.

Therefore, keyring availability is not equivalent to account-wide sharing
across arbitrary isolated homes. A fresh isolated home has a different
keyring account and no home-local encrypted auth payload. Reusing the existing
user authentication would require a provider-supported cross-home mechanism,
credential duplication, or sharing the home; none was established here.

The supported provider login/configuration surface is the normal Codex login
flow with `cli_auth_credentials_store = "keyring"`, including the supported
browser/device-auth variants. The provider source removes the fallback
`auth.json` after a successful keyring save. No non-destructive migration from
the existing file-backed login was found in the installed CLI surface or the
inspected provider source.

### Local observation

Only non-secret metadata and redacted provider diagnostics were inspected. The
existing credential payload was never read by Synesis or exposed to this task,
and was never printed, copied, logged, hashed, or migrated. Codex itself read
its stored credential internally as part of the provider-supported status and
ordinary model-turn operations.

- `%USERPROFILE%\\.codex\\auth.json`: exists; the existing file remained at
  length 4466 bytes and timestamp `2026-08-28T17:28:07.5160086+03:00` after
  the probes.
- Redacted `codex doctor --json`: `auth storage mode: File`, stored ChatGPT
  tokens: true, stored API key: false.
- `codex login status`: `Logged in using ChatGPT` (exit 0).
- `codex --config cli_auth_credentials_store=auto login status`: `Logged in
  using ChatGPT` (exit 0).
- `codex --config cli_auth_credentials_store=keyring login status`: `Not
  logged in` (exit 1).
- Redacted Doctor with the keyring override: `no Codex credentials were
  found`, `auth storage mode: Keyring`.
- No process environment values were set for `OPENAI_API_KEY`,
  `CODEX_API_KEY`, `CODEX_ACCESS_TOKEN`, or `CODEX_HOME`.
- No matching Codex/OpenAI metadata was returned by the Windows Credential
  Manager listing.
- No `secrets` directory was present in the normal user Codex home.

The ordinary provider smoke turn used `codex exec --ephemeral
--ignore-user-config` with read-only execution and no MCP configuration. It
returned the exact response `AUTH_PROBE_OK` and exited 0. This proves the
ordinary file-backed provider login remained usable; it does not prove safe
managed authentication.

## Isolated-home gate

A fresh disposable home was created at
`C:\Users\Liparakis\AppData\Local\Temp\syn051-codex-home-01f35832941e4489ae32f6fe6bfdfb6d`.
It started empty and no auth file or credential file was copied into it.

- Explicit keyring login status: `Not logged in` (exit 1).
- Real Codex model probe: failed after retries with HTTP 401
  `Missing bearer or basic authentication in header` (exit 1).
- No `auth.json` or `.credentials.json` was present in the disposable home.
- No managed Synesis process, MCP server, thread, attachment proof,
  participant, WorkIntent, claim, or WorkGroup was created.

This is the required stop condition. The two-home concurrent test, managed
Worker A/B acceptance, restart/resume, proof rotation, task-tracker
acceptance, Doctor/reconciliation, and credential-hygiene acceptance were not
run because the first isolated authenticated model-turn gate failed.

## Production impact

- Production authentication-detection code changed: no.
- Production files changed: none.
- Build required: no; the existing hash-matched distribution was reused only
  for the read-only version/provenance check.
- `UNSAFE_FILE_AUTH` file-only regression: the committed production path and
  focused regression remain fail-closed; a new Gradle invocation could not
  start because the host again returned `java.io.IOException: Unable to
  establish loopback connection` before tests ran, including after the
  documented IPv4 preference retry.
- No provider credential was duplicated into an isolated home.
- The disposable failed probe home was not a managed resumable worker home;
  it contains no provider auth file and remains identifiable for safe manual
  cleanup if needed.

## Disposition

SYN-051 remains `ACTIVE` and blocked at the safe provider-authentication gate;
the implementation continues to classify the current file-backed state as
`UNSAFE_FILE_AUTH`. No attempt was made to weaken that classification, copy
`auth.json`, use a symlink/junction, set broad secret environment variables,
put credentials in configuration, or launch managed continuity.

The exact next action is to obtain a provider-supported authentication mode
that lets isolated managed homes authenticate without copying or sharing
long-lived credential material. Until that capability is available and
verified, do not run managed acceptance or change the SYN-051 production
security boundary.
