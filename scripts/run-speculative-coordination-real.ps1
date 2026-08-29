[CmdletBinding()]
param(
    [string]$DemoRoot = (Join-Path $env:TEMP ("synesis-coordination-cli-" + [Guid]::NewGuid().ToString("N"))),
    [int]$Port = 48123,
    [string]$CliPath = ""
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Get-Location).Path
$cli = if ( [string]::IsNullOrWhiteSpace($CliPath))
{
    Join-Path $repo 'cli\build\install\synesis\bin\synesis.bat'
}
else
{
    $CliPath
}
if (-not (Test-Path -LiteralPath $cli))
{
    throw "Build the CLI first: $cli"
}

New-Item -ItemType Directory -Force -Path $DemoRoot | Out-Null
$project = Join-Path $DemoRoot 'external-project'
$profileA = Join-Path $DemoRoot 'requester-profile'
$profileB = Join-Path $DemoRoot 'owner-profile'
$profileCoordinator = Join-Path $DemoRoot 'coordinator-profile'
$coordData = Join-Path $DemoRoot 'coordinator-data'
$logs = Join-Path $DemoRoot 'logs'
New-Item -ItemType Directory -Force -Path $project,$profileA,$profileB,$profileCoordinator,$coordData,$logs | Out-Null

function Invoke-Cli([string[]]$Arguments, [int]$ExpectedExit = 0)
{
    $previousAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = (& $cli @Arguments 2>&1 | Out-String)
    $ErrorActionPreference = $previousAction
    if ($LASTEXITCODE -ne $ExpectedExit)
    {
        throw "CLI failed with $LASTEXITCODE (expected $ExpectedExit): synesis $( $Arguments -join ' ' )`n$output"
    }
    return $output
}
function Value([string]$Text, [string]$Name)
{
    $line = ($Text -split "`r?`n" | Where-Object { $_ -match "^$Name=" } | Select-Object -First 1)
    if ($null -eq $line)
    {
        throw "Missing $Name in output:`n$Text"
    }
    return ($line -split '=', 2)[1].Trim()
}
function StopTree($Process)
{
    if ($Process -and -not $Process.HasExited)
    {
        & taskkill.exe /PID $Process.Id /T /F 2> $null | Out-Null
    }
}

$coordinator = $null; $supervisor = $null
try
{
    & git init -q $project
    & git -C $project config user.email test@synesis.local
    & git -C $project config user.name Synesis
    Set-Content (Join-Path $project 'README.md') 'external project'
    & git -C $project add README.md
    & git -C $project commit -q -m 'external baseline'
    $init = Invoke-Cli @('init', '--project', $project)
    $projectId = Value $init 'PROJECT_ID'
    $baseCommit = (& git -C $project rev-parse HEAD).Trim()
    $nodeA = Value (Invoke-Cli @('identity', 'show', '--profile', $profileA)) 'NODE_ID'
    $nodeB = Value (Invoke-Cli @('identity', 'show', '--profile', $profileB)) 'NODE_ID'

    $coordOut = Join-Path $logs 'coordinator.log'; $coordErr = Join-Path $logs 'coordinator.err'
    $coordinator = Start-Process -FilePath $cli -ArgumentList (@('coordination', 'serve', '--project', $project,
    '--data', $coordData, '--identity', $profileCoordinator, '--port', $Port, '--duration-seconds', '60')) `
        -WorkingDirectory $repo -RedirectStandardOutput $coordOut -RedirectStandardError $coordErr -PassThru -WindowStyle Hidden
    $ready = $false
    for ($i = 0; $i -lt 100; $i++) {
        Start-Sleep -Milliseconds 100
        if ((Test-Path $coordOut) -and ((Get-Content $coordOut -Raw) -match 'COORDINATION_SERVE_READY'))
        {
            $ready = $true; break
        }
    }
    if (-not $ready)
    {
        throw 'coordinator did not become ready'
    }
    $endpoint = "http://127.0.0.1:$Port/"

    $supOut = Join-Path $logs 'supervisor-owner.log'; $supErr = Join-Path $logs 'supervisor-owner.err'
    $supervisor = Start-Process -FilePath $cli -ArgumentList (@('supervisor', 'run', '--project', $project,
    '--endpoint', $endpoint, '--profile', $profileB, '--supervisor', 'sup-b', '--worker', 'worker-b',
    '--cursor', (Join-Path $profileB 'coordination.cursor'), '--duration-seconds', '15')) `
        -RedirectStandardOutput $supOut -RedirectStandardError $supErr -PassThru -WindowStyle Hidden

    $taskOut = Invoke-Cli @('task', 'create', '--project', $project, '--endpoint', $endpoint, '--profile', $profileA,
    '--supervisor', 'sup-a', '--worker', 'worker-a', '--title', 'prediction status', '--capability', 'workspace.prediction-status')
    $task = Value $taskOut 'TASK_ID'
    Invoke-Cli @('task', 'claim', '--project', $project, '--endpoint', $endpoint, '--profile', $profileB,
    '--supervisor', 'sup-b', '--worker', 'worker-b', '--task', $task) | Out-Null
    Invoke-Cli @('ownership', 'claim', '--project', $project, '--endpoint', $endpoint, '--profile', $profileB,
    '--supervisor', 'sup-b', '--task', $task, '--capability', 'workspace.prediction-status', '--scope', 'project-scope') | Out-Null

    $predictionOut = Invoke-Cli @('prediction', 'create', '--project', $project, '--endpoint', $endpoint, '--profile', $profileA,
    '--supervisor', 'sup-a', '--worker', 'worker-a', '--task', $task, '--capability', 'workspace.prediction-status',
    '--owner-node', $nodeB, '--owner-supervisor', 'sup-b', '--scope', 'project-scope', '--base-commit', $baseCommit,
    '--base-scope-hash', 'src=absent', '--purpose', 'external acceptance', '--inputs', 'none', '--outputs', 'status',
    '--behavior', 'returns status', '--errors', 'missing rejected', '--side-effects', 'none', '--invariants', 'bounded',
    '--compatibility', 'Java 25', '--performance', 'normal', '--concurrency', 'single-threaded', '--acceptance-test', 'cli')
    $prediction = Value $predictionOut 'PREDICTION_ID'

    Invoke-Cli @('prediction', 'respond', '--project', $project, '--endpoint', $endpoint, '--profile', $profileA,
    '--supervisor', 'sup-a', '--worker', 'worker-a', '--prediction', $prediction, '--action', 'receive') 10 | Out-Null
    $commonOwner = @('--project', $project, '--endpoint', $endpoint, '--profile', $profileB, '--supervisor', 'sup-b', '--worker', 'worker-b', '--prediction', $prediction)
    Invoke-Cli (@('prediction', 'respond') + $commonOwner + @('--action', 'receive')) | Out-Null
    Invoke-Cli (@('prediction', 'respond') + $commonOwner + @('--action', 'exact')) | Out-Null
    foreach ($stage in @('implementation-started', 'patch-ready', 'available'))
    {
        Invoke-Cli (@('prediction', 'publish') + $commonOwner + @('--stage', $stage, '--commit', $baseCommit)) | Out-Null
    }
    Invoke-Cli @('speculation', 'prepare', '--project', $project, '--prediction', $prediction, '--base-commit', $baseCommit) | Out-Null
    Invoke-Cli @('integration', 'gate', '--project', $project, '--endpoint', $endpoint, '--prediction', $prediction) | Out-Null
    Invoke-Cli @('speculation', 'validate', '--project', $project, '--endpoint', $endpoint, '--profile', $profileA, '--prediction', $prediction) | Out-Null
    Invoke-Cli @('speculation', 'retire', '--project', $project, '--endpoint', $endpoint, '--profile', $profileA, '--prediction', $prediction) | Out-Null
    $show = Invoke-Cli @('prediction', 'show', '--project', $project, '--endpoint', $endpoint, '--prediction', $prediction)
    if ($show -notmatch 'STATE=RETIRED')
    {
        throw "prediction did not retire`n$show"
    }

    $supervisor.WaitForExit()
    $supervisorTranscript = if (Test-Path $supOut)
    {
        Get-Content $supOut -Raw
    }
    else
    {
        ''
    }
    if ($supervisorTranscript -notmatch 'EVENT sequence=')
    {
        throw 'supervisor did not receive live events'
    }
    $transcript = Join-Path $DemoRoot 'process-transcript.log'
    Get-Content $coordOut,$supOut | Set-Content $transcript
    [pscustomobject]@{
        demoRoot = $DemoRoot; project = $project; projectId = $projectId; endpoint = $endpoint;
        nodeA = $nodeA; nodeB = $nodeB; task = $task; prediction = $prediction; baseCommit = $baseCommit;
        coordinatorPid = $coordinator.Id; supervisorPid = $supervisor.Id; transcript = $transcript
    } |
            ConvertTo-Json | Set-Content (Join-Path $DemoRoot 'summary.json')
    Write-Output "DEMO_ROOT=$DemoRoot"
    Write-Output "PROJECT=$project"
    Write-Output "PROJECT_ID=$projectId"
    Write-Output "TASK_ID=$task"
    Write-Output "PREDICTION_ID=$prediction"
    Write-Output "ENDPOINT=$endpoint"
    Write-Output "ACCEPTANCE=PASS"
}
finally
{
    StopTree $supervisor
    StopTree $coordinator
}
