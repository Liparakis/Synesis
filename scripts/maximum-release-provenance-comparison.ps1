# Compares two private maximum-release records and their canonical artifact
# manifests. It proves only lawful release provenance properties: controlled
# diversification or reproducibility. It does not inspect or expose private
# mapping/retrace/native-symbol contents and does not treat ZIP timestamps as a
# reproducibility signal.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('cli', 'relay')]
  [string]$Component,

  [Parameter(Mandatory = $true)]
  [ValidateSet('Diversification', 'Reproducibility')]
  [string]$Mode,

  [Parameter(Mandatory = $true)]
  [string]$FirstRecord,

  [Parameter(Mandatory = $true)]
  [string]$SecondRecord,

  [string]$EvidenceFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require([bool]$Condition, [string]$Message)
{
  if (-not $Condition)
  {
    throw $Message
  }
}

function Sha256([string]$Path)
{
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ResolvePrivateFile([string]$Path, [string]$Label)
{
  if ([string]::IsNullOrWhiteSpace($Path) -or
      -not (Test-Path -LiteralPath $Path -PathType Leaf))
  {
    throw "MAXIMUM_RECORDS_NOT_SUPPLIED: $Label is missing"
  }
  return (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
}

function ReadProperties([string]$Path)
{
  $properties = [ordered]@{}
  foreach ($line in Get-Content -LiteralPath $Path)
  {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#'))
    {
      continue
    }
    $separator = $line.IndexOf('=')
    Require ($separator -gt 0) "Malformed private release record line"
    $key = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1)
    Require ($key -match '^[A-Za-z][A-Za-z0-9_.-]*$') "Malformed private release record key"
    Require (-not $properties.Contains($key)) "Duplicate private release record key: $key"
    $properties[$key] = $value
  }
  return $properties
}

function RequiredValue([System.Collections.Specialized.OrderedDictionary]$Properties, [string]$Key)
{
  Require ($Properties.Contains($Key) -and -not [string]::IsNullOrWhiteSpace([string]$Properties[$Key])) (
    "Private release record is missing required property: $Key")
  return [string]$Properties[$Key]
}

function ValidateManifest([string]$RecordPath, [System.Collections.Specialized.OrderedDictionary]$Properties)
{
  $manifestName = RequiredValue $Properties 'artifactManifest'
  Require ([IO.Path]::GetFileName($manifestName) -eq $manifestName) (
    'Private artifact manifest must be a file name, not a path')
  $manifestPath = Join-Path (Split-Path -Parent $RecordPath) $manifestName
  $manifestPath = ResolvePrivateFile $manifestPath 'private artifact manifest'
  $expectedHash = RequiredValue $Properties 'artifactManifestSha256'
  Require ($expectedHash -match '^[0-9a-fA-F]{64}$') (
    'Private artifact manifest SHA-256 is malformed')
  $actualHash = Sha256 $manifestPath
  Require ($actualHash -eq $expectedHash.ToLowerInvariant()) (
    'Private artifact manifest hash does not match release-record.properties')

  $lines = @(Get-Content -LiteralPath $manifestPath)
  Require ($lines.Count -ge 2 -and $lines[0] -match '^# SYNESIS_MAXIMUM(?:_RELAY)?_MANIFEST_V1$') (
    'Private artifact manifest header is missing or unsupported')
  $entries = @($lines | Select-Object -Skip 1)
  $paths = @()
  foreach ($entry in $entries)
  {
    Require ($entry -match '^([^\t]+)\t([0-9a-fA-F]{64})$') (
      'Private artifact manifest contains a malformed entry')
    $relativePath = $Matches[1]
    Require (-not $relativePath.StartsWith('/') -and -not $relativePath.Contains('\\') -and
      -not $relativePath.Split('/').Contains('..')) (
      'Private artifact manifest contains an unsafe relative path')
    $paths += $relativePath
  }
  $sortedPaths = @($paths | Sort-Object)
  Require (($paths -join "`n") -eq ($sortedPaths -join "`n")) (
    'Private artifact manifest entries are not sorted')
  Require ($paths.Count -eq @($paths | Sort-Object -Unique).Count) (
    'Private artifact manifest contains duplicate paths')
  return [ordered]@{
    fileName = $manifestName
    sha256 = $actualHash
    entryCount = $paths.Count
  }
}

function ReadPrivateRelease([string]$Path, [string]$Label)
{
  $recordPath = ResolvePrivateFile $Path "$Label release record"
  $properties = ReadProperties $recordPath
  Require ((RequiredValue $properties 'schema') -eq '1') "$Label release record schema is unsupported"
  Require ((RequiredValue $properties 'profile') -eq 'maximum-release') "$Label release record is not maximum-release"
  Require ((RequiredValue $properties 'component') -eq $Component) "$Label release record component does not match -Component"
  Require ((RequiredValue $properties 'dirtyTree') -eq 'false') "$Label release record is not from a clean checkout"
  Require ((RequiredValue $properties 'diversification') -eq 'verified') "$Label release record lacks verified diversification"
  $manifest = ValidateManifest $recordPath $properties
  return [ordered]@{
    label = $Label
    fileName = [IO.Path]::GetFileName($recordPath)
    properties = $properties
    manifest = $manifest
  }
}

function Fingerprint([string]$Value)
{
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  $hash = [Security.Cryptography.SHA256]::HashData($bytes)
  return ([Convert]::ToHexString($hash)).ToLowerInvariant()
}

$evidencePath = if ([string]::IsNullOrWhiteSpace($EvidenceFile))
{
  Join-Path (Get-Location) "build\maximum-release-provenance-comparison\$Component-$($Mode.ToLowerInvariant()).json"
}
else
{
  [IO.Path]::GetFullPath($EvidenceFile)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null

$result = [ordered]@{
  schema = 1
  component = $Component
  mode = $Mode
  comparison = [ordered]@{}
  status = 'NOT_STARTED'
}

$sharedFields = @(
  'sourceCommit',
  'dirtyTree',
  'protectorName',
  'protectorVersion',
  'configurationSha256',
  'lockfileCount',
  'lockfilesSha256',
  'gradleVersion',
  'javaRuntime',
  'javaToolchain',
  'nodeVersion',
  'npmVersion',
  'nativeToolchain',
  'commercialRings'
)

try
{
  $first = ReadPrivateRelease $FirstRecord 'first'
  $second = ReadPrivateRelease $SecondRecord 'second'
  $firstProperties = $first.properties
  $secondProperties = $second.properties

  $mismatches = @()
  foreach ($field in $sharedFields)
  {
    $firstValue = RequiredValue $firstProperties $field
    $secondValue = RequiredValue $secondProperties $field
    if ($firstValue -ne $secondValue)
    {
      $mismatches += $field
    }
  }
  Require ($mismatches.Count -eq 0) (
    "Release provenance inputs differ: $($mismatches -join ', ')")

  $firstReleaseId = RequiredValue $firstProperties 'releaseId'
  $secondReleaseId = RequiredValue $secondProperties 'releaseId'
  $firstSeed = RequiredValue $firstProperties 'seed'
  $secondSeed = RequiredValue $secondProperties 'seed'
  $firstManifestHash = $first.manifest.sha256
  $secondManifestHash = $second.manifest.sha256

  $result.comparison = [ordered]@{
    sharedProvenance = 'MATCH'
    sourceCommit = $firstProperties['sourceCommit']
    protector = "$($firstProperties['protectorName'])/$($firstProperties['protectorVersion'])"
    firstReleaseId = $firstReleaseId
    secondReleaseId = $secondReleaseId
    releaseIdsDiffer = ($firstReleaseId -ne $secondReleaseId)
    seedsDiffer = ($firstSeed -ne $secondSeed)
    seedFingerprints = @{
      first = Fingerprint $firstSeed
      second = Fingerprint $secondSeed
    }
    manifestHashesDiffer = ($firstManifestHash -ne $secondManifestHash)
    manifestHashes = @{
      first = $firstManifestHash
      second = $secondManifestHash
    }
    manifestEntryCounts = @{
      first = $first.manifest.entryCount
      second = $second.manifest.entryCount
    }
    archiveComparison = 'NOT_APPLICABLE; ZIP metadata is outside this manifest comparison'
  }

  if ($Mode -eq 'Diversification')
  {
    Require ($firstReleaseId -ne $secondReleaseId) (
      'Diversification requires distinct release IDs')
    Require ($firstSeed -ne $secondSeed) (
      'Diversification requires distinct release seeds')
    Require ($firstManifestHash -ne $secondManifestHash) (
      'Diversification requires distinct canonical artifact manifests')
    $result.status = 'PASS_DIVERSIFIED_RELEASES'
  }
  else
  {
    Require ($firstReleaseId -eq $secondReleaseId) (
      'Reproducibility requires the same release ID')
    Require ($firstSeed -eq $secondSeed) (
      'Reproducibility requires the same release seed')
    Require ($firstManifestHash -eq $secondManifestHash) (
      'Reproducibility requires identical canonical artifact manifests')
    $result.status = 'PASS_REPRODUCIBLE_RELEASES'
  }

  $result | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Output "MAXIMUM_PROVENANCE_COMPARISON_STATUS=$($result.status)"
  Write-Output "MAXIMUM_PROVENANCE_COMPARISON_EVIDENCE=$evidencePath"
}
catch
{
  $message = $_.Exception.Message
  $result.status = if ($message.StartsWith('MAXIMUM_RECORDS_NOT_SUPPLIED')) {
    'PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED'
  } else {
    'FAIL'
  }
  if ($result.status -eq 'PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED')
  {
    $result.openGate = 'Supply two clean licensed maximum-release private records and their verified canonical artifact manifests.'
  }
  else
  {
    $result.failure = $message
  }
  $result | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Output "MAXIMUM_PROVENANCE_COMPARISON_STATUS=$($result.status)"
  Write-Output "MAXIMUM_PROVENANCE_COMPARISON_EVIDENCE=$evidencePath"
  if ($result.status -eq 'PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED') { exit 2 }
  exit 1
}
