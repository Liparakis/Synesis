# ADR-0062: SYN-051 Windows runtime boundary and pending proof activation

**Status:** Accepted for the bounded implementation slice

**Date:** 2026-09-03

## Context

The selected shared-normal-home design needs two independent runtime controls:
the App Server and all of its MCP descendants must be owned as one process
tree, and the current attachment proof must reach only the exact MCP child
without being placed in the shared Codex configuration or model-visible data.
The existing JDK PID/tree terminator cannot prove assignment-before-resume or
contain a child that survives App Server-only death.

## Decision

Use a Windows-only Java 25 FFM implementation over `kernel32`. Each managed
launch creates a new Job Object, configures only
`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, creates the App Server with
`CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT`, assigns the process before
`ResumeThread`, and retains the Job, process, primary-thread, and pipe
handles in the trusted supervisor. Descendant inheritance remains enabled;
neither breakaway flag is set.

Definitive tree death requires the root process wait to be signaled, the Job
wait to be signaled, and `QueryInformationJobObject` accounting to report
`ActiveProcesses == 0`. Any failed query, failed wait, lost handle, or
non-empty result is ambiguous and blocks successor authority. PID disappearance
alone is not a death proof.

The existing `CodexAppServerLifecycleService` remains the lifecycle owner.
Managed `AppServerProcess` attachments carry the supervisor; ordinary/fake
launchers retain the legacy path. Startup failure, observed App Server exit,
hard stop, failed-attachment cleanup, and owner shutdown all use the managed
Job teardown path when present.

The managed launcher uses the normal provider home without reading or copying
provider credentials. It puts the random proof only in the App Server process
environment and passes only its variable name through the launch-local Codex
`mcp_servers.synesis.env_vars` override. The durable attachment record is
`PENDING_ACTIVATION` until the broker-pinned exact thread response and
readback are verified; only the trusted lifecycle then changes it to
`ACTIVE`. Early proof authentication fails closed.

## Consequences

- One worker generation owns one independent Job; Jobs are not shared.
- The proof carrier is private to the App Server/selected MCP child topology,
  while the durable record remains hash-only.
- The managed runtime requires a Java 25 host with native access enabled and
  is explicitly unsupported on non-Windows platforms for now.
- This slice does not prove real Codex A/B proof isolation, model turns,
  restart, artifact provenance, or full task-tracker acceptance. Those remain
  separate gates.
- The MCP catalog remains exactly ten tools and no lifecycle or proof control
  is model-facing.

## Evidence

Focused source compilation, lifecycle/attachment/admission tests, and a real
Windows root-plus-descendant Job teardown test pass. The broad MCP selection
was stopped after no progress and is incomplete. The current evidence record
is `docs/evidence/SYN-051-shared-normal-home-implementation-2026-09-03.md`.
