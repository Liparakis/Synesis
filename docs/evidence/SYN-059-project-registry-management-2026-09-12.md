# SYN-059 Project Registry Management — 2026-09-12

## Scope

SYN-059 adds a useful main Projects page without moving project authority into
the browser. The existing project search is retained and now has an explicit
status filter. The page can register an existing initialized Synesis project or
remove a non-current project from the installation-local registry.

Removal is registry metadata only. It does not delete the project directory,
`.synesis` state, repositories, workspaces, or Link identity. The current
project serving the authenticated control plane is protected from removal.

## Implementation

- `KnownProjectRegistry.register(Path)` validates through
  `ProjectApplicationService` and preserves idempotent observation behavior.
- `KnownProjectRegistry.remove(UUID)` removes only the matching registry
  record under the existing file lock and atomic persistence path.
- Authenticated `register-project` and `remove-project` control-plane commands
  require the existing session and CSRF headers.
- The Projects page provides search, status filtering, Add project, and a
  confirmation modal for Remove from registry.
- Successful mutations refresh the authoritative snapshot; browser controls
  never write project files directly.

## Verification

- Focused workspace registry/control-plane tests — PASS.
- `npm run typecheck` — PASS.
- `npm run lint` — PASS.
- `npm run test -- --run` — PASS, 21 tests.
- `npm run build` — PASS.
- `:cli:runnableInstaller --rerun-tasks --no-daemon` — PASS.
- Fresh Windows installed payload doctor — PASS.
- Installed CLI version and stable `synesis://` registration — PASS.
- `git diff --check` — PASS.

Known unrelated aggregate CLI/workspace/provider failures remain outside this
slice and were not repaired.
