[CmdletBinding()]
param([string]$RepositoryRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ( [string]::IsNullOrWhiteSpace($RepositoryRoot))
{
    $RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
}

function Fail([string]$Message)
{
    Write-Error $Message; exit 1
}
function ReadText([string]$Path)
{
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf))
    {
        Fail "Missing required file: $Path"
    }; return Get-Content -Raw -LiteralPath $Path
}
function ExactAction([string]$Text, [string]$Label)
{
    $m = [regex]::Match($Text, '(?ms)^## Immediate next action\s*\r?\n\s*(.+?)\s*$')
    if (-not $m.Success -or $m.Groups[1].Value -match '^(continue|continue implementation|investigate|work on tests|finish)\.?$')
    {
        Fail "$Label lacks an exact immediate next action"
    }
    return $m.Groups[1].Value.Trim()
}

Set-Location $RepositoryRoot
$required = @('AGENTS.md', 'docs/agent/CONTRACT.md', 'docs/agent/GOAL.md', 'docs/agent/STATE.md', 'docs/agent/TASKS.md', 'docs/agent/CURRENT.md', 'docs/agent/DECISIONS.md', 'docs/agent/FAILED_ATTEMPTS.md', 'docs/agent/TEST_MATRIX.md', 'docs/agent/SESSION_LOG.md', 'docs/agent/NEXT_SESSION.md', 'docs/agent/DEFERRED.md', 'scripts/agent-resume.ps1', 'scripts/agent-checkpoint.ps1', 'scripts/agent-doctor.ps1', 'scripts/agent-validate-deferred.ps1')
$texts = @{ }
foreach ($file in $required)
{
    $texts[$file] = ReadText (Join-Path $RepositoryRoot $file)
}
$deferredValidator = Join-Path $RepositoryRoot 'scripts/agent-validate-deferred.ps1'; $LASTEXITCODE = 0; & $deferredValidator -RepositoryRoot $RepositoryRoot; if ($LASTEXITCODE -ne 0) { Fail 'Deferred register validation failed' }
$taskMatches = [regex]::Matches($texts['docs/agent/TASKS.md'], '(?ms)^##\s+((?:SL|SYN)-[A-Z0-9.-]+)\s*\r?\n(?:(?!^##\s).)*?^\s*- Status:\s*(ACTIVE)\s*$')
if ($taskMatches.Count -ne 1)
{
    Fail "Expected exactly one ACTIVE task; found $( $taskMatches.Count )"
}
$activeTask = $taskMatches[0].Groups[1].Value
$currentMatch = [regex]::Match($texts['docs/agent/CURRENT.md'], '(?m)^- Task ID:\s*(\S+)\s*$')
if (-not $currentMatch.Success -or $currentMatch.Groups[1].Value -ne $activeTask)
{
    Fail "CURRENT.md task does not match active TASKS.md task"
}
$action = ExactAction $texts['docs/agent/CURRENT.md'] 'CURRENT.md'
$contractRevision = ([regex]::Match($texts['docs/agent/CONTRACT.md'], '(?m)^- Contract revision:\s*(\S+)')).Groups[1].Value
$goalRevision = ([regex]::Match($texts['docs/agent/GOAL.md'], '(?m)^- Goal revision:\s*(\S+)')).Groups[1].Value
$checkpoint = Get-ChildItem (Join-Path $RepositoryRoot 'docs/agent/checkpoints') -Filter 'CP-*.md' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1; $checkpointName = if ($checkpoint)
{
    $checkpoint.Name
}
else
{
    'none'
}
$branch = 'unavailable; not a Git repository'; $status = 'unavailable; not a Git repository'; $commits = 'unavailable'
if (Test-Path (Join-Path $RepositoryRoot '.git'))
{
    $branch = (git branch --show-current); $status = ((git status --short) -join "`n"); $commits = ((git log -5 --oneline) -join "`n")
}
Write-Output 'Synesis Link Agent Resume'; Write-Output '========================='; Write-Output "Contract revision: $contractRevision"; Write-Output "Contract status: $( ([regex]::Match($texts['docs/agent/CONTRACT.md'], '(?m)^(?:- Status:|- Contract status:)\s*(\S+)')).Groups[1].Value )"; Write-Output "Goal revision: $goalRevision"; Write-Output "Active task: $activeTask"; Write-Output "Current checkpoint: $checkpointName"; Write-Output "Branch: $branch"; Write-Output "Working tree: $status"; Write-Output "Recent commits: $commits"; Write-Output "`nImmediate next action:`n  $action"; Write-Output "`nWarnings:"; if ($texts['docs/agent/CONTRACT.md'] -match 'Status:\s*PLACEHOLDER')
{
    Write-Output '  Product implementation is blocked until the complete contract is installed.'
}
foreach ($file in @('src', 'README.md'))
{
    $path = Join-Path $RepositoryRoot $file; if (Test-Path $path)
    {
        $newest = Get-ChildItem $path -Recurse -File | Where-Object FullName -notmatch '\\build\\' | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($newest -and $newest.LastWriteTime -gt (Get-Item (Join-Path $RepositoryRoot 'docs/agent/STATE.md')).LastWriteTime)
        {
            Write-Output "  Production or repository file may be newer than STATE.md: $( $newest.FullName )"
        }
    }
}
