# Synesis Project Layout

Run `synesis init` at a project root. Commands started in a nested directory
walk upward until `.synesis/project.json` is found; `--project <path>` selects
an explicit project directory. A malformed or partial state fails closed and
is never repaired or overwritten automatically.

```text
<project>/
  .synesis/
    project.json                 # shareable bounded metadata
    shared/
      records/                   # reserved for explicitly shareable records
    local/                       # add to .gitignore
      profile/
        link/                    # private node identity and Link state
        project.conf             # local peer configuration
        records/                 # local record store
      providers/                 # machine-local provider metadata and health
      runtime/                   # machine/runtime state
```

`project.json` contains `schemaVersion`, `projectId`, and `createdAt`. It must
not contain private keys, absolute paths, provider metadata, or runtime state.
It may also hold the project's policy. Any other field is refused.

- `validation` is the command that must pass before work is integrated:
  `argv` (the executable and its arguments, run without a shell),
  `workingDirectory` (relative to the project root, default `.`),
  `timeoutSeconds` (default 120, at most 3600) and `alsoInLane`. The command
  always runs on the combined work before the project's branch moves. With
  `alsoInLane` true it also runs in each lane alone before that lane's finish
  is published; absent or false, it does not. Set it with
  `synesis project validation set`, not by hand.
- `review` with `mandatory` true makes every work group announced in the
  project subject to mandatory independent review.

```json
{
  "schemaVersion": 2,
  "projectId": "<uuid>",
  "createdAt": "<instant>",
  "validation": {"argv":["python","-m","unittest"],"workingDirectory":".","timeoutSeconds":120,"alsoInLane":true}
}
```

`synesis init` commits `project.json`. A later change to it is an uncommitted
change in the project checkout until it is committed, and nothing is integrated
into a checkout holding one.

The recommended ignore entry is:

```gitignore
.synesis/local/
```

`.synesis/` is Synesis project metadata and is the canonical home for
project-internal bookkeeping. On Windows Synesis marks the directory Hidden by
default through the standard filesystem attribute; on Unix-like systems the
leading dot supplies the normal hidden-directory behavior. This visibility
state is UX only: it is not authorization, confidentiality, integrity, or
project identity. Filesystem permissions, authenticated state, signed
membership, and identity checks remain authoritative.

The Hidden attribute is local derived presentation state. A copy, archive,
cross-platform move, or relocation may lose it; reopening or attaching a valid
project re-ensures the attribute without changing `projectId`, node identity,
membership, or metadata bytes. Diagnostics may show the metadata path when
needed. Users should not edit Synesis metadata manually.

`AGENTS.md` is intentionally visible because its managed section is a
user-facing provider/integration contract, not internal bookkeeping. User,
agent, build, test, and tooling output remains ordinary visible project
content.

`synesis init` is idempotent for valid existing state and returns exit code 0
with `INIT_RESULT=ALREADY_INITIALIZED`. Conflicting or malformed state returns
`INIT_RESULT=CONFLICT` and a non-zero exit.
