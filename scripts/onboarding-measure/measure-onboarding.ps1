#Requires -Version 7
<#
.SYNOPSIS
Measures the two onboarding times of ADR-0103, with Synesis's own overhead apart from external latency.

.DESCRIPTION
Each run prepares a disposable Git repository from the predefined task fixture (docs/fixtures/first-task, checked in)
and then, timed with a stopwatch:

  1. the installer's install, when -Installer is given (extraction and activation, as one command);
  2. the commands of the getting-started guide's WALKTHROUGH block, in order, up to and including the first
     `provider status` (the agent's health check): time to agent readiness. The block is the one
     GettingStartedWalkthroughTest runs, so the script measures what the guide tells a person to do, and a guide
     that changes (for example `init` connecting the agent itself) changes the measurement with it. Commands of the
     block after that (`doctor`) are timed and reported but are not part of readiness;
  3. unless -SkipTask, `synesis agents run` with the fixture's task text, to its end, then the check that the
     fixture's test passes on `main` and the test file is unchanged: time to first successful task.

During the task, Synesis's own time is read from the MCP server's opt-in trace (SYNESIS_MCP_TRACE_FILE: one
`mcp_call` line per answered request, frame read to answer flushed). Model time is read from the harness's transcript
(Claude Code only; the timestamp and type of each entry are the only fields looked at, nothing is kept or printed).
Whatever is left is reported as unattributed, never folded into Synesis's overhead.

Nothing here reads a credential: no token file, no environment variable's value is looked at or printed, no sign-in is
done (the person signs in beforehand and that time is excluded and not measured). Standard input of every command is
closed, so a command that would ask a question fails instead and the run reports a prompt where one was needed.

The runs write the person's real provider files (the installed skill, project hooks) unless -HomeDirectory points
the home directory at a disposable folder, which is how to dry-run the script without an agent.

.PARAMETER Synesis
The launcher to measure (a name on the PATH or a path). With -Installer, the launcher the installer installed is used.
.PARAMETER Installer
The self-extracting installer to time. Needs -InstallDir; each run installs into its own folder below it.
.PARAMETER InstallerArguments
Arguments of the installer. Default: install --skip-path-update.
.PARAMETER Agent
claude or codex. Substituted for `claude` in the guide's commands and used for `agents run`.
.PARAMETER Model
Model requested with --agent provider:model:effort. Empty is the provider's default. Recorded with the result.
.PARAMETER Effort
Reasoning effort requested. Empty is the provider's default. Recorded with the result.
.PARAMETER Network
Free text recorded with the result (for example "home Wi-Fi", "same LAN as the VM").
.PARAMETER Runs
Number of runs; the medians are over these. The ADR asks for 5.
.PARAMETER SkipTask
Measure readiness only. No model is used.
.PARAMETER PathPrefix
A directory put first on the PATH of every command (a stand-in agent for a dry run).
.PARAMETER HomeDirectory
Home directory the commands run with (HOME and USERPROFILE). Default: the person's own.
.PARAMETER TaskTimeoutMinutes
Longest a task may take before the run is reported as failed.
.PARAMETER OutDirectory
Where the JSON result is written. Default: a folder in the temporary directory, outside any checkout.

.EXAMPLE
pwsh scripts/onboarding-measure/measure-onboarding.ps1 -Agent claude -Model sonnet -Effort medium -Network "home Wi-Fi"

.EXAMPLE
pwsh scripts/onboarding-measure/measure-onboarding.ps1 -SkipTask -Runs 3 -HomeDirectory $env:TEMP\measure-home -PathPrefix $env:TEMP\stand-in-agents
#>
[CmdletBinding()]
param(
    [string]$Synesis = 'synesis',
    [string]$Installer,
    [string]$InstallDir,
    [string[]]$InstallerArguments = @('install', '--skip-path-update'),
    [ValidateSet('claude', 'codex')][string]$Agent = 'claude',
    [string]$Model = '',
    [string]$Effort = '',
    [string]$Network = 'unspecified',
    [int]$Runs = 5,
    [switch]$SkipTask,
    [string]$PathPrefix,
    [string]$HomeDirectory,
    [int]$TaskTimeoutMinutes = 10,
    [string]$OutDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'synesis-onboarding-measure')
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixture = Join-Path $repository 'docs/fixtures/first-task'
$guide = Join-Path $repository 'docs/getting-started/README.md'
$taskText = (Get-Content (Join-Path $fixture 'task.txt') -Raw).Trim()
if ($Installer -and -not $InstallDir) { throw '-Installer needs -InstallDir.' }

# ---- helpers ---------------------------------------------------------------------------------------------------

function Get-Median([double[]]$values) {
    if (-not $values -or $values.Count -eq 0) { return $null }
    $sorted = $values | Sort-Object
    $middle = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}

# Nearest-rank 95th percentile, the same rule as scripts/linux-gate/cli-startup-baseline.sh.
function Get-P95([double[]]$values) {
    if (-not $values -or $values.Count -eq 0) { return $null }
    $sorted = $values | Sort-Object
    return $sorted[[int][math]::Ceiling(0.95 * $sorted.Count) - 1]
}

function Get-Executable([string]$name) {
    if (Test-Path $name -PathType Leaf) { return (Resolve-Path $name).Path }
    if ($PathPrefix) {
        foreach ($candidate in $name, "$name.exe", "$name.cmd") {
            if (Test-Path (Join-Path $PathPrefix $candidate) -PathType Leaf) { return (Resolve-Path (Join-Path $PathPrefix $candidate)).Path }
        }
    }
    $found = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $found) { throw "Not found on the PATH: $name" }
    return $found.Source
}

# Runs one command with standard input closed and records when each output line arrived.
function Invoke-Timed {
    param([string]$File, [string[]]$Arguments = @(), [string]$WorkingDirectory, [hashtable]$Environment = @{}, [int]$TimeoutSeconds = 300)
    $info = [Diagnostics.ProcessStartInfo]::new()
    if ($File -match '\.(cmd|bat)$') {
        $info.FileName = $env:ComSpec
        foreach ($a in @('/d', '/c', $File)) { $info.ArgumentList.Add($a) }
    } else {
        $info.FileName = $File
    }
    foreach ($a in $Arguments) { $info.ArgumentList.Add($a) }
    if ($WorkingDirectory) { $info.WorkingDirectory = $WorkingDirectory }
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    foreach ($key in $Environment.Keys) { $info.Environment[$key] = [string]$Environment[$key] }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $process = [Diagnostics.Process]::Start($info)
    $process.StandardInput.Close()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $lines = [Collections.Generic.List[object]]::new()
    $timedOut = $false
    while ($true) {
        $read = $process.StandardOutput.ReadLineAsync()
        $remaining = [int][math]::Max(0, $TimeoutSeconds * 1000 - $watch.ElapsedMilliseconds)
        if (-not $read.Wait($remaining)) { $timedOut = $true; $process.Kill($true); break }
        if ($null -eq $read.Result) { break }
        $lines.Add([pscustomobject]@{ AtMs = $watch.ElapsedMilliseconds; Text = $read.Result })
    }
    $process.WaitForExit()
    $watch.Stop()
    return [pscustomobject]@{ Exit = $process.ExitCode; ElapsedMs = $watch.ElapsedMilliseconds; Lines = $lines; Error = $errorTask.Result; TimedOut = $timedOut }
}

function Get-Walkthrough {
    $lines = Get-Content $guide
    $marker = [array]::FindIndex($lines, [Predicate[string]] { param($l) $l.StartsWith('<!-- WALKTHROUGH:') })
    if ($marker -lt 0) { throw "$guide has no WALKTHROUGH block." }
    $inside = $false
    $commands = @()
    foreach ($line in $lines[($marker + 1)..($lines.Count - 1)]) {
        if ($line.StartsWith('```')) { if ($inside) { break } else { $inside = $true; continue } }
        if (-not $inside) { continue }
        $text = ($line -replace '#.*$', '').Trim()
        if ($text) {
            $tokens = $text -split '\s+'
            if ($tokens[0] -ne 'synesis') { throw "A walkthrough command does not start with synesis: $text" }
            $commands += , @($tokens[1..($tokens.Count - 1)] | ForEach-Object { if ($_ -eq 'claude') { $Agent } else { $_ } })
        }
    }
    return , $commands
}

function Find-Sh {
    $sh = Get-Command sh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($sh) { return $sh.Source }
    $git = Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $candidate = Join-Path (Split-Path (Split-Path $git.Source)) 'usr/bin/sh.exe'
    if (Test-Path $candidate) { return $candidate }
    throw 'No sh found to run the fixture test (Git for Windows ships one).'
}

# Model time and harness tool time from a Claude Code transcript: only each entry's type and timestamp are used.
function Get-ClaudeTranscriptTimes([string]$project, [datetime]$since, [string]$userHome) {
    $slug = $project -replace '[^A-Za-z0-9]', '-'
    $directory = Join-Path $userHome ".claude/projects/$slug"
    if (-not (Test-Path $directory)) { return $null }
    $file = Get-ChildItem $directory -Filter *.jsonl | Where-Object { $_.LastWriteTime -ge $since } | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $file) { return $null }
    $model = 0.0; $tool = 0.0; $previous = $null; $previousType = $null
    foreach ($line in Get-Content $file.FullName) {
        $entry = $line | ConvertFrom-Json -ErrorAction SilentlyContinue
        if (-not $entry -or -not $entry.timestamp -or $entry.type -notin 'user', 'assistant') { continue }
        $at = [datetime]::Parse($entry.timestamp, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
        if ($previous) {
            $gap = ($at - $previous).TotalMilliseconds
            if ($entry.type -eq 'assistant') { $model += $gap } elseif ($previousType -eq 'assistant') { $tool += $gap }
        }
        $previous = $at; $previousType = $entry.type
    }
    return [pscustomobject]@{ ModelMs = [math]::Round($model); HarnessToolMs = [math]::Round($tool) }
}

function Get-McpTimes([string]$trace) {
    if (-not (Test-Path $trace)) { return $null }
    $durations = @(Get-Content $trace | Where-Object { $_ -match '^mcp_call .* duration_micros=(\d+)$' } | ForEach-Object { [double]$Matches[1] / 1000.0 })
    if ($durations.Count -eq 0) { return $null }
    return [pscustomobject]@{ Calls = $durations.Count; TotalMs = [math]::Round(($durations | Measure-Object -Sum).Sum, 1); MedianMs = [math]::Round((Get-Median $durations), 2); P95Ms = [math]::Round((Get-P95 $durations), 2); MaxMs = [math]::Round(($durations | Measure-Object -Maximum).Maximum, 2) }
}

function Get-CommandText([string]$file, [string[]]$arguments) { return ((Split-Path $file -Leaf), $arguments) -join ' ' }

# ---- conditions ------------------------------------------------------------------------------------------------

$userHome = if ($HomeDirectory) { (New-Item -ItemType Directory -Force $HomeDirectory).FullName } else { [Environment]::GetFolderPath('UserProfile') }
$baseEnvironment = @{ SYNESIS_STUN_SERVER = 'off' }
if ($HomeDirectory) {
    # Every per-user root, so a dry run cannot reach the person's registry of projects or Link profile either.
    $baseEnvironment['HOME'] = $userHome; $baseEnvironment['USERPROFILE'] = $userHome
    $baseEnvironment['LOCALAPPDATA'] = Join-Path $userHome 'AppData/Local'; $baseEnvironment['APPDATA'] = Join-Path $userHome 'AppData/Roaming'
    $baseEnvironment['XDG_STATE_HOME'] = Join-Path $userHome '.local/state'; $baseEnvironment['XDG_DATA_HOME'] = Join-Path $userHome '.local/share'
    $baseEnvironment['SYNESIS_LINK_PROFILE'] = Join-Path $userHome 'link-profile'
}
if ($PathPrefix) { $baseEnvironment['PATH'] = $PathPrefix + [IO.Path]::PathSeparator + $env:PATH }
$agentExecutable = Get-Executable $Agent
$harnessVersion = (Invoke-Timed -File $agentExecutable -Arguments @('--version') -Environment $baseEnvironment -TimeoutSeconds 30).Lines | Select-Object -First 1 -ExpandProperty Text
$workRoot = Join-Path ([IO.Path]::GetTempPath()) ("synesis-onboarding-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $workRoot, $OutDirectory | Out-Null
$sh = Find-Sh
$walkthrough = Get-Walkthrough
$statusIndex = [array]::FindIndex($walkthrough, [Predicate[object]] { param($c) $c.Count -ge 2 -and $c[0] -eq 'provider' -and $c[1] -eq 'status' })
if ($statusIndex -lt 0) { throw 'The guide has no `provider status` command, so readiness has no end.' }

$conditions = [ordered]@{
    machine           = [Environment]::MachineName
    os                = [Runtime.InteropServices.RuntimeInformation]::OSDescription
    cpus              = [Environment]::ProcessorCount
    network           = $Network
    provider          = $Agent
    model             = if ($Model) { $Model } else { 'provider default' }
    effort            = if ($Effort) { $Effort } else { 'provider default' }
    harnessVersion    = $harnessVersion
    synesisVersion    = if ($Installer) { 'the installed build, named by the installer' } else { (Invoke-Timed -File (Get-Executable $Synesis) -Arguments @('--version') -Environment $baseEnvironment -TimeoutSeconds 60).Lines | Select-Object -First 1 -ExpandProperty Text }
    powershell        = $PSVersionTable.PSVersion.ToString()
    installerGiven    = [bool]$Installer
    taskText          = $taskText
    guideCommands     = @($walkthrough | ForEach-Object { 'synesis ' + ($_ -join ' ') })
    runs              = $Runs
    measuredAt        = (Get-Date).ToUniversalTime().ToString('o')
}

# ---- one run ---------------------------------------------------------------------------------------------------

function Measure-Run([int]$index) {
    $runDirectory = Join-Path $workRoot "run$index"
    $project = Join-Path $runDirectory 'project'
    New-Item -ItemType Directory -Force $project | Out-Null
    Copy-Item (Join-Path $fixture 'repo/*') $project
    foreach ($arguments in @(@('init', '-q', '-b', 'main'), @('config', 'user.name', 'Measure'), @('config', 'user.email', 'measure@example.test'), @('add', '.'), @('commit', '-q', '-m', 'fixture'))) {
        $prepared = Invoke-Timed -File (Get-Executable git) -Arguments $arguments -WorkingDirectory $project -Environment $baseEnvironment -TimeoutSeconds 60
        if ($prepared.Exit -ne 0) { throw "Fixture preparation failed: git $($arguments -join ' ')`n$($prepared.Error)" }
    }

    $launcher = Get-Executable $Synesis
    $environment = $baseEnvironment.Clone()
    $steps = [Collections.Generic.List[object]]::new()
    $prompts = 0
    $total = [Diagnostics.Stopwatch]::StartNew()

    if ($Installer) {
        $target = Join-Path $InstallDir "run$index"
        $result = Invoke-Timed -File (Get-Executable $Installer) -Arguments ($InstallerArguments + @('--install-dir', $target)) -Environment $environment -TimeoutSeconds 600
        $steps.Add([pscustomobject]@{ Name = 'installer'; Command = 'installer ' + ($InstallerArguments -join ' '); ElapsedMs = $result.ElapsedMs; Exit = $result.Exit; Readiness = $true })
        if ($result.Exit -ne 0) { return [pscustomobject]@{ Run = $index; Failed = "installer exit $($result.Exit)"; Steps = $steps } }
        $installed = Get-ChildItem (Join-Path $target 'bin/*') -Include 'synesis.exe', 'synesis.cmd', 'synesis' -File -ErrorAction SilentlyContinue | Sort-Object { $_.Extension -ne '.exe' } | Select-Object -First 1
        if (-not $installed) { return [pscustomobject]@{ Run = $index; Failed = "no launcher in $target\bin after the install"; Steps = $steps } }
        $launcher = $installed.FullName
        $environment['PATH'] = (Split-Path $launcher) + [IO.Path]::PathSeparator + $(if ($environment.ContainsKey('PATH')) { $environment['PATH'] } else { $env:PATH })
    }

    $ready = $false
    $readyAtMs = $null
    for ($i = 0; $i -lt $walkthrough.Count; $i++) {
        $arguments = $walkthrough[$i]
        $result = Invoke-Timed -File $launcher -Arguments $arguments -WorkingDirectory $project -Environment $environment -TimeoutSeconds 300
        if ($result.Lines | Where-Object { $_.Text -match '(?i)^\s*(continue|proceed)\b.*\?|\[y/n\]' }) { $prompts++ }
        $isReadiness = $i -le $statusIndex
        $steps.Add([pscustomobject]@{ Name = $arguments[0]; Command = 'synesis ' + ($arguments -join ' '); ElapsedMs = $result.ElapsedMs; Exit = $result.Exit; Readiness = $isReadiness })
        if ($i -eq $statusIndex) {
            $ready = $result.Exit -eq 0 -and [bool]($result.Lines | Where-Object { $_.Text -match '^PROVIDER_STATUS=HEALTHY$' })
            $readyAtMs = $total.ElapsedMilliseconds
        }
        if ($result.Exit -ne 0 -and $isReadiness) {
            return [pscustomobject]@{ Run = $index; Failed = "synesis $($arguments -join ' ') exit $($result.Exit): $($result.Error.Trim())"; Steps = $steps }
        }
    }
    if (-not $ready) { return [pscustomobject]@{ Run = $index; Failed = 'provider status was not HEALTHY'; Steps = $steps } }

    $synesisReadinessMs = ($steps | Where-Object Readiness | Measure-Object ElapsedMs -Sum).Sum
    $userActions = @($steps | Where-Object Readiness).Count + 1  # + the `cd` into the project
    $record = [ordered]@{
        Run = $index; Failed = $null; Steps = $steps
        ReadinessMs = $readyAtMs; SynesisReadinessMs = $synesisReadinessMs
        UserActions = $userActions; Prompts = $prompts
    }
    if ($SkipTask) { return [pscustomobject]$record }

    # ---- first task ----
    $trace = Join-Path $runDirectory 'mcp-trace.log'
    $taskEnvironment = $environment.Clone()
    $taskEnvironment['SYNESIS_MCP_TRACE_FILE'] = $trace
    $spec = $Agent + $(if ($Model -or $Effort) { ":${Model}" + $(if ($Effort) { ":$Effort" } else { '' }) } else { '' })
    $taskStarted = Get-Date
    $result = Invoke-Timed -File $launcher -Arguments @('agents', 'run', '--project', $project, '--agent', $spec, '--prompt', $taskText, '--timeout-minutes', "$TaskTimeoutMinutes") -Environment $taskEnvironment -TimeoutSeconds (($TaskTimeoutMinutes + 2) * 60)
    $runtimeLine = $result.Lines | Where-Object { $_.Text -like 'PROJECT_RUNTIME=*' } | Select-Object -First 1
    $startedLine = $result.Lines | Where-Object { $_.Text -like 'AGENT_LOG_DIRECTORY=*' } | Select-Object -First 1
    $outcome = ($result.Lines | Where-Object { $_.Text -like 'AGENT_RUN_RESULT=*' } | Select-Object -Last 1)
    $verified = $false
    $verifyDirectory = Join-Path $runDirectory 'verify'
    New-Item -ItemType Directory -Force $verifyDirectory | Out-Null
    foreach ($name in 'lib.sh', 'test.sh') {
        $content = Invoke-Timed -File (Get-Executable git) -Arguments @('show', "main:$name") -WorkingDirectory $project -Environment $baseEnvironment -TimeoutSeconds 30
        if ($content.Exit -eq 0) { [IO.File]::WriteAllText((Join-Path $verifyDirectory $name), (($content.Lines | ForEach-Object Text) -join "`n") + "`n") }
    }
    $testUnchanged = (Test-Path (Join-Path $verifyDirectory 'test.sh')) -and ((Get-Content (Join-Path $verifyDirectory 'test.sh') -Raw).Trim() -eq (Get-Content (Join-Path $fixture 'repo/test.sh') -Raw).Trim())
    if ($testUnchanged -and (Test-Path (Join-Path $verifyDirectory 'lib.sh'))) {
        $verified = (Invoke-Timed -File $sh -Arguments @('test.sh') -WorkingDirectory $verifyDirectory -Environment $baseEnvironment -TimeoutSeconds 30).Exit -eq 0
    }
    $firstTaskMs = $total.ElapsedMilliseconds
    $mcp = Get-McpTimes $trace
    $transcript = if ($Agent -eq 'claude') { Get-ClaudeTranscriptTimes $project $taskStarted $userHome } else { $null }
    $record.Task = [ordered]@{
        Succeeded = $verified -and $result.Exit -eq 0 -and -not $result.TimedOut
        AgentsRunExit = $result.Exit; TimedOut = $result.TimedOut
        Outcome = if ($outcome) { $outcome.Text } else { $null }
        TestPassesOnMain = $verified; TestUnchanged = $testUnchanged
        UntilRuntimeLineMs = if ($runtimeLine) { $runtimeLine.AtMs } else { $null }
        UntilAgentStartedMs = if ($startedLine) { $startedLine.AtMs } else { $null }
        AgentsRunMs = $result.ElapsedMs
        FirstTaskMs = $firstTaskMs
        SynesisMcp = $mcp
        ModelMs = if ($transcript) { $transcript.ModelMs } else { $null }
        HarnessToolMs = if ($transcript) { $transcript.HarnessToolMs } else { $null }
        UnattributedMs = if ($mcp -and $transcript) { [math]::Max(0, $result.ElapsedMs - $mcp.TotalMs - $transcript.ModelMs) } else { $null }
        McpTraceMissing = -not $mcp
        TranscriptMissing = -not $transcript
    }
    return [pscustomobject]$record
}

# ---- run, summarise --------------------------------------------------------------------------------------------

$results = @()
try {
    for ($i = 1; $i -le $Runs; $i++) {
        $results += Measure-Run $i
        $last = $results[-1]
        if ($last.Failed) { Write-Host "RUN_$i=FAILED REASON=$($last.Failed)" } else { Write-Host "RUN_$i=OK READINESS_MS=$($last.ReadinessMs)" }
    }
} finally {
    Remove-Item $workRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$ok = @($results | Where-Object { -not $_.Failed })
$tasks = @($ok | Where-Object { $_.Task -and $_.Task.Succeeded })
$summary = [ordered]@{
    runs                          = $Runs
    readinessRuns                 = $ok.Count
    failedRuns                    = @($results | Where-Object Failed | ForEach-Object { "run $($_.Run): $($_.Failed)" })
    failedTasks                   = @($ok | Where-Object { $_.Task -and -not $_.Task.Succeeded } | ForEach-Object { "run $($_.Run): $($_.Task.Outcome) testPassesOnMain=$($_.Task.TestPassesOnMain) testUnchanged=$($_.Task.TestUnchanged) timedOut=$($_.Task.TimedOut)" })
    medianTimeToAgentReadinessMs  = Get-Median @($ok | ForEach-Object ReadinessMs)
    medianSynesisReadinessMs      = Get-Median @($ok | ForEach-Object SynesisReadinessMs)
    userActionsToReadiness        = ($ok | Select-Object -First 1).UserActions
    promptsToReadiness            = ($ok | Measure-Object Prompts -Maximum).Maximum
    firstTaskRuns                 = $tasks.Count
    medianTimeToFirstTaskMs       = Get-Median @($tasks | ForEach-Object { $_.Task.FirstTaskMs })
    medianSynesisMcpTotalMs       = Get-Median @($tasks | Where-Object { $_.Task.SynesisMcp } | ForEach-Object { $_.Task.SynesisMcp.TotalMs })
    medianSynesisMcpP95Ms         = Get-Median @($tasks | Where-Object { $_.Task.SynesisMcp } | ForEach-Object { $_.Task.SynesisMcp.P95Ms })
    medianModelMs                 = Get-Median @($tasks | Where-Object { $null -ne $_.Task.ModelMs } | ForEach-Object { $_.Task.ModelMs })
    note                          = 'Failed runs and runs whose task did not succeed are listed, not averaged. A run during a provider outage is to be reported as such by the person, not counted.'
}
$file = Join-Path $OutDirectory ("onboarding-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
[ordered]@{ conditions = $conditions; summary = $summary; runs = $results } | ConvertTo-Json -Depth 8 | Set-Content $file -Encoding UTF8

"ONBOARDING_RESULT_FILE=$file"
$summary.GetEnumerator() | ForEach-Object { 'ONBOARDING_' + ($_.Key -creplace '([A-Z])', '_$1').ToUpper() + '=' + ($_.Value -join '; ') }
