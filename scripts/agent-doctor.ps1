[CmdletBinding()]
param([string]$RepositoryRoot)
Set-StrictMode -Version Latest; $ErrorActionPreference = 'Stop'
if ( [string]::IsNullOrWhiteSpace($RepositoryRoot))
{
    $RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
}
Set-Location $RepositoryRoot
$errors = 0; function Result([string]$Level, [string]$Text)
{
    Write-Output "${Level}: $Text"; if ($Level -eq 'ERROR')
    {
        $script:errors++
    }
}
$required = @('AGENTS.md', 'docs/agent/CONTRACT.md', 'docs/agent/GOAL.md', 'docs/agent/STATE.md', 'docs/agent/TASKS.md', 'docs/agent/CURRENT.md', 'docs/agent/NEXT_SESSION.md', 'docs/agent/DEFERRED.md', 'scripts/agent-resume.ps1', 'scripts/agent-checkpoint.ps1', 'scripts/agent-validate-deferred.ps1'); foreach ($f in $required)
{
    if (Test-Path $f)
    {
        Result PASS "exists: $f"
    }
    else
    {
        Result ERROR "missing: $f"
    }
}
$validator = Join-Path $RepositoryRoot 'scripts/agent-validate-deferred.ps1'; $LASTEXITCODE = 0; & $validator -RepositoryRoot $RepositoryRoot; if ($LASTEXITCODE -eq 0)
{
    Result PASS 'deferred register validation'
}
else
{
    Result ERROR 'deferred register validation'
}
if (Test-Path 'docs/agent/TASKS.md')
{
    $t = Get-Content -Raw 'docs/agent/TASKS.md'; $a = [regex]::Matches($t, '(?ms)^##\s+((?:SL|SYN)-[A-Z0-9-]+)\s*\r?\n(?:(?!^##\s).)*?^\s*- Status:\s*ACTIVE\s*$'); if ($a.Count -eq 1)
    {
        Result PASS 'exactly one active task'
    }
    else
    {
        Result ERROR "active task count is $( $a.Count )"
    }
}
if ((Get-Content -Raw 'docs/agent/CONTRACT.md') -match 'Status:\s*PLACEHOLDER')
{
    Result WARNING 'contract is still a placeholder'
}
if ((Get-Content -Raw 'docs/agent/CURRENT.md') -match '(?mi)^## Immediate next action\s*\r?\n\s*(continue|investigate|finish)')
{
    Result ERROR 'vague continuation'
}
else
{
    Result PASS 'continuation is concrete'
}
if (Test-Path 'src')
{
    $java = Get-ChildItem src -Recurse -Filter '*.java' -ErrorAction SilentlyContinue; if ($java)
    {
        Result WARNING 'Java production code exists; run Javadoc/package checks'
    }
}
if (Get-ChildItem -Recurse -File -ErrorAction SilentlyContinue | Where-Object Name -match '(^|\.)id_rsa$|\.pem$|\.key$')
{
    Result ERROR 'obvious secret/private-key filename detected'
}
$docFiles = @(Get-ChildItem docs -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object FullName); if ($docFiles.Count -gt 0 -and (Select-String -Path $docFiles -Pattern 'C:\\Users\\|/Users/' -Quiet))
{
    Result WARNING 'personal absolute path found in documentation'
}
if ($errors -gt 0)
{
    exit 1
}; exit 0
