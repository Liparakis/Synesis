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
      providers/                 # future provider installation state
      runtime/                   # machine/runtime state
```

`project.json` contains `schemaVersion`, `projectId`, and `createdAt`. It must
not contain private keys, absolute paths, provider metadata, or runtime state.
The recommended ignore entry is:

```gitignore
.synesis/local/
```

`synesis init` is idempotent for valid existing state and returns exit code 0
with `INIT_RESULT=ALREADY_INITIALIZED`. Conflicting or malformed state returns
`INIT_RESULT=CONFLICT` and a non-zero exit.
