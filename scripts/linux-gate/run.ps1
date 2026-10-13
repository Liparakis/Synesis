<#
.SYNOPSIS
Runs Synesis Gradle tasks on Linux in a container, on the current branch plus uncommitted work.

.DESCRIPTION
The test suite starts tens of thousands of git processes. Starting one costs about 30 to 40 ms on
Windows and about 1 ms on Linux, so the gate that takes about 20 minutes on Windows takes about
2.5 minutes here. Use this while iterating. It does not test Windows behaviour: run the Windows
gate before a change is pushed.

The checkout is not mounted into the container. Its history goes in as one bundle file and
uncommitted and untracked work as one patch, and the container clones onto its own filesystem:
cloning through a Windows folder mount was slow and dropped the engine's connection.

.PARAMETER GradleArguments
Arguments for Gradle. With none, the whole gate runs (check --continue). Quote an argument that
starts with a single dash, such as '-PsynesisTestForks=1', so PowerShell does not read it as a
parameter of this script.

.PARAMETER WorkDirectory
Where the bundle, the patch, the log and the copied test results are kept. Defaults to the
SYNESIS_LINUX_GATE_DIR environment variable, or a linux-gate directory under the local
application data directory.

.PARAMETER Engine
Container engine command: podman (default) or docker.

.EXAMPLE
scripts\linux-gate\run.ps1

.EXAMPLE
scripts\linux-gate\run.ps1 :workspace:test --tests '*ConversationServiceTest'
#>
# Only the Gradle arguments are taken by position; otherwise the first of them was read as the work directory.
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments,
    [string]$WorkDirectory = $(if ($env:SYNESIS_LINUX_GATE_DIR) { $env:SYNESIS_LINUX_GATE_DIR } else { Join-Path $env:LOCALAPPDATA 'Synesis\linux-gate' }),
    [string]$Engine = 'podman'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$image = 'localhost/synesis-linux-gate:25'
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null

Push-Location $repository
try {
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($branch -eq 'HEAD') { throw 'The checkout is on no branch; the gate clones a branch.' }
    # A step of Git that fails leaves the bundle or the patch of an earlier run in place, and the
    # gate would pass or fail on a tree that is not this one.
    function Assert-Git([string]$step) { if ($LASTEXITCODE -ne 0) { throw "git $step failed; nothing was tested." } }
    git bundle create (Join-Path $WorkDirectory 'synesis.bundle') $branch 2>&1 | Out-Null
    Assert-Git 'bundle'
    # Uncommitted and untracked work, through a throwaway index so the real one is not touched.
    $env:GIT_INDEX_FILE = Join-Path $WorkDirectory 'wip.index'
    git read-tree HEAD
    Assert-Git 'read-tree'
    git add -A 2>&1 | Out-Null
    Assert-Git 'add'
    $patch = Join-Path $WorkDirectory 'wip.patch'
    cmd /c "git diff --cached --binary HEAD > `"$patch`""
    Assert-Git 'diff'
} finally {
    $env:GIT_INDEX_FILE = $null
    Pop-Location
}

# The script reaches the container with Unix line endings whatever the checkout gave it.
$script = (Get-Content (Join-Path $PSScriptRoot 'gate.sh') -Raw) -replace "`r`n", "`n"
[IO.File]::WriteAllText((Join-Path $WorkDirectory 'gate.sh'), $script)

if ($Engine -eq 'podman') { & $Engine machine start 2>&1 | Out-Null }
& $Engine image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    & $Engine build -t $image -f (Join-Path $PSScriptRoot 'Containerfile') $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'The gate image could not be built.' }
}

$timer = [Diagnostics.Stopwatch]::StartNew()
& $Engine run --rm -e "SYNESIS_BRANCH=$branch" -v "${WorkDirectory}:/out" -v synesis-linux-gate-gradle:/root/.gradle $image bash /out/gate.sh @GradleArguments
$status = $LASTEXITCODE
"TOTAL_SECONDS=$([int]$timer.Elapsed.TotalSeconds)"
"Log and test results: $WorkDirectory"
exit $status
