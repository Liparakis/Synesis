[CmdletBinding()]
param([string]$RepositoryRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ( [string]::IsNullOrWhiteSpace($RepositoryRoot))
{
    $RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
}
Set-Location $RepositoryRoot
$errors = 0
function Fail([string]$Message)
{
    Write-Error $Message; $script:errors++
}
$path = Join-Path $RepositoryRoot 'docs/agent/DEFERRED.md'
if (-not (Test-Path $path -PathType Leaf))
{
    Fail 'Missing docs/agent/DEFERRED.md'; exit 1
}
$text = Get-Content -Raw $path
$allowed = @('DEFERRED', 'RESEARCH_REQUIRED', 'BLOCKED', 'READY_FOR_PLANNING', 'SUPERSEDED', 'CANCELLED')
$required = @('Status', 'Area', 'Current verified capability', 'Missing capability', 'Reason deferred',
'Dependencies', 'Activation trigger', 'Evidence required before planning', 'Security questions',
'Privacy questions', 'Operational questions', 'Public-claims impact', 'Potential future task',
'Related ADRs', 'Related documents', 'Code extension seams', 'Last reviewed')
$entries = [regex]::Matches($text, '(?ms)^##\s+(SL-D-\d{3})\s+—[^\r\n]*\r?\n(?<body>.*?)(?=^##\s+|\z)')
if ($entries.Count -eq 0)
{
    Fail 'No deferred entries found'
}
$ids = @{ }
foreach ($entry in $entries)
{
    $id = $entry.Groups[1].Value
    if ( $ids.ContainsKey($id))
    {
        Fail "Duplicate deferred ID: $id"
    }
    else
    {
        $ids[$id] = $true
    }
    $body = $entry.Groups['body'].Value
    $statusMatch = [regex]::Match($body, '(?m)^\*\*Status:\*\*\s*(\S+)')
    if (-not $statusMatch.Success -or $allowed -notcontains $statusMatch.Groups[1].Value)
    {
        Fail "Invalid status: $id"
    }
    foreach ($field in $required)
    {
        if ($body -notmatch [regex]::Escape("**${field}:**"))
        {
            Fail "Missing field '$field': $id"
        }
    }
    if ($body -match '^\*\*Status:\*\*\s*SUPERSEDED' -and ($body -notmatch '(?i)replacement task|SL-[A-Z0-9-]+' -or $body -notmatch '(?i)CP-\d{4}'))
    {
        Fail "SUPERSEDED entry lacks replacement task/checkpoint: $id"
    }
    if ($body -match '^\*\*Status:\*\*\s*CANCELLED' -and $body -match '(?mi)^\*\*Reason deferred:\*\*\s*(?:None|Unassigned|\.+)\s*$')
    {
        Fail "CANCELLED entry lacks a reason: $id"
    }
    if ($body -match '^\*\*Status:\*\*\s*READY_FOR_PLANNING' -and $body -match '(?mi)^\*\*Activation trigger:\*\*\s*(?:None|Unassigned|\.+)\s*$')
    {
        Fail "READY_FOR_PLANNING entry lacks activation explanation: $id"
    }
}
$sourceFiles = Get-ChildItem src, docs -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notmatch '\\checkpoints\\' }
foreach ($file in $sourceFiles)
{
    $fileText = Get-Content -Raw $file.FullName
    foreach ($todo in [regex]::Matches($fileText, 'TODO\(?(SL-D-\d{3})\)?'))
    {
        if (-not $ids.ContainsKey($todo.Groups[1].Value))
        {
            Fail "TODO references missing deferred ID: $( $todo.Groups[1].Value ) in $( $file.FullName )"
        }
    }
    foreach ($line in ($fileText -split '\r?\n'))
    {
        if ($line -match '(?i)TODO[^\r\n]*(NAT traversal|NAT-PMP|UPnP|STUN|TURN|relay|reconnect|path migration|hole punching)' -and $line -notmatch 'TODO\(SL-D-\d{3}\)')
        {
            Fail "Known deferred TODO lacks SL-D ID in $( $file.FullName )"
        }
    }
}
if ($text -notmatch '(?m)^## SL-D-001')
{
    Fail 'NAT traversal entry missing'
}
if ($text -notmatch '(?m)^## SL-D-026')
{
    Fail 'reserved relay entry missing'
}
if ($errors -gt 0)
{
    exit 1
}
Write-Output "PASS deferred register ($( $entries.Count ) entries)"
