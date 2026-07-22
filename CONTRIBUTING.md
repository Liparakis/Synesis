# Contributing

Read [`AGENTS.md`](AGENTS.md) and run `scripts/agent-resume.ps1` before making
changes. Exactly one primary task may be active.

For production changes, use Java 25 and the Gradle Wrapper; bootstrapper work
also requires Go 1.26.5. Run the strict Java checks and the Go test/vet checks
described in [`README.md`](README.md). Add tests and strict Javadocs for public
or protected Java APIs. Record architecture changes as ADRs under
[`docs/adr/`](docs/adr/).

Keep secrets, private fixtures, local state, generated provider hooks, session
transcripts, and machine-specific paths out of commits. Keep changes scoped to
the active task and update durable agent state after meaningful implementation
slices.
