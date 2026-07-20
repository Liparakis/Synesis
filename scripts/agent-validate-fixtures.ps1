[CmdletBinding()]
param([string]$RepositoryRoot)
Set-StrictMode -Version Latest; $ErrorActionPreference = 'Stop'
$failures = 0
if ( [string]::IsNullOrWhiteSpace($RepositoryRoot))
{
    $RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
}
function Check([string]$Name, [bool]$ShouldPass, [scriptblock]$Action)
{
    try
    {
        & $Action; $passed = $true
    }
    catch
    {
        $passed = $false
    }; if ($passed -eq $ShouldPass)
    {
        Write-Output "PASS $Name"
    }
    else
    {
        Write-Error "FAIL $Name"; $script:failures++
    }
}
function Validate([string]$Dir)
{
    $tasks = Get-Content -Raw (Join-Path $Dir 'TASKS.md'); $current = Get-Content -Raw (Join-Path $Dir 'CURRENT.md'); $next = Get-Content -Raw (Join-Path $Dir 'NEXT_SESSION.md'); $a = [regex]::Matches($tasks, '(?ms)^##\s+(\S+)\s*\r?\n(?:(?!^##\s).)*?^\s*- Status:\s*ACTIVE\s*$'); if ($a.Count -ne 1)
    {
        throw 'active count'
    }; $id = $a[0].Groups[1].Value; $c = [regex]::Match($current, '(?m)^- Task ID:\s*(\S+)'); if (-not $c.Success -or $c.Groups[1].Value -ne $id)
    {
        throw 'task mismatch'
    }; if ($current -notmatch '(?m)^## Immediate next action')
    {
        throw 'missing current action'
    }; if ($next -notmatch '(?m)^- Exact next documentation action:')
    {
        throw 'missing next action'
    }; if ($current -match '(?mi)^\s*(continue|investigate|finish)\.?\s*$' -or $next -match '(?mi)^\s*(continue|investigate|finish)\.?\s*$')
    {
        throw 'vague action'
    }; foreach ($m in [regex]::Matches($tasks, '(?ms)^##\s+\S+.*?^\s*- Status:\s*DONE\s*$.*?(?=^##\s+|\z)'))
    {
        if ($m.Value -notmatch '(?i)evidence:\s*\S+')
        {
            throw 'done evidence'
        }
    }
}
$root = Join-Path $env:TEMP ('synesis-agent-fixtures-' + [guid]::NewGuid()); New-Item -ItemType Directory -Force $root | Out-Null; try
{
    $cases = @('valid-state', 'zero-active-tasks', 'multiple-active-tasks', 'current-task-mismatch', 'missing-next-action', 'done-without-evidence', 'placeholder-contract'); foreach ($case in $cases)
    {
        $d = Join-Path $root $case; New-Item -ItemType Directory -Force $d | Out-Null; $task = if ($case -eq 'zero-active-tasks')
        {
            "## A`n- Status: READY"
        }
        elseif ($case -eq 'multiple-active-tasks')
        {
            "## A`n- Status: ACTIVE`n## B`n- Status: ACTIVE"
        }
        elseif ($case -eq 'done-without-evidence')
        {
            "## A`n- Status: ACTIVE`n## B`n- Status: DONE`n- Evidence:"
        }
        else
        {
            "## A`n- Status: ACTIVE`n- Evidence: PASS"
        }; $curId = if ($case -eq 'current-task-mismatch')
        {
            'B'
        }
        else
        {
            'A'
        }; $cur = "# Current Task`n`n- Task ID: $curId`n`n## Immediate next action`n`nRun the exact validation command."; if ($case -eq 'missing-next-action')
        {
            $cur = "# Current Task`n`n- Task ID: A"
        }; $next = "# Next Session`n`n- Exact next documentation action: Run the exact validation command."; Set-Content (Join-Path $d 'TASKS.md') $task; Set-Content (Join-Path $d 'CURRENT.md') $cur; Set-Content (Join-Path $d 'NEXT_SESSION.md') $next; $shouldPass = $case -eq 'valid-state' -or $case -eq 'placeholder-contract'; Check $case $shouldPass { Validate $d }
    }; $bad = Join-Path $root 'corrupt'; Copy-Item (Join-Path $root 'valid-state') $bad -Recurse; Add-Content (Join-Path $bad 'CURRENT.md') "`n## Immediate next action`n`ncontinue"; Check 'intentional corruption rejected' $false { Validate $bad }
}
finally
{
    Remove-Item -LiteralPath $root -Recurse -Force
}; $validator = Join-Path $PSScriptRoot 'agent-validate-deferred.ps1'; $LASTEXITCODE = 0; & $validator -RepositoryRoot $RepositoryRoot; if ($LASTEXITCODE -ne 0) { exit 1 }; if ($failures)
{
    exit 1
}
