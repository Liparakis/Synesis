# Audits Synesis-owned native launchers in extracted customer archives.
# It records PE symbols, debug directories, exports, trim-path build metadata,
# source-path signals, and platform signing status without changing an archive.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('cli', 'relay')]
  [string]$Component,

  [Parameter(Mandatory = $true)]
  [string]$DeveloperArchive,

  [string]$ProtectionLiteArchive,

  [string]$MaximumArchive,

  [string]$EvidenceFile,

  [switch]$KeepExtracted
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Require([bool]$Condition, [string]$Message)
{
  if (-not $Condition)
  {
    throw $Message
  }
}

Require (
  -not [string]::IsNullOrWhiteSpace($ProtectionLiteArchive) -or
  -not [string]::IsNullOrWhiteSpace($MaximumArchive)
) 'Supply at least one protected archive: -ProtectionLiteArchive or -MaximumArchive'

$evidencePath = if ([string]::IsNullOrWhiteSpace($EvidenceFile))
{
  Join-Path (Get-Location) "build\release-native-hardening\$Component\native-hardening.json"
}
else
{
  [IO.Path]::GetFullPath($EvidenceFile)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $temporaryRoot ("synesis-native-hardening-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
Require ($tempRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) (
  "Native audit temporary directory escaped the system temporary root: $tempRoot")

$forbiddenNativeNames = @(
  '.pdb', '.dSYM', '.debug', '.dwo', '.sym', '.map', '.sourcemap'
)
$sourcePathPatterns = @(
  '(?i)[A-Z]:[\\/](?:users|home|workspace|src)[\\/]',
  '(?i)(?:^|[\\/])(?:src|source|workspace)[\\/]'
)

$result = [ordered]@{
  schema = 1
  component = $Component
  scope = 'EXTRACTED_CUSTOMER_ARCHIVES_ONLY; SYNESIS-OWNED-NATIVE-PE-STATIC-AUDIT'
  profiles = [ordered]@{}
  comparisons = [ordered]@{}
  toolchain = [ordered]@{
    go = 'NOT_CHECKED'
    goPath = ''
  }
  status = 'NOT_STARTED'
  nativeHardeningStatus = 'NOT_STARTED'
  nativeSigningStatus = 'NOT_CHECKED'
  openGates = @()
}

function Sha256([string]$Path)
{
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ResolveArchive([string]$Path, [string]$Label)
{
  Require (-not [string]::IsNullOrWhiteSpace($Path)) "$Label archive path is empty"
  $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
  Require (Test-Path -LiteralPath $resolved -PathType Leaf) "$Label archive is missing: $resolved"
  Require ([IO.Path]::GetExtension($resolved).Equals('.zip', [StringComparison]::OrdinalIgnoreCase)) (
    "$Label archive must be a ZIP: $resolved")
  return $resolved
}

function RelativePath([string]$Root, [string]$Path)
{
  $relative = $Path.Substring($Root.Length)
  return $relative.TrimStart([char]'\', [char]'/').Replace('\', '/')
}

function FindBundleRoot([string]$ExtractedRoot, [string]$Label)
{
  $candidates = @((Get-Item -LiteralPath $ExtractedRoot))
  $candidates += @(Get-ChildItem -LiteralPath $ExtractedRoot -Directory -Force)
  $matches = @($candidates | Where-Object {
      Test-Path -LiteralPath (Join-Path $_.FullName 'bin') -PathType Container
    })
  Require ($matches.Count -eq 1) (
    "$Label archive must contain exactly one bundle root with bin/: $ExtractedRoot")
  return $matches[0].FullName
}

function ReadProfile([string]$BundleRoot)
{
  $marker = Join-Path $BundleRoot 'PROTECTION_PROFILE'
  if (Test-Path -LiteralPath $marker -PathType Leaf)
  {
    return (Get-Content -Raw -LiteralPath $marker).Trim()
  }
  return 'developer'
}

function ReadUInt16([byte[]]$Bytes, [int]$Offset, [string]$Label)
{
  Require ($Offset -ge 0 -and $Offset + 2 -le $Bytes.Length) "Truncated PE field: $Label"
  return [BitConverter]::ToUInt16($Bytes, $Offset)
}

function ReadUInt32([byte[]]$Bytes, [int]$Offset, [string]$Label)
{
  Require ($Offset -ge 0 -and $Offset + 4 -le $Bytes.Length) "Truncated PE field: $Label"
  return [BitConverter]::ToUInt32($Bytes, $Offset)
}

function MapRva([object[]]$Sections, [uint32]$Rva)
{
  foreach ($section in $Sections)
  {
    $span = [Math]::Max([uint32]$section.virtualSize, [uint32]$section.rawSize)
    if ($Rva -ge $section.virtualAddress -and $Rva -lt ($section.virtualAddress + $span))
    {
      $offset = [uint64]$section.rawPointer + ($Rva - $section.virtualAddress)
      if ($offset -le [int]::MaxValue)
      {
        return [int]$offset
      }
    }
  }
  return $null
}

function ReadAscii([byte[]]$Bytes, [int]$Offset, [int]$Length)
{
  if ($null -eq $Offset -or $Offset -lt 0 -or $Offset -ge $Bytes.Length)
  {
    return ''
  }
  $safeLength = [Math]::Min($Length, $Bytes.Length - $Offset)
  return [Text.Encoding]::ASCII.GetString($Bytes, $Offset, $safeLength)
}

function ParsePe([string]$Path)
{
  $bytes = [IO.File]::ReadAllBytes($Path)
  Require ($bytes.Length -ge 0x40) "Native binary is too small to be PE: $Path"
  Require ($bytes[0] -eq 0x4d -and $bytes[1] -eq 0x5a) "Native binary lacks an MZ header: $Path"

  $peOffset = [int](ReadUInt32 $bytes 0x3c 'PE header offset')
  Require ($peOffset -ge 0 -and $peOffset + 24 -le $bytes.Length) "PE header offset is invalid: $Path"
  Require ($bytes[$peOffset] -eq 0x50 -and $bytes[$peOffset + 1] -eq 0x45 -and
    $bytes[$peOffset + 2] -eq 0 -and $bytes[$peOffset + 3] -eq 0) "PE signature is invalid: $Path"

  $coff = $peOffset + 4
  $machine = ReadUInt16 $bytes $coff 'machine'
  $sectionCount = ReadUInt16 $bytes ($coff + 2) 'section count'
  $symbolPointer = ReadUInt32 $bytes ($coff + 8) 'COFF symbol pointer'
  $symbolCount = ReadUInt32 $bytes ($coff + 12) 'COFF symbol count'
  $optionalSize = ReadUInt16 $bytes ($coff + 16) 'optional header size'
  $optional = $coff + 20
  Require ($optional + $optionalSize -le $bytes.Length) "Optional PE header is truncated: $Path"
  $magic = ReadUInt16 $bytes $optional 'optional header magic'
  $isPe32Plus = $magic -eq 0x20b
  Require ($isPe32Plus -or $magic -eq 0x10b) "Unsupported PE optional header: $Path"
  $directoryOffset = $optional + $(if ($isPe32Plus) { 112 } else { 96 })
  $directoryCountOffset = $optional + $(if ($isPe32Plus) { 108 } else { 92 })
  $directoryCount = ReadUInt32 $bytes $directoryCountOffset 'data-directory count'

  $sectionTable = $optional + $optionalSize
  $sections = [System.Collections.Generic.List[object]]::new()
  for ($index = 0; $index -lt $sectionCount; $index++)
  {
    $offset = $sectionTable + ($index * 40)
    Require ($offset + 40 -le $bytes.Length) "PE section table is truncated: $Path"
    $sections.Add([pscustomobject]@{
        name = (ReadAscii $bytes $offset 8).Trim([char]0)
        virtualSize = ReadUInt32 $bytes ($offset + 8) 'section virtual size'
        virtualAddress = ReadUInt32 $bytes ($offset + 12) 'section virtual address'
        rawSize = ReadUInt32 $bytes ($offset + 16) 'section raw size'
        rawPointer = ReadUInt32 $bytes ($offset + 20) 'section raw pointer'
      })
  }

  $exportRva = [uint32]0
  $exportSize = [uint32]0
  $debugRva = [uint32]0
  $debugSize = [uint32]0
  if ($directoryCount -gt 0)
  {
    $exportRva = ReadUInt32 $bytes $directoryOffset 'export directory RVA'
    $exportSize = ReadUInt32 $bytes ($directoryOffset + 4) 'export directory size'
  }
  if ($directoryCount -gt 6)
  {
    $debugRva = ReadUInt32 $bytes ($directoryOffset + (6 * 8)) 'debug directory RVA'
    $debugSize = ReadUInt32 $bytes ($directoryOffset + (6 * 8) + 4) 'debug directory size'
  }

  $exportNames = 0
  if ($exportRva -ne 0 -and $exportSize -ne 0)
  {
    $exportOffset = MapRva $sections $exportRva
    if ($null -ne $exportOffset -and $exportOffset + 40 -le $bytes.Length)
    {
      $exportNames = [int](ReadUInt32 $bytes ($exportOffset + 24) 'export name count')
    }
  }

  $text = [Text.Encoding]::ASCII.GetString($bytes)
  $pathSignals = @(
    $sourcePathPatterns | Where-Object { $text -match $_ }
  )
  $debugSectionNames = @(
    $sections | Where-Object { $_.name -match '(?i)(debug|dwarf|stab)' } | ForEach-Object { $_.name }
  )

  $goMetadata = [ordered]@{
    available = $false
    trimpath = 'NOT_AVAILABLE'
    buildMode = ''
    goVersion = ''
    module = ''
    vcsRevision = ''
    output = @()
  }
  $goCommand = Get-Command go -ErrorAction SilentlyContinue
  if ($null -ne $goCommand)
  {
    $goMetadata.available = $true
    $goMetadata.output = @(& $goCommand.Source version -m $Path 2>&1 | ForEach-Object { $_.ToString() })
    $goMetadata.trimpath = if ($goMetadata.output -match 'build\s+-trimpath=true') { 'true' } else { 'false' }
    $goMetadata.buildMode = [string]($goMetadata.output | Where-Object { $_ -match '^\s*build\s+-buildmode=' } | Select-Object -First 1)
    $goMetadata.goVersion = [string]($goMetadata.output | Where-Object { $_ -match ':\s+go[0-9]' } | Select-Object -First 1)
    $goMetadata.module = [string]($goMetadata.output | Where-Object { $_ -match '^\s*mod\s+' } | Select-Object -First 1)
    $goMetadata.vcsRevision = [string]($goMetadata.output | Where-Object { $_ -match '^\s*build\s+vcs\.revision=' } | Select-Object -First 1)
  }

  $signature = [ordered]@{
    status = 'NOT_CHECKED'
    subject = ''
    message = ''
  }
  try
  {
    $authenticode = Get-AuthenticodeSignature -LiteralPath $Path
    $signature.status = [string]$authenticode.Status
    $signature.subject = if ($null -ne $authenticode.SignerCertificate) {
      [string]$authenticode.SignerCertificate.Subject
    } else { '' }
    $signature.message = [string]$authenticode.StatusMessage
  }
  catch
  {
    $signature.status = 'NOT_AVAILABLE'
    $signature.message = $_.Exception.Message
  }

  return [ordered]@{
    path = $Path
    bytes = [long]$bytes.Length
    sha256 = Sha256 $Path
    format = if ($isPe32Plus) { 'PE32+' } else { 'PE32' }
    machine = ('0x{0:x4}' -f $machine)
    sectionCount = [int]$sectionCount
    sections = @($sections | ForEach-Object { $_.name })
    coffSymbolPointer = [uint32]$symbolPointer
    coffSymbolCount = [uint32]$symbolCount
    debugDirectoryRva = [uint32]$debugRva
    debugDirectorySize = [uint32]$debugSize
    debugDirectoryPresent = ($debugRva -ne 0 -and $debugSize -ne 0)
    debugSectionNames = $debugSectionNames
    exportDirectoryRva = [uint32]$exportRva
    exportDirectorySize = [uint32]$exportSize
    exportNameCount = [int]$exportNames
    sourcePathSignals = $pathSignals
    goMetadata = $goMetadata
    signing = $signature
  }
}

function OwnedNativeRelativePaths([string]$BundleRoot)
{
  if ($Component -eq 'relay')
  {
    return @()
  }
  $files = @(Get-ChildItem -LiteralPath (Join-Path $BundleRoot 'bin') -File -Force)
  return @($files | Where-Object {
      $_.Name -in @('synesis-mcp.exe', 'synesis-mcp', 'synesis-installer.exe', 'synesis-installer')
    } | ForEach-Object { RelativePath $BundleRoot $_.FullName })
}

function AuditProfile([string]$Label, [string]$ArchivePath, [string]$ExpectedProfile)
{
  $resolved = ResolveArchive $ArchivePath $Label
  $extractRoot = Join-Path $tempRoot ($Label.ToLowerInvariant() + '-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
  [IO.Compression.ZipFile]::ExtractToDirectory($resolved, $extractRoot)
  $bundleRoot = FindBundleRoot $extractRoot $Label
  $profile = ReadProfile $bundleRoot
  Require ($profile -eq $ExpectedProfile) "$Label archive profile is '$profile', expected '$ExpectedProfile'"

  $allFiles = @(Get-ChildItem -LiteralPath $bundleRoot -Recurse -File -Force)
  $forbidden = @($allFiles | Where-Object {
      $lower = $_.Name.ToLowerInvariant()
      $forbiddenNativeNames | Where-Object { $lower.EndsWith($_.ToLowerInvariant()) }
    } | ForEach-Object { RelativePath $bundleRoot $_.FullName })
  $nativePaths = @(OwnedNativeRelativePaths $bundleRoot)
  if ($Component -eq 'cli')
  {
    Require ($nativePaths.Count -eq 2) (
      "$Label archive must contain exactly two Synesis-owned native launchers under bin/: $($nativePaths -join ', ')")
  }

  $binaries = [ordered]@{}
  foreach ($relative in $nativePaths)
  {
    $path = Join-Path $bundleRoot ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
    $record = ParsePe $path
    $record.path = $relative
    $binaries[$relative] = $record
  }

  $checks = [ordered]@{
    ownedNativeFiles = if ($Component -eq 'cli' -and $nativePaths.Count -eq 2) { 'PASS' } else { 'NOT_APPLICABLE' }
    peFormat = if (@($binaries.Values | Where-Object { $_.format -notin @('PE32', 'PE32+') }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    coffSymbols = if (@($binaries.Values | Where-Object { $_.coffSymbolCount -ne 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    debugDirectory = if (@($binaries.Values | Where-Object { $_.debugDirectoryPresent }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    debugSections = if (@($binaries.Values | Where-Object { $_.debugSectionNames.Count -gt 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    namedExports = if (@($binaries.Values | Where-Object { $_.exportNameCount -ne 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    sourcePaths = if (@($binaries.Values | Where-Object { $_.sourcePathSignals.Count -gt 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    privateNativeFiles = if ($forbidden.Count -eq 0) { 'PASS' } else { 'FAIL' }
    goTrimpath = if ($binaries.Count -eq 0) { 'NOT_APPLICABLE' } elseif (@($binaries.Values | Where-Object { $_.goMetadata.trimpath -eq 'false' }).Count -gt 0) { 'FAIL' } elseif (@($binaries.Values | Where-Object { $_.goMetadata.trimpath -eq 'NOT_AVAILABLE' }).Count -gt 0) { 'NOT_AVAILABLE' } else { 'PASS' }
  }
  $hardeningChecks = @('ownedNativeFiles', 'peFormat', 'coffSymbols', 'debugDirectory', 'debugSections', 'namedExports', 'sourcePaths', 'privateNativeFiles', 'goTrimpath')
  $hardeningStatus = if (@($hardeningChecks | Where-Object { $checks[$_] -eq 'FAIL' }).Count -gt 0) { 'FAIL' } elseif (@($hardeningChecks | Where-Object { $checks[$_] -eq 'NOT_AVAILABLE' }).Count -gt 0) { 'PARTIAL' } else { 'PASS' }
  $signatureStatuses = @($binaries.Values | ForEach-Object { $_.signing.status })
  $signingStatus = if ($binaries.Count -eq 0) { 'NOT_APPLICABLE' } elseif (@($signatureStatuses | Where-Object { $_ -in @('Valid', 'Valid') }).Count -eq $binaries.Count) { 'PASS' } elseif (@($signatureStatuses | Where-Object { $_ -in @('NotSigned', 'UnknownError', 'NotTrusted') }).Count -gt 0) { 'OPEN_NOT_SIGNED_OR_UNTRUSTED' } else { 'NOT_AVAILABLE' }

  return [ordered]@{
    profile = $profile
    archive = $resolved
    archiveSha256 = Sha256 $resolved
    archiveBytes = [long](Get-Item -LiteralPath $resolved).Length
    extractedRoot = if ($KeepExtracted) { $extractRoot } else { 'DISPOSABLE_TEMPORARY_ROOT' }
    bundleRoot = if ($KeepExtracted) { $bundleRoot } else { 'DISPOSABLE_TEMPORARY_ROOT' }
    nativePaths = $nativePaths
    forbiddenNativeFiles = $forbidden
    binaries = $binaries
    checks = $checks
    nativeHardeningStatus = $hardeningStatus
    nativeSigningStatus = $signingStatus
  }
}

$archives = [ordered]@{
  developer = @{ path = (ResolveArchive $DeveloperArchive 'Developer'); expected = 'developer' }
}
if (-not [string]::IsNullOrWhiteSpace($ProtectionLiteArchive))
{
  $archives.protectionLite = @{ path = $ProtectionLiteArchive; expected = 'protection-lite' }
}
if (-not [string]::IsNullOrWhiteSpace($MaximumArchive))
{
  $archives.maximum = @{ path = $MaximumArchive; expected = 'maximum-release' }
}

foreach ($entry in $archives.GetEnumerator())
{
  $result.profiles[$entry.Key] = AuditProfile $entry.Key $entry.Value.path $entry.Value.expected
}

$profileRecords = @($result.profiles.Values)
$hardeningStatuses = @($profileRecords | ForEach-Object { $_.nativeHardeningStatus })
$signingStatuses = @($profileRecords | ForEach-Object { $_.nativeSigningStatus } | Where-Object { $_ -ne 'NOT_APPLICABLE' })
$goCommand = Get-Command go -ErrorAction SilentlyContinue
if ($null -ne $goCommand)
{
  $result.toolchain.go = [string](& $goCommand.Source version 2>$null)
  $result.toolchain.goPath = [string]$goCommand.Source
}
$developer = $result.profiles.developer
foreach ($comparisonName in @('protectionLite', 'maximum'))
{
  if (-not $result.profiles.Contains($comparisonName))
  {
    continue
  }
  $other = $result.profiles[$comparisonName]
  $comparison = [ordered]@{}
  foreach ($nativePath in $developer.binaries.Keys)
  {
    if ($other.binaries.Contains($nativePath))
    {
      $comparison[$nativePath] = [ordered]@{
        sameSha256 = ($developer.binaries[$nativePath].sha256 -eq $other.binaries[$nativePath].sha256)
        developerSha256 = $developer.binaries[$nativePath].sha256
        profileSha256 = $other.binaries[$nativePath].sha256
      }
    }
  }
  $result.comparisons[$comparisonName] = $comparison
}
$result.nativeHardeningStatus = if (@($hardeningStatuses | Where-Object { $_ -eq 'FAIL' }).Count -gt 0) { 'FAIL' } elseif (@($hardeningStatuses | Where-Object { $_ -ne 'PASS' -and $_ -ne 'NOT_APPLICABLE' }).Count -gt 0) { 'PARTIAL' } else { 'PASS' }
$result.nativeSigningStatus = if ($signingStatuses.Count -eq 0) { 'NOT_APPLICABLE' } elseif (@($signingStatuses | Where-Object { $_ -ne 'PASS' }).Count -gt 0) { 'OPEN_NOT_SIGNED_OR_UNTRUSTED' } else { 'PASS' }

$result.openGates = @(
  'Commercial maximum archive was not supplied; this audit cannot promote the maximum profile.',
  'Native Authenticode/Apple signing is not implemented by the current release pipeline; unsigned native binaries remain an open release gate.'
)
if ($archives.Contains('maximum'))
{
  $result.openGates = @($result.openGates | Where-Object { $_ -notlike 'Commercial maximum archive was not supplied*' })
}
if ($result.nativeSigningStatus -eq 'PASS')
{
  $result.openGates = @($result.openGates | Where-Object { $_ -notlike 'Native Authenticode/Apple signing*' })
}

$result.status = if ($result.nativeHardeningStatus -eq 'FAIL') {
  'FAIL_NATIVE_HARDENING'
} elseif (-not $archives.Contains('maximum')) {
  'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED'
} elseif ($result.nativeSigningStatus -ne 'PASS') {
  'PARTIAL_NATIVE_SIGNING_OPEN'
} elseif ($result.nativeHardeningStatus -ne 'PASS') {
  'PARTIAL_NATIVE_HARDENING'
} else {
  'PASS_NATIVE_HARDENING_AND_SIGNING'
}

if (-not $KeepExtracted)
{
  $result.profiles.Values | ForEach-Object {
    $_.extractedRoot = 'DISPOSED_AFTER_AUDIT'
    $_.bundleRoot = 'DISPOSED_AFTER_AUDIT'
  }
  Require ($tempRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) (
    "Refusing to remove an audit directory outside the system temporary root: $tempRoot")
  if (Test-Path -LiteralPath $tempRoot -PathType Container)
  {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
  }
}
$json = $result | ConvertTo-Json -Depth 14
[IO.File]::WriteAllText($evidencePath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output ("NATIVE_HARDENING_STATUS=" + $result.nativeHardeningStatus)
Write-Output ("NATIVE_SIGNING_STATUS=" + $result.nativeSigningStatus)
Write-Output ("STATUS=" + $result.status)
Write-Output ("EVIDENCE=" + $evidencePath)
if ($result.status -like 'FAIL*') { exit 1 }
if ($result.status -like 'PARTIAL*') { exit 2 }
