# Getting started

This is the shortest path from a checkout to an initialized local Synesis
project.

## Build the local launcher

Use Java 25 and the Gradle Wrapper:

```powershell
.\gradlew.bat :cli:installDist --dependency-verification=strict
& ".\cli\build\install\synesis\bin\synesis.bat" --help
```

For the full verification matrix, see [build and test](../development/build-and-test.md).

## Initialize a project

From the project root, run:

```powershell
synesis init
synesis doctor
synesis provider list
```

Initialization creates shareable metadata in `.synesis/project.json` and keeps
identity, provider, and runtime state under `.synesis/local/`. Add that local
directory to `.gitignore`. Initialization also adds one replaceable Synesis
section to `AGENTS.md`; user-authored text is preserved.

## Normal workflow

Use a managed provider hook or MCP connection. Reads return a revision for the
exact file bytes, and modifications must provide that revision as a precondition.
One persistent MCP connection owns one provider binding and does not share a
worker worktree with another connection.

See [project layout](../installation/project-layout.md), [provider management](../installation/provider-management.md), and the [CLI reference](../cli/command-reference.md).
