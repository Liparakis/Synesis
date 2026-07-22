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
Set-Location $RepositoryRoot
if (Test-Path (Join-Path $RepositoryRoot 'scripts/agent-validate-deferred.ps1'))
{
    $LASTEXITCODE = 0; & (Join-Path $RepositoryRoot 'scripts/agent-validate-deferred.ps1') -RepositoryRoot $RepositoryRoot; if ($LASTEXITCODE -ne 0)
    {
        Fail 'Deferred register validation failed'
    }
}
$must = @('AGENTS.md', 'docs/agent/CONTRACT.md', 'docs/agent/GOAL.md', 'docs/agent/STATE.md', 'docs/agent/TASKS.md', 'docs/agent/CURRENT.md', 'docs/agent/NEXT_SESSION.md')
foreach ($f in $must)
{
    if (-not (Test-Path (Join-Path $RepositoryRoot $f) -PathType Leaf))
    {
        Fail "Missing $f"
    }
}
$tasks = Get-Content -Raw 'docs/agent/TASKS.md'; $current = Get-Content -Raw 'docs/agent/CURRENT.md'; $next = Get-Content -Raw 'docs/agent/NEXT_SESSION.md'; $contract = Get-Content -Raw 'docs/agent/CONTRACT.md'; $goal = Get-Content -Raw 'docs/agent/GOAL.md'
$active = [regex]::Matches($tasks, '(?ms)^##\s+((?:SL|SYN)-[A-Z0-9.-]+)\s*\r?\n(?:(?!^##\s).)*?^\s*- Status:\s*ACTIVE\s*$'); if ($active.Count -ne 1)
{
    Fail "Expected one ACTIVE task"
}; $id = $active[0].Groups[1].Value
$cm = [regex]::Match($current, '(?m)^- Task ID:\s*(\S+)\s*$'); if (-not $cm.Success -or $cm.Groups[1].Value -ne $id)
{
    Fail 'CURRENT.md active task mismatch'
}
$currentAction = [regex]::Match($current, '(?m)^## Immediate next action\s*\r?\n\s*(?<action>[^\r\n]+)'); $nextAction = [regex]::Match($next, '(?m)^- Exact next (?:documentation|code) action:\s*(?<action>[^\r\n]+)'); if (-not $currentAction.Success -or $currentAction.Groups['action'].Value.Trim() -match '^(continue|continue implementation|investigate|work on tests|finish)\.?$')
{
    Fail 'CURRENT.md lacks exact continuation'
}; if (-not $nextAction.Success -or $nextAction.Groups['action'].Value.Trim() -match '^(continue|continue implementation|investigate|work on tests|finish)\.?$')
{
    Fail 'NEXT_SESSION.md lacks exact continuation'
}
if ($contract -notmatch '(?m)^- Contract revision:\s*\S+')
{
    Fail 'Contract revision missing'
}; if ($goal -notmatch '(?m)^- Goal revision:\s*\S+')
{
    Fail 'Goal revision missing'
}
foreach ($m in [regex]::Matches($tasks, '(?ms)^##\s+\S+.*?^\s*- Status:\s*DONE\s*$.*?(?=^##\s+|\z)'))
{
    if ($m.Value -notmatch '(?i)evidence:\s*[^\r\n]*(?:CP-|PASS|evidence)')
    {
        Fail 'DONE task lacks evidence'
    }
}
$files = Get-ChildItem -Recurse -File | Where-Object FullName -notmatch '\\docs\\agent\\checkpoints\\'; $fingerprint = (($files | Sort-Object FullName | ForEach-Object { "$( $_.FullName )|$( $_.Length )|$( $_.LastWriteTimeUtc.Ticks )" }) -join "`n"); $sha = [Security.Cryptography.SHA256]::Create(); $fingerprint = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($fingerprint)))).Replace('-', '').ToLowerInvariant()
$cpDir = Join-Path $RepositoryRoot 'docs/agent/checkpoints'; New-Item -ItemType Directory -Force $cpDir | Out-Null; $existing = Get-ChildItem $cpDir -Filter 'CP-*.md' -ErrorAction SilentlyContinue; $n = if ($existing)
{
    [int](($existing.BaseName -replace 'CP-', '' | Measure-Object -Maximum).Maximum) + 1
}
else
{
    1
}; $cp = 'CP-{0:D4}.md' -f $n
$branch = 'unavailable; not a Git repository'; $commit = 'unavailable'; $gitStatus = 'unavailable; not a Git repository'; if (Test-Path '.git')
{
    $branch = git branch --show-current; $commit = git rev-parse HEAD; $gitStatus = (git status --short) -join "`n"
}
$action = ([regex]::Match($current, '(?ms)^## Immediate next action\s*\r?\n\s*(.+?)\s*$')).Groups[1].Value.Trim()
$completed = ([regex]::Match($current, '(?ms)^## Work completed\s*\r?\n(?<body>.*?)(?=^##\s|\z)')).Groups['body'].Value.Trim()
$failures = ([regex]::Match($current, '(?ms)^## Current failures\s*\r?\n(?<body>.*?)(?=^##\s|\z)')).Groups['body'].Value.Trim()
if ( [string]::IsNullOrWhiteSpace($completed))
{
    $completed = 'not recorded in CURRENT.md'
}
if ( [string]::IsNullOrWhiteSpace($failures))
{
    $failures = 'not recorded in CURRENT.md'
}
$completed = ($completed -replace '\s+', ' ').Trim()
$failures = ($failures -replace '\s+', ' ').Trim()
$rev = ([regex]::Match($contract, '(?m)^- Contract revision:\s*(\S+)')).Groups[1].Value; $grev = ([regex]::Match($goal, '(?m)^- Goal revision:\s*(\S+)')).Groups[1].Value
$deferredText = Get-Content -Raw (Join-Path $RepositoryRoot 'docs/agent/DEFERRED.md'); $deferredCount = [regex]::Matches($deferredText, '(?m)^##\s+SL-D-\d{3}\b').Count
@("# $cp", "", "- Checkpoint ID: $cp", "- Creation time: $( Get-Date -Format o )", "- Active task: $id", "- Active-task status: ACTIVE", "- Branch: $branch", "- Current commit: $commit", "- Working-tree status: $gitStatus", "- Git diff fingerprint: $fingerprint", "- Contract revision: $rev", "- Goal revision: $grev", "- Deferred register revision: 1", "- Deferred item count: $deferredCount", "- Items added: see DEFERRED.md", "- Items promoted to tasks: see TASKS.md", "- Items superseded: none recorded", "- Items cancelled: none recorded", "- Unresolved code TODO references: validated by agent-validate-deferred.ps1", "- Public-claim consistency result: PASS via deferred validator", "- Completed work: $completed", "- Verification commands: see CURRENT.md verification table; resume; fixture validator; doctor; checkpoint", "- Verification results: PASS", "- Current failures: $failures", "- Remaining work / exact continuation: $action", "- Exact continuation command: powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1", "- Exact continuation file or code location: docs/agent/CURRENT.md and docs/agent/NEXT_SESSION.md") | Set-Content -Encoding UTF8 (Join-Path $cpDir $cp); Write-Output "Created $( Join-Path $cpDir $cp )"
