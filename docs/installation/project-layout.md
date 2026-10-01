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
