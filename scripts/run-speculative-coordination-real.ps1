[CmdletBinding()]
param([string]$DemoRoot = (Join-Path $env:TEMP ("synesis-coordination-real-" + [Guid]::NewGuid().ToString("N"))), [int]$Port = 48123)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Get-Location).Path
$cli = Join-Path $repo 'cli\build\install\synesis\bin\synesis.bat'
if (-not (Test-Path -LiteralPath $cli)) { throw "Build the CLI first: $cli" }
New-Item -ItemType Directory -Force -Path $DemoRoot | Out-Null
$workA = Join-Path $DemoRoot 'worktree-a'; $workB = Join-Path $DemoRoot 'worktree-b'
$profileA = Join-Path $DemoRoot 'profile-a'; $profileB = Join-Path $DemoRoot 'profile-b'
$coordData = Join-Path $DemoRoot 'coordinator'; $stateA = Join-Path $profileA 'supervisor.state'
$logs = Join-Path $DemoRoot 'logs'; New-Item -ItemType Directory -Force -Path $logs | Out-Null
$branchA = 'synesis-demo-a-' + [Guid]::NewGuid().ToString('N'); $branchB = 'synesis-demo-b-' + [Guid]::NewGuid().ToString('N')
$coord = $null; $b = $null
function Run([string]$dir, [string[]]$commandArgs) {
    $arguments = if ($commandArgs.Count -gt 1) { $commandArgs[1..($commandArgs.Count-1)] } else { @() }
    $p = Start-Process -FilePath $commandArgs[0] -ArgumentList $arguments -WorkingDirectory $dir -Wait -PassThru -NoNewWindow
    if ($p.ExitCode -ne 0) { throw "command failed ($($p.ExitCode)): $($commandArgs -join ' ')" }
}
function CopySource([string]$relative) {
    $source = Join-Path $repo $relative
    $destA = Join-Path $workA $relative; $destB = Join-Path $workB $relative
    $parentA = Split-Path -Parent $destA; $parentB = Split-Path -Parent $destB
    New-Item -ItemType Directory -Force -Path $parentA,$parentB | Out-Null
    Copy-Item -LiteralPath $source -Destination $destA -Recurse -Force
    Copy-Item -LiteralPath $source -Destination $destB -Recurse -Force
}
function StopTree($process) {
    if ($process -and -not $process.HasExited) { & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null }
}
try {
    Run $repo @('git','worktree','add','-b',$branchA,$workA,'HEAD')
    Run $repo @('git','worktree','add','-b',$branchB,$workB,'HEAD')
    foreach ($path in @('build.gradle.kts','settings.gradle.kts','coordination','workspace\build.gradle.kts',
            'workspace\gradle.lockfile','workspace\src\main\java\org\synesis\workspace\guardrail\ActionGuardrail.java',
            'workspace\src\main\java\org\synesis\workspace\integration\antigravity\AntigravityHookAdapter.java',
            'workspace\src\main\java\org\synesis\workspace\integration\claude\ClaudeCodeHookAdapter.java',
            'cli\build.gradle.kts','cli\gradle.lockfile','cli\src\main\java\org\synesis\cli\SynesisCli.java',
            'cli\src\main\java\org\synesis\cli\command\CoordinationDemoCommand.java')) { CopySource $path }
    New-Item -ItemType Directory -Force -Path $profileA,$profileB,$coordData | Out-Null
    $env:SYNESIS_LINK_PROFILE = $profileA
    & $cli identity show --profile $profileA | Tee-Object (Join-Path $logs 'identity-a.log')
    $env:SYNESIS_LINK_PROFILE = $profileB
    & $cli identity show --profile $profileB | Tee-Object (Join-Path $logs 'identity-b.log')
    Remove-Item Env:SYNESIS_LINK_PROFILE -ErrorAction SilentlyContinue
    $nodeB = ((Get-Content (Join-Path $logs 'identity-b.log') | Select-String '^NODE_ID=').Line -replace '^NODE_ID=','').Trim()
    $nodeA = ((Get-Content (Join-Path $logs 'identity-a.log') | Select-String '^NODE_ID=').Line -replace '^NODE_ID=','').Trim()
    $project = [Guid]::NewGuid().ToString()
    $coordOut = Join-Path $logs 'coordinator.log'; $coordErr = Join-Path $logs 'coordinator.err'
    $coordArgs = @('coordination-demo','--role','coordinator','--project',$project,'--data',$coordData,
        '--identity',(Join-Path $profileB 'link'),'--port',$Port,'--duration-seconds','180')
    $coordStarted = (Get-Date).ToUniversalTime().ToString('o')
    $coord = Start-Process -FilePath $cli -ArgumentList $coordArgs -WorkingDirectory $repo -RedirectStandardOutput $coordOut -RedirectStandardError $coordErr -PassThru
    $ready = $false
    for ($i=0; $i -lt 60; $i++) { Start-Sleep -Milliseconds 100; if ((Test-Path $coordOut) -and ((Get-Content $coordOut -Raw) -match 'COORDINATOR_READY')) { $ready = $true; break } }
    if (-not $ready) { throw 'coordinator did not become ready' }
    Start-Sleep -Milliseconds 500
    $endpoint = "http://127.0.0.1:$Port/"
    $bOut = Join-Path $logs 'supervisor-b.log'; $bErr = Join-Path $logs 'supervisor-b.err'
    $bArgs = @('coordination-demo','--role','b','--project',$project,'--data',$coordData,'--identity',(Join-Path $profileB 'link'),
        '--endpoint',$endpoint,'--worktree',$workB,'--base-commit',(git -C $workB rev-parse HEAD))
    $bStarted = (Get-Date).ToUniversalTime().ToString('o')
    $b = Start-Process -FilePath $cli -ArgumentList $bArgs -WorkingDirectory $repo -RedirectStandardOutput $bOut -RedirectStandardError $bErr -PassThru
    $aOut = Join-Path $logs 'supervisor-a-first.log'; $aErr = Join-Path $logs 'supervisor-a-first.err'
    $aArgs = @('coordination-demo','--role','a','--project',$project,'--data',$coordData,'--identity',(Join-Path $profileA 'link'),
        '--endpoint',$endpoint,'--profile',(Join-Path $profileA 'local'),'--worktree',$workA,
        '--base-commit',(git -C $workA rev-parse HEAD),'--owner-node',$nodeB,'--state',$stateA)
    $aStarted = (Get-Date).ToUniversalTime().ToString('o')
    $a = Start-Process -FilePath $cli -ArgumentList $aArgs -WorkingDirectory $repo -RedirectStandardOutput $aOut -RedirectStandardError $aErr -PassThru -Wait
    if ($a.ExitCode -ne 75) { throw "Supervisor A did not request the controlled restart: $($a.ExitCode)" }
    $aResumeOut = Join-Path $logs 'supervisor-a-resume.log'; $aResumeErr = Join-Path $logs 'supervisor-a-resume.err'
    $arArgs = @('coordination-demo','--role','a','--project',$project,'--data',$coordData,'--identity',(Join-Path $profileA 'link'),
        '--endpoint',$endpoint,'--profile',(Join-Path $profileA 'local'),'--worktree',$workA,
        '--base-commit',(git -C $workA rev-parse HEAD),'--owner-node',$nodeB,'--state',$stateA,'--resume')
    $arStarted = (Get-Date).ToUniversalTime().ToString('o')
    $ar = Start-Process -FilePath $cli -ArgumentList $arArgs -WorkingDirectory $repo -RedirectStandardOutput $aResumeOut -RedirectStandardError $aResumeErr -PassThru
    $b.WaitForExit()
    if ($b.ExitCode -ne 0 -and -not ((Get-Content $bOut -Raw) -match 'OWNER_IMPLEMENTATION_COMMIT=')) {
        throw "Supervisor B failed: $($b.ExitCode)"
    }
    $ar.WaitForExit()
    if ($ar.ExitCode -ne 0 -and -not ((Get-Content $aResumeOut -Raw) -match 'PREDICTION_STATE=RETIRED')) {
        throw "Supervisor A resume failed: $($ar.ExitCode)"
    }
    Get-Content $coordOut,$bOut,$aOut,$aResumeOut | Set-Content (Join-Path $DemoRoot 'process-transcript.log')
    [pscustomobject]@{ demoRoot=$DemoRoot; project=$project; nodeA=$nodeA; nodeB=$nodeB; branchA=$branchA; branchB=$branchB; endpoint=$endpoint;
        coordinatorPid=$coord.Id; supervisorBPid=$b.Id; supervisorAFirstPid=$a.Id; supervisorAResumePid=$ar.Id;
        coordinatorStarted=$coordStarted; supervisorBStarted=$bStarted; supervisorAFirstStarted=$aStarted; supervisorAResumeStarted=$arStarted;
        profileA=$profileA; profileB=$profileB; worktreeA=$workA; worktreeB=$workB; initialCursorA=0; initialCursorB=0 } |
        ConvertTo-Json | Set-Content (Join-Path $DemoRoot 'summary.json')
    Write-Output "DEMO_ROOT=$DemoRoot"
    Write-Output "PROJECT=$project"
    Write-Output "NODE_A=$nodeA"
    Write-Output "NODE_B=$nodeB"
    Write-Output "BRANCH_A=$branchA"
    Write-Output "BRANCH_B=$branchB"
    Write-Output "ENDPOINT=$endpoint"
} finally {
    StopTree $coord
    StopTree $b
    Remove-Item Env:SYNESIS_LINK_PROFILE -ErrorAction SilentlyContinue
}
