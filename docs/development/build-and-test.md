# Build and test

Requirements: Java 25, the Gradle Wrapper, and Go 1.26.5 for bootstrapper work.

```powershell
.\gradlew.bat check --dependency-verification=strict
```

`check` intentionally covers Java verification only. It does not assemble or
smoke-test release distributions. Run the explicit distribution checks when
working on packaging or release artifacts:

```powershell
.\gradlew.bat :cli:distributionCheck --dependency-verification=strict
```

Gradle keeps its daemon, parallel execution, configuration cache, build cache,
and file-system watching enabled by default in this repository. Avoid `clean`
for iterative builds because it discards the outputs those caches can reuse.

## The gate on Linux, for iterating

The test suite starts tens of thousands of `git` processes. Starting one costs
about 30 to 40 ms on Windows and about 1 ms on Linux, so the same gate that
takes about 20 minutes on Windows takes about two and a half minutes in a Linux
container. With Podman (or Docker) installed:

```powershell
scripts\linux-gate\run.ps1
scripts\linux-gate\run.ps1 :workspace:test --tests '*ConversationServiceTest'
```

The first form runs the whole gate; the second passes its arguments to Gradle.
The script tests the current branch together with uncommitted and untracked
work, and does not touch the checkout: history goes into the container as one
bundle file and the rest as one patch, and the container clones onto its own
filesystem. The image (Java 25, git, Node) is built from
`scripts/linux-gate/Containerfile` on first use. The log and a copy of the test
results are left in the work directory, which is `SYNESIS_LINUX_GATE_DIR` when
that is set.

This run does not test Windows behaviour: file locking, path and line-ending
handling, the launcher and the installer. Run the Windows gate above before a
change is pushed.

## Measuring onboarding

[ADR-0103](../adr/0103-zero-friction-onboarding-and-developer-experience.md)
sets two times, measured apart: from the installer starting to the agent being
ready, and to a predefined task done and verified. The task is the fixture in
`docs/fixtures/first-task` (a repository with one failing test; the task text is
in `task.txt`). Neither measurement is part of `check`, because a time is a
number to read, not a pass or fail.

On a real computer, with the agent signed in beforehand (that time is not
measured):

```powershell
pwsh scripts/onboarding-measure/measure-onboarding.ps1 -Agent claude -Model sonnet -Effort medium -Network "home Wi-Fi" -Runs 5
```

The script follows the commands of the getting-started guide's `WALKTHROUGH`
block, so it times what the guide tells a person to do. It reports the median
time to agent readiness and to the first task, Synesis's own part (the commands,
and during the task the time Synesis held each MCP request, read from the opt-in
trace `SYNESIS_MCP_TRACE_FILE`) apart from the model's time (from Claude Code's
transcript; only each entry's type and timestamp are read), the number of user
actions and prompts, and the conditions (machine, OS, network, provider, model,
effort, harness version). Add `-SkipTask` to measure readiness without a model
and `-Installer <path> -InstallDir <folder>` to time the installer too.
`-HomeDirectory <folder>` runs everything with a disposable home directory, which
is how to try the script without touching the real provider files. The script
reads no credential.

The guide's commands also run as a test, in the Linux gate
(`GettingStartedWalkthroughTest`), so a command that stops working fails the
build. The CLI's start-up time is recorded on the Linux gate with
`scripts\linux-gate\run.ps1 :cli:cliStartupBaseline`, which prints the minimum,
median and 95th percentile of `--help`, `version` and a read of an initialized
project; the budget is set later from those numbers.

The Go module is `bootstrap`; run its checks from that directory:

```powershell
Push-Location bootstrap
go test -count=1 ./...
go vet ./...
Pop-Location
```

Build the local application distribution with
`.\gradlew.bat :cli:installDist`. The generated launcher is under
`cli/build/install/synesis/bin/`. Run `synesis --help`, `synesis version`, and
`synesis provider list` as smoke checks. Do not treat a synthetic provider check
as real-agent evidence.
