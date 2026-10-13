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
