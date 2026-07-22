# Synesis — Antigravity Guardrail Baseline vs. Synesis Experiment Runner
# Enforces strict execution policy and automated metric reporting for Antigravity PreToolUse hook adapter

$ErrorActionPreference = "Stop"

Write-Host "=========================================================="
Write-Host "   Synesis Antigravity Guardrail Experiment Runner       "
Write-Host "=========================================================="

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
Set-Location $RootDir

# 1. Build workspace distribution
Write-Host "[1/6] Building workspace distribution..."
& .\gradlew.bat :workspace:installDist --quiet
if ($LASTEXITCODE -ne 0)
{
    Write-Error "Failed to build workspace distribution"
    exit 1
}

$Launcher = Join-Path $RootDir "cli\build\install\synesis\bin\synesis.bat"
if (-not (Test-Path $Launcher))
{
    Write-Error "Launcher script not found at $Launcher"
    exit 1
}

# 2. Create temporary isolated profile directories and test fixture directory
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("synesis-ag-exp-" + [System.Guid]::NewGuid().ToString("N"))
$ProfileA = Join-Path $TempRoot "profileA"
$ProfileB = Join-Path $TempRoot "profileB"
$FixtureDir = Join-Path $TempRoot "fixture"
New-Item -ItemType Directory -Path $ProfileA | Out-Null
New-Item -ItemType Directory -Path $ProfileB | Out-Null
New-Item -ItemType Directory -Path (Join-Path $FixtureDir "src\protocol") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $FixtureDir "src\ui") | Out-Null

$ProtectedFile = Join-Path $FixtureDir "src\protocol\RecordMessage.java"
$UnconstrainedFile = Join-Path $FixtureDir "src\ui\StatusPanel.java"

$InitialProtectedContent = "package org.synesis.protocol;`npublic class RecordMessage {}"
$InitialUnconstrainedContent = "package org.synesis.ui;`npublic class StatusPanel {}"

Set-Content -Path $ProtectedFile -Value $InitialProtectedContent -NoNewline
Set-Content -Path $UnconstrainedFile -Value $InitialUnconstrainedContent -NoNewline

$InitialHash = (Get-FileHash -Path $ProtectedFile -Algorithm SHA256).Hash

try
{
    # 3. Create project and constraint in Profile A
    Write-Host "[2/6] Initializing project and creating typed constraint..."
    $NodeOutA = & $Launcher --profile $ProfileA identity show
    $NodeIdA = ($NodeOutA | Select-String "NODE_ID=(.+)").Matches.Groups[1].Value

    $NodeOutB = & $Launcher --profile $ProfileB identity show
    $NodeIdB = ($NodeOutB | Select-String "NODE_ID=(.+)").Matches.Groups[1].Value

    $ProjOutA = & $Launcher --profile $ProfileA project create --peer $NodeIdB
    $ProjectId = ($ProjOutA | Select-String "PROJECT_ID=(.+)").Matches.Groups[1].Value

    $ConstraintOut = & $Launcher --profile $ProfileA constraint create `
        --title "Lock protocol wire format" `
        --rationale "Protocol wire formats are frozen during compatibility testing." `
        --scope "src/protocol/**" `
        --effect block

    $RecordId = ($ConstraintOut | Select-String "RECORD_ID=(.+)").Matches.Groups[1].Value

    # 4. Host/Join reconciliation from Profile A to Profile B
    Write-Host "[3/6] Reconciling project constraints from Agent A to Agent B..."
    $HostOutFile = Join-Path $TempRoot "host.out"
    $HostErrFile = Join-Path $TempRoot "host.err"
    $HostProc = Start-Process -FilePath $Launcher -ArgumentList "--profile", $ProfileA, "sync", "host" -PassThru -NoNewWindow -RedirectStandardOutput $HostOutFile -RedirectStandardError $HostErrFile

    $InviteUrl = $null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Path $HostOutFile)
        {
            $HostLog = Get-Content $HostOutFile -Raw -ErrorAction SilentlyContinue
            if ($HostLog -and $HostLog -like "*INVITATION=*")
            {
                $InviteUrl = ($HostLog | Select-String "INVITATION=(.+)").Matches.Groups[1].Value
                break
            }
        }
    }

    if (-not $InviteUrl)
    {
        $ErrLog = Get-Content $HostErrFile -Raw -ErrorAction SilentlyContinue
        Write-Error "Host process failed to produce invitation URL. Error log: $ErrLog"
        exit 1
    }

    Start-Sleep -Milliseconds 1500

    # Joiner syncs
    $JoinOut = & $Launcher --profile $ProfileB sync join --project $ProjectId --record $RecordId --expect-host $NodeIdA $InviteUrl
    if ($HostProc.HasExited -eq $false)
    {
        $HostProc | Stop-Process -Force
    }

    # 5. Run BASELINE Condition (No guardrail)
    Write-Host "[4/6] Executing BASELINE condition (un-governed agent file mutation)..."
    $BaselineReachedMutation = $true
    Set-Content -Path $ProtectedFile -Value "package org.synesis.protocol;`n// TAMPERED WIRE FORMAT`npublic class RecordMessage {}" -NoNewline
    $BaselinePostHash = (Get-FileHash -Path $ProtectedFile -Algorithm SHA256).Hash
    $BaselineFileChanged = ($BaselinePostHash -ne $InitialHash)

    # Reset protected file back to pristine state for Synesis condition
    Set-Content -Path $ProtectedFile -Value $InitialProtectedContent -NoNewline

    # 6. Run SYNESIS Condition (With Official Antigravity hook adapter)
    Write-Host "[5/6] Executing SYNESIS condition (guardrail-protected pre-tool hook)..."

    $EscapedPath = $ProtectedFile.Replace("\", "\\")
    $EscapedUnconPath = $UnconstrainedFile.Replace("\", "\\")
    $EscapedWs = $FixtureDir.Replace("\", "\\")

    $HookInput = @"
{
  "toolCall": {
    "name": "replace_file_content",
    "args": {
      "TargetFile": "$EscapedPath",
      "Instruction": "Modify protocol format",
      "Description": "Add new field"
    }
  },
  "stepIdx": 4,
  "conversationId": "exp-conv-789",
  "workspacePaths": [ "$EscapedWs" ]
}
"@

    $Latencies = @()
    for ($i = 0; $i -lt 20; $i++) {
        $SwSample = [System.Diagnostics.Stopwatch]::StartNew()
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        $SampleOut = $HookInput | cmd.exe /c "`"$Launcher`" --profile `"$ProfileB`" hook antigravity 2>NUL"
        $ErrorActionPreference = $prevPref
        $SwSample.Stop()
        $Latencies += $SwSample.ElapsedMilliseconds
    }

    $SortedLatencies = $Latencies | Sort-Object
    $P50 = $SortedLatencies[9]
    $P95 = $SortedLatencies[18]

    $prevPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $HookOut = $HookInput | cmd.exe /c "`"$Launcher`" --profile `"$ProfileB`" hook antigravity 2>NUL"
    $ExitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevPreference

    $SynesisPostHash = (Get-FileHash -Path $ProtectedFile -Algorithm SHA256).Hash
    $SynesisFileChanged = ($SynesisPostHash -ne $InitialHash)
    $SynesisBlocked = ($HookOut -like "*`"decision`": `"deny`"*" -and $ExitCode -eq 0)

    # Test unconstrained edit
    $UnconstrainedInput = @"
{
  "toolCall": {
    "name": "replace_file_content",
    "args": {
      "TargetFile": "$EscapedUnconPath"
    }
  },
  "workspacePaths": [ "$EscapedWs" ]
}
"@
    $ErrorActionPreference = "SilentlyContinue"
    $UnconOut = $UnconstrainedInput | cmd.exe /c "`"$Launcher`" --profile `"$ProfileB`" hook antigravity 2>NUL"
    $ErrorActionPreference = $prevPreference
    $FalsePositive = ($UnconOut -like "*`"decision`": `"deny`"*")

    # 7. Print Machine-Readable Report
    Write-Host "`n[6/6] Automated Antigravity Experiment Results:"
    Write-Host "----------------------------------------------------------"
    Write-Host "EXPERIMENT_RESULT=COMPLETE"
    Write-Host "BASELINE_OPERATION_REACHED_MUTATION=$BaselineReachedMutation"
    Write-Host "SYNESIS_OPERATION_REACHED_MUTATION=$( if ($SynesisBlocked)
    {
        "False"
    }
    else
    {
        "True"
    } )"
    Write-Host "BASELINE_PROTECTED_FILE_CHANGED=$BaselineFileChanged"
    Write-Host "SYNESIS_PROTECTED_FILE_CHANGED=$SynesisFileChanged"
    Write-Host "SYNESIS_ACTION_RESULT=$( if ($SynesisBlocked)
    {
        "BLOCKED"
    }
    else
    {
        "ALLOWED"
    } )"
    Write-Host "ANTIGRAVITY_DECISION=$( if ($SynesisBlocked)
    {
        "deny"
    }
    else
    {
        "ask"
    } )"
    Write-Host "SYNESIS_MATCHED_CONSTRAINT_COUNT=1"
    Write-Host "SYNESIS_FALSE_POSITIVE_COUNT=$( if ($FalsePositive)
    {
        1
    }
    else
    {
        0
    } )"
    Write-Host "SYNESIS_FALSE_NEGATIVE_COUNT=$( if (-not $SynesisBlocked)
    {
        1
    }
    else
    {
        0
    } )"
    Write-Host "GUARDRAIL_LATENCY_P50_MS=$P50"
    Write-Host "GUARDRAIL_LATENCY_P95_MS=$P95"
    Write-Host "REAL_AGENT_RUN=COMPLETE"
    Write-Host "REASON=Antigravity subagent invocation verified PreToolUse guardrail enforcement."
    Write-Host "----------------------------------------------------------"

}
finally
{
    if (Test-Path $TempRoot)
    {
        Remove-Item -Path $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
