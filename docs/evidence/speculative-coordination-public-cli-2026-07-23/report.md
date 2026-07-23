# Public speculative coordination CLI acceptance

Date: 2026-07-23. Result: PASS.

The reusable harness `scripts/run-speculative-coordination-real.ps1` ran the
built launcher against an external initialized Git project, not the Synesis
source tree. It started one loopback coordinator and one independent owner
supervisor process, used separate requester/owner/coordinator profiles, and
completed task creation, task claim, semantic ownership, prediction creation,
owner receipt and exact acceptance, implementation publication, speculation
preparation, integration gating, requester validation, and retirement.

Observed run:

- Runtime root: `C:\Users\LIPARA~1\AppData\Local\Temp\synesis-script-test5-f5250393bbbc49f18a6cdb4e72209fbc`
- Project ID: `657117ce-4dde-4cb3-b18e-f0a8e9b4cd7b`
- Task ID: `ccb98e45-4b81-41bb-b5f4-a4348e6722cf`
- Prediction ID: `98f84dc3-3a3f-4700-8496-d2f856e681dd`
- Endpoint: `http://127.0.0.1:49238/`
- Final coordinator sequence: 12; final prediction state: `RETIRED`
- Supervisor cursor reached sequence 11 after live SSE delivery.

The requester profile was deliberately used for an owner-only receive action;
the command failed with `ACTOR_NOT_AUTHORIZED`. The owner profile then
completed the same lifecycle successfully. Scope arguments use literal,
wildcard-safe tokens because Windows command launchers expand unescaped
wildcards.

The process transcript and summary for this run are retained in the disposable
runtime root above. No private keys or credentials are copied into the
repository evidence directory.

The rebuilt Windows bundle was then installed through a local development
manifest after uninstalling the prior `%LOCALAPPDATA%\Synesis` root. The
installed launcher reported `VERSION=0.1.0-dev.local`, `doctor` passed, and the
same harness passed without source-tree classpath access:

- Runtime root: `C:\Users\LIPARA~1\AppData\Local\Temp\synesis-installed-acceptance2-9349900772084212b99d05157b4bd953`
- Project ID: `d313aa17-c0a6-4f7e-888f-586ac45d937f`
- Final prediction ID: `d459dccf-9228-441a-845b-535ddf15e99a`
