# Audits Synesis-owned native launchers in extracted customer archives.
# It records PE/ELF/Mach-O symbols, debug data, exports, trim-path build
# metadata, source-path signals, and platform signing status without changing
# an archive.
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
  scope = 'EXTRACTED_CUSTOMER_ARCHIVES_ONLY; SYNESIS-OWNED-NATIVE-PE-ELF-MACH-O-STATIC-AUDIT'
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

function SanitizeNativeText([string]$Path, [string]$Text)
{
  if ($null -eq $Text) { return '' }
  return $Text -replace [Regex]::Escape($Path), '<native-file>'
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
    $goMetadata.output = @(& $goCommand.Source version -m $Path 2>&1 | ForEach-Object { SanitizeNativeText $Path $_.ToString() })
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
    $signature.message = SanitizeNativeText $Path ([string]$authenticode.StatusMessage)
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
    nativeSymbolCount = [uint64]$symbolCount
    debugDirectoryRva = [uint32]$debugRva
    debugDirectorySize = [uint32]$debugSize
    debugDirectoryPresent = ($debugRva -ne 0 -and $debugSize -ne 0)
    debugInfoPresent = ($debugRva -ne 0 -and $debugSize -ne 0 -or $debugSectionNames.Count -gt 0)
    debugSectionNames = $debugSectionNames
    exportDirectoryRva = [uint32]$exportRva
    exportDirectorySize = [uint32]$exportSize
    exportNameCount = [int]$exportNames
    sourcePathSignals = $pathSignals
    goMetadata = $goMetadata
    signing = $signature
  }
}

function ReadEndianUInt16([byte[]]$Bytes, [int]$Offset, [bool]$LittleEndian, [string]$Label)
{
  Require ($Offset -ge 0 -and $Offset + 2 -le $Bytes.Length) "Truncated native field: $Label"
  $value = [uint32]0
  if ($LittleEndian)
  {
    $value = $value -bor ([uint32]$Bytes[$Offset])
    $value = $value -bor (([uint32]$Bytes[$Offset + 1]) -shl 8)
  }
  else
  {
    $value = $value -bor (([uint32]$Bytes[$Offset]) -shl 8)
    $value = $value -bor ([uint32]$Bytes[$Offset + 1])
  }
  return [uint16]$value
}

function ReadEndianUInt32([byte[]]$Bytes, [int]$Offset, [bool]$LittleEndian, [string]$Label)
{
  Require ($Offset -ge 0 -and $Offset + 4 -le $Bytes.Length) "Truncated native field: $Label"
  $value = [uint64]0
  for ($index = 0; $index -lt 4; $index++)
  {
    $shift = if ($LittleEndian) { 8 * $index } else { 8 * (3 - $index) }
    $value = $value -bor (([uint64]$Bytes[$Offset + $index]) -shl $shift)
  }
  return [uint32]$value
}

function ReadEndianUInt64([byte[]]$Bytes, [int]$Offset, [bool]$LittleEndian, [string]$Label)
{
  Require ($Offset -ge 0 -and $Offset + 8 -le $Bytes.Length) "Truncated native field: $Label"
  $value = [uint64]0
  for ($index = 0; $index -lt 8; $index++)
  {
    $shift = if ($LittleEndian) { 8 * $index } else { 8 * (7 - $index) }
    $value = $value -bor (([uint64]$Bytes[$Offset + $index]) -shl $shift)
  }
  return $value
}

function ReadNativeAscii([byte[]]$Bytes, [int]$Offset, [int]$Length)
{
  if ($Offset -lt 0 -or $Offset -ge $Bytes.Length)
  {
    return ''
  }
  $safeLength = [Math]::Min($Length, $Bytes.Length - $Offset)
  return [Text.Encoding]::ASCII.GetString($Bytes, $Offset, $safeLength).Trim([char]0)
}

function ReadNativeStringTableValue(
  [byte[]]$Bytes,
  [uint64]$TableOffset,
  [uint64]$TableSize,
  [uint32]$StringOffset
)
{
  if ($StringOffset -ge $TableSize -or $TableOffset + $StringOffset -ge [uint64]$Bytes.Length)
  {
    return ''
  }
  $start = [int]($TableOffset + $StringOffset)
  $limit = [int]([Math]::Min($TableOffset + $TableSize, [uint64]$Bytes.Length))
  $end = $start
  while ($end -lt $limit -and $Bytes[$end] -ne 0)
  {
    $end++
  }
  return [Text.Encoding]::ASCII.GetString($Bytes, $start, $end - $start)
}

function GetNativePathSignals([byte[]]$Bytes)
{
  $text = [Text.Encoding]::ASCII.GetString($Bytes)
  return @($sourcePathPatterns | Where-Object { $text -match $_ })
}

function GetGoMetadata([string]$Path)
{
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
    $goMetadata.output = @(& $goCommand.Source version -m $Path 2>&1 | ForEach-Object { SanitizeNativeText $Path $_.ToString() })
    $goMetadata.trimpath = if (@($goMetadata.output | Where-Object { $_ -match 'build\s+-trimpath=true' }).Count -gt 0) { 'true' } else { 'false' }
    $goMetadata.buildMode = [string]($goMetadata.output | Where-Object { $_ -match '^\s*build\s+-buildmode=' } | Select-Object -First 1)
    $goMetadata.goVersion = [string]($goMetadata.output | Where-Object { $_ -match ':\s+go[0-9]' } | Select-Object -First 1)
    $goMetadata.module = [string]($goMetadata.output | Where-Object { $_ -match '^\s*mod\s+' } | Select-Object -First 1)
    $goMetadata.vcsRevision = [string]($goMetadata.output | Where-Object { $_ -match '^\s*build\s+vcs\.revision=' } | Select-Object -First 1)
  }
  return $goMetadata
}

function ParseElf([string]$Path)
{
  $bytes = [IO.File]::ReadAllBytes($Path)
  Require ($bytes.Length -ge 0x34) "Native binary is too small to be ELF: $Path"
  Require ($bytes[0] -eq 0x7f -and $bytes[1] -eq 0x45 -and $bytes[2] -eq 0x4c -and $bytes[3] -eq 0x46) (
    "Native binary lacks an ELF header: $Path")
  $elfClass = [int]$bytes[4]
  $dataEncoding = [int]$bytes[5]
  Require ($elfClass -in @(1, 2)) "Unsupported ELF class: $Path"
  Require ($dataEncoding -in @(1, 2)) "Unsupported ELF byte order: $Path"
  $littleEndian = $dataEncoding -eq 1
  $is64 = $elfClass -eq 2
  $machine = ReadEndianUInt16 $bytes 18 $littleEndian 'ELF machine'
  $sectionOffset = if ($is64) { ReadEndianUInt64 $bytes 40 $littleEndian 'ELF section offset' } else { ReadEndianUInt32 $bytes 32 $littleEndian 'ELF section offset' }
  $sectionEntrySize = if ($is64) { ReadEndianUInt16 $bytes 58 $littleEndian 'ELF section entry size' } else { ReadEndianUInt16 $bytes 46 $littleEndian 'ELF section entry size' }
  $sectionCount = if ($is64) { ReadEndianUInt16 $bytes 60 $littleEndian 'ELF section count' } else { ReadEndianUInt16 $bytes 48 $littleEndian 'ELF section count' }
  $sectionNameIndex = if ($is64) { ReadEndianUInt16 $bytes 62 $littleEndian 'ELF section-name index' } else { ReadEndianUInt16 $bytes 50 $littleEndian 'ELF section-name index' }
  $sectionHeaders = [System.Collections.Generic.List[object]]::new()
  if ($sectionCount -gt 0)
  {
    Require ($sectionEntrySize -gt 0 -and $sectionOffset -gt 0) "ELF section table is missing: $Path"
    Require ($sectionOffset + ([uint64]$sectionEntrySize * $sectionCount) -le [uint64]$bytes.Length) "ELF section table is truncated: $Path"
    for ($index = 0; $index -lt $sectionCount; $index++)
    {
      $offset = [int]($sectionOffset + ([uint64]$index * $sectionEntrySize))
      $type = ReadEndianUInt32 $bytes ($offset + 4) $littleEndian 'ELF section type'
      if ($is64)
      {
        $dataOffset = ReadEndianUInt64 $bytes ($offset + 24) $littleEndian 'ELF section offset'
        $dataSize = ReadEndianUInt64 $bytes ($offset + 32) $littleEndian 'ELF section size'
        $link = ReadEndianUInt32 $bytes ($offset + 40) $littleEndian 'ELF section link'
        $entrySize = ReadEndianUInt64 $bytes ($offset + 56) $littleEndian 'ELF symbol entry size'
      }
      else
      {
        $dataOffset = ReadEndianUInt32 $bytes ($offset + 16) $littleEndian 'ELF section offset'
        $dataSize = ReadEndianUInt32 $bytes ($offset + 20) $littleEndian 'ELF section size'
        $link = ReadEndianUInt32 $bytes ($offset + 24) $littleEndian 'ELF section link'
        $entrySize = ReadEndianUInt32 $bytes ($offset + 36) $littleEndian 'ELF symbol entry size'
      }
      $sectionHeaders.Add([pscustomobject]@{
          nameOffset = ReadEndianUInt32 $bytes $offset $littleEndian 'ELF section name offset'
          type = $type
          offset = [uint64]$dataOffset
          size = [uint64]$dataSize
          link = $link
          entrySize = [uint64]$entrySize
          name = ''
        })
    }
  }
  $sectionNameTable = $null
  if ($sectionCount -gt 0 -and $sectionNameIndex -lt $sectionHeaders.Count)
  {
    $sectionNameTable = $sectionHeaders[$sectionNameIndex]
  }
  foreach ($section in $sectionHeaders)
  {
    if ($null -ne $sectionNameTable)
    {
      $section.name = ReadNativeStringTableValue $bytes $sectionNameTable.offset $sectionNameTable.size $section.nameOffset
    }
  }

  $debugSectionNames = @($sectionHeaders | Where-Object { $_.name -match '(?i)(^\.debug|^\.zdebug|^\.stab|debuglink|gdb_index)' } | ForEach-Object { $_.name })
  $nativeSymbolCount = [uint64]0
  $exportNameCount = 0
  foreach ($table in @($sectionHeaders | Where-Object { $_.type -in @(2, 11) }))
  {
    $entrySize = $table.entrySize
    if ($entrySize -eq 0) { $entrySize = if ($is64) { 24 } else { 16 } }
    $count = [uint64]($table.size / $entrySize)
    $nativeSymbolCount += $count
    $stringTable = $null
    if ($table.link -lt $sectionHeaders.Count) { $stringTable = $sectionHeaders[$table.link] }
    for ($index = [uint64]0; $index -lt $count; $index++)
    {
      $entryOffset = $table.offset + ($index * $entrySize)
      Require ($entryOffset + $entrySize -le [uint64]$bytes.Length) "ELF symbol table is truncated: $Path"
      $entry = [int]$entryOffset
      $nameOffset = ReadEndianUInt32 $bytes $entry $littleEndian 'ELF symbol name offset'
      $infoOffset = $entry + $(if ($is64) { 4 } else { 12 })
      $sectionIndexOffset = $entry + $(if ($is64) { 6 } else { 14 })
      $info = [int]$bytes[$infoOffset]
      $symbolSection = ReadEndianUInt16 $bytes $sectionIndexOffset $littleEndian 'ELF symbol section index'
      if ($table.type -eq 11 -and (($info -shr 4) -band 0x0f) -in @(1, 2) -and $symbolSection -ne 0)
      {
        $symbolName = if ($null -eq $stringTable) { '' } else {
          ReadNativeStringTableValue $bytes $stringTable.offset $stringTable.size $nameOffset
        }
        if (-not [string]::IsNullOrWhiteSpace($symbolName)) { $exportNameCount++ }
      }
    }
  }
  $signature = [ordered]@{
    status = 'NOT_APPLICABLE'
    subject = ''
    message = 'ELF uses the signed release manifest as its distribution integrity boundary; no per-file OS signature is applicable.'
  }
  return [ordered]@{
    path = $Path
    bytes = [long]$bytes.Length
    sha256 = Sha256 $Path
    format = if ($is64) { 'ELF64' } else { 'ELF32' }
    machine = ('0x{0:x4}' -f $machine)
    sectionCount = [int]$sectionHeaders.Count
    sections = @($sectionHeaders | ForEach-Object { $_.name })
    coffSymbolPointer = [uint32]0
    coffSymbolCount = [uint32]0
    nativeSymbolCount = $nativeSymbolCount
    debugDirectoryRva = [uint32]0
    debugDirectorySize = [uint32]0
    debugDirectoryPresent = $false
    debugInfoPresent = ($debugSectionNames.Count -gt 0)
    debugSectionNames = $debugSectionNames
    exportDirectoryRva = [uint32]0
    exportDirectorySize = [uint32]0
    exportNameCount = [int]$exportNameCount
    sourcePathSignals = @(GetNativePathSignals $bytes)
    goMetadata = GetGoMetadata $Path
    codeSignaturePresent = $false
    signing = $signature
  }
}

function MachOFormat([byte[]]$Bytes, [int]$Offset)
{
  Require ($Offset -ge 0 -and $Offset + 4 -le $Bytes.Length) 'Truncated Mach-O magic'
  if ($Bytes[$Offset] -eq 0xfe -and $Bytes[$Offset + 1] -eq 0xed -and $Bytes[$Offset + 2] -eq 0xfa -and $Bytes[$Offset + 3] -eq 0xce) { return 'macho32be' }
  if ($Bytes[$Offset] -eq 0xce -and $Bytes[$Offset + 1] -eq 0xfa -and $Bytes[$Offset + 2] -eq 0xed -and $Bytes[$Offset + 3] -eq 0xfe) { return 'macho32le' }
  if ($Bytes[$Offset] -eq 0xfe -and $Bytes[$Offset + 1] -eq 0xed -and $Bytes[$Offset + 2] -eq 0xfa -and $Bytes[$Offset + 3] -eq 0xcf) { return 'macho64be' }
  if ($Bytes[$Offset] -eq 0xcf -and $Bytes[$Offset + 1] -eq 0xfa -and $Bytes[$Offset + 2] -eq 0xed -and $Bytes[$Offset + 3] -eq 0xfe) { return 'macho64le' }
  if ($Bytes[$Offset] -eq 0xca -and $Bytes[$Offset + 1] -eq 0xfe -and $Bytes[$Offset + 2] -eq 0xba -and $Bytes[$Offset + 3] -eq 0xbe) { return 'fat32be' }
  if ($Bytes[$Offset] -eq 0xbe -and $Bytes[$Offset + 1] -eq 0xba -and $Bytes[$Offset + 2] -eq 0xfe -and $Bytes[$Offset + 3] -eq 0xca) { return 'fat32le' }
  if ($Bytes[$Offset] -eq 0xca -and $Bytes[$Offset + 1] -eq 0xfe -and $Bytes[$Offset + 2] -eq 0xba -and $Bytes[$Offset + 3] -eq 0xbf) { return 'fat64be' }
  if ($Bytes[$Offset] -eq 0xbf -and $Bytes[$Offset + 1] -eq 0xba -and $Bytes[$Offset + 2] -eq 0xfe -and $Bytes[$Offset + 3] -eq 0xca) { return 'fat64le' }
  return ''
}

function ParseMachOSlice([byte[]]$Bytes, [uint64]$BaseOffset, [uint64]$SliceSize, [string]$Path)
{
  Require ($BaseOffset -le [int]::MaxValue) "Mach-O slice offset is too large: $Path"
  $base = [int]$BaseOffset
  $kind = MachOFormat $Bytes $base
  Require ($kind -match '^macho') "Mach-O slice magic is invalid: $Path"
  $littleEndian = $kind.EndsWith('le')
  $is64 = $kind -match '64'
  $headerSize = if ($is64) { 32 } else { 28 }
  $limit = $BaseOffset + $SliceSize
  Require ($limit -le [uint64]$Bytes.Length -and $BaseOffset + $headerSize -le $limit) "Mach-O slice is truncated: $Path"
  $machine = ReadEndianUInt32 $Bytes ($base + 4) $littleEndian 'Mach-O CPU type'
  $commandCount = ReadEndianUInt32 $Bytes ($base + 16) $littleEndian 'Mach-O command count'
  $commandBytes = ReadEndianUInt32 $Bytes ($base + 20) $littleEndian 'Mach-O command size'
  $commandOffset = $BaseOffset + $headerSize
  Require ($commandOffset + $commandBytes -le $limit) "Mach-O load-command table is truncated: $Path"
  $sections = [System.Collections.Generic.List[string]]::new()
  $debugSections = [System.Collections.Generic.List[string]]::new()
  $symtab = $null
  $codeSignaturePresent = $false
  for ($index = 0; $index -lt $commandCount; $index++)
  {
    Require ($commandOffset + 8 -le $limit) "Mach-O load command is truncated: $Path"
    $command = ReadEndianUInt32 $Bytes ([int]$commandOffset) $littleEndian 'Mach-O command'
    $commandSize = ReadEndianUInt32 $Bytes ([int]($commandOffset + 4)) $littleEndian 'Mach-O command size'
    Require ($commandSize -ge 8 -and $commandOffset + $commandSize -le $limit) "Mach-O load command has invalid size: $Path"
    if ($command -eq 1 -or $command -eq 0x19)
    {
      $segment64 = $command -eq 0x19
      $segmentName = ReadNativeAscii $Bytes ([int]($commandOffset + 8)) 16
      $sectionCount = if ($segment64) {
        ReadEndianUInt32 $Bytes ([int]($commandOffset + 64)) $littleEndian 'Mach-O section count'
      } else {
        ReadEndianUInt32 $Bytes ([int]($commandOffset + 48)) $littleEndian 'Mach-O section count'
      }
      $sectionHeaderSize = if ($segment64) { 80 } else { 68 }
      $sectionStart = $commandOffset + $(if ($segment64) { 72 } else { 56 })
      Require ($sectionStart + ([uint64]$sectionCount * $sectionHeaderSize) -le $commandOffset + $commandSize) "Mach-O section table is truncated: $Path"
      for ($sectionIndex = 0; $sectionIndex -lt $sectionCount; $sectionIndex++)
      {
        $sectionOffset = $sectionStart + ([uint64]$sectionIndex * $sectionHeaderSize)
        $sectionName = ReadNativeAscii $Bytes ([int]$sectionOffset) 16
        [void]$sections.Add($sectionName)
        if ($segmentName -eq '__DWARF' -or $sectionName -match '(?i)(debug|stab)') { [void]$debugSections.Add($sectionName) }
      }
    }
    elseif ($command -eq 2)
    {
      $symtab = [pscustomobject]@{
        symbolOffset = ReadEndianUInt32 $Bytes ([int]($commandOffset + 8)) $littleEndian 'Mach-O symbol offset'
        symbolCount = ReadEndianUInt32 $Bytes ([int]($commandOffset + 12)) $littleEndian 'Mach-O symbol count'
        stringOffset = ReadEndianUInt32 $Bytes ([int]($commandOffset + 16)) $littleEndian 'Mach-O string offset'
        stringSize = ReadEndianUInt32 $Bytes ([int]($commandOffset + 20)) $littleEndian 'Mach-O string size'
      }
    }
    elseif ($command -eq 0x1d)
    {
      $codeSignaturePresent = $true
    }
    $commandOffset += $commandSize
  }
  $nativeSymbolCount = [uint64]0
  $exportNameCount = 0
  if ($null -ne $symtab)
  {
    $entrySize = if ($is64) { 16 } else { 12 }
    $nativeSymbolCount = [uint64]$symtab.symbolCount
    $symbolTableOffset = $BaseOffset + $symtab.symbolOffset
    Require ($symbolTableOffset + ([uint64]$symtab.symbolCount * $entrySize) -le $limit) "Mach-O symbol table is truncated: $Path"
    for ($index = 0; $index -lt $symtab.symbolCount; $index++)
    {
      $entryOffset = [int]($symbolTableOffset + ([uint64]$index * $entrySize))
      $symbolType = [int]$Bytes[$entryOffset + 4]
      $symbolSection = [int]$Bytes[$entryOffset + 5]
      if (($symbolType -band 0x01) -ne 0 -and ($symbolType -band 0x0e) -ne 0 -and $symbolSection -ne 0)
      {
        $exportNameCount++
      }
    }
  }
  return [ordered]@{
    format = if ($is64) { 'Mach-O64' } else { 'Mach-O32' }
    machine = ('0x{0:x8}' -f $machine)
    sectionCount = [int]$sections.Count
    sections = @($sections | Select-Object -Unique)
    nativeSymbolCount = $nativeSymbolCount
    debugInfoPresent = ($debugSections.Count -gt 0)
    debugSectionNames = @($debugSections | Select-Object -Unique)
    exportNameCount = [int]$exportNameCount
    codeSignaturePresent = $codeSignaturePresent
  }
}

function ParseMachO([string]$Path)
{
  $bytes = [IO.File]::ReadAllBytes($Path)
  $kind = MachOFormat $bytes 0
  Require ($kind -match '^(macho|fat)') "Native binary is not Mach-O: $Path"
  $slices = [System.Collections.Generic.List[object]]::new()
  $isFat = $kind.StartsWith('fat')
  if ($isFat)
  {
    $littleEndian = $kind.EndsWith('le')
    $isFat64 = $kind -match '64'
    $architectureCount = ReadEndianUInt32 $bytes 4 $littleEndian 'Mach-O architecture count'
    $architectureSize = if ($isFat64) { 32 } else { 20 }
    $architectureTableEnd = 8 + ([uint64]$architectureCount * $architectureSize)
    Require ($architectureTableEnd -le [uint64]$bytes.Length) "Mach-O architecture table is truncated: $Path"
    for ($index = 0; $index -lt $architectureCount; $index++)
    {
      $entry = 8 + ($index * $architectureSize)
      $sliceOffset = if ($isFat64) { ReadEndianUInt64 $bytes ($entry + 8) $littleEndian 'Mach-O 64-bit slice offset' } else { ReadEndianUInt32 $bytes ($entry + 8) $littleEndian 'Mach-O slice offset' }
      $sliceSize = if ($isFat64) { ReadEndianUInt64 $bytes ($entry + 16) $littleEndian 'Mach-O 64-bit slice size' } else { ReadEndianUInt32 $bytes ($entry + 12) $littleEndian 'Mach-O slice size' }
      Require ($sliceOffset + $sliceSize -le [uint64]$bytes.Length) "Mach-O slice exceeds the file: $Path"
      [void]$slices.Add((ParseMachOSlice $bytes $sliceOffset $sliceSize $Path))
    }
  }
  else
  {
    [void]$slices.Add((ParseMachOSlice $bytes 0 ([uint64]$bytes.Length) $Path))
  }
  $sections = @($slices | ForEach-Object { $_.sections } | Select-Object -Unique)
  $debugSections = @($slices | ForEach-Object { $_.debugSectionNames } | Select-Object -Unique)
  $nativeSymbolCount = [uint64]0
  $exportNameCount = 0
  foreach ($slice in $slices)
  {
    $nativeSymbolCount += [uint64]$slice.nativeSymbolCount
    $exportNameCount += [int]$slice.exportNameCount
  }
  $signature = [ordered]@{
    status = 'NOT_CHECKED'
    subject = ''
    message = 'Mach-O signature status was not checked on this host.'
  }
  $codesign = Get-Command codesign -ErrorAction SilentlyContinue
  $codeSignaturePresent = @($slices | Where-Object { $_.codeSignaturePresent }).Count -gt 0
  if ($null -ne $codesign)
  {
    $codesignOutput = @(& $codesign.Source --verify --deep --strict --verbose=2 $Path 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -eq 0)
    {
      $signature.status = 'Valid'
      $signature.message = SanitizeNativeText $Path (($codesignOutput -join "`n").Trim())
    }
    else
    {
      $signature.status = if ($codeSignaturePresent) { 'UnknownError' } else { 'NotSigned' }
      $signature.message = SanitizeNativeText $Path (($codesignOutput -join "`n").Trim())
    }
  }
  elseif ($codeSignaturePresent)
  {
    $signature.status = 'NOT_CHECKED'
    $signature.message = 'Mach-O code-signature load command is present, but codesign verification is unavailable on this host.'
  }
  else
  {
    $signature.status = 'NotSigned'
    $signature.message = 'Mach-O file has no code-signature load command.'
  }
  return [ordered]@{
    path = $Path
    bytes = [long]$bytes.Length
    sha256 = Sha256 $Path
    format = if ($isFat) { 'Mach-O Universal' } else { $slices[0].format }
    machine = (@($slices | ForEach-Object { $_.machine } | Select-Object -Unique) -join ',')
    sectionCount = [int]$sections.Count
    sections = $sections
    coffSymbolPointer = [uint32]0
    coffSymbolCount = [uint32]0
    nativeSymbolCount = $nativeSymbolCount
    debugDirectoryRva = [uint32]0
    debugDirectorySize = [uint32]0
    debugDirectoryPresent = $false
    debugInfoPresent = ($debugSections.Count -gt 0)
    debugSectionNames = $debugSections
    exportDirectoryRva = [uint32]0
    exportDirectorySize = [uint32]0
    exportNameCount = [int]$exportNameCount
    sourcePathSignals = @(GetNativePathSignals $bytes)
    goMetadata = GetGoMetadata $Path
    codeSignaturePresent = $codeSignaturePresent
    signing = $signature
  }
}

function ParseNative([string]$Path)
{
  $header = [IO.File]::ReadAllBytes($Path)
  Require ($header.Length -ge 4) "Native binary is too small: $Path"
  if ($header[0] -eq 0x4d -and $header[1] -eq 0x5a) { return ParsePe $Path }
  if ($header[0] -eq 0x7f -and $header[1] -eq 0x45 -and $header[2] -eq 0x4c -and $header[3] -eq 0x46) { return ParseElf $Path }
  $machKind = MachOFormat $header 0
  if ($machKind -match '^(macho|fat)') { return ParseMachO $Path }
  throw "Unsupported native executable format: $Path"
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
    $record = ParseNative $path
    $record.path = $relative
    $binaries[$relative] = $record
  }

  $checks = [ordered]@{
    ownedNativeFiles = if ($Component -eq 'cli' -and $nativePaths.Count -eq 2) { 'PASS' } else { 'NOT_APPLICABLE' }
    nativeFormat = if (@($binaries.Values | Where-Object { $_.format -notin @('PE32', 'PE32+', 'ELF32', 'ELF64', 'Mach-O32', 'Mach-O64', 'Mach-O Universal') }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    nativeSymbols = if (@($binaries.Values | Where-Object {
          ($_.format -in @('PE32', 'PE32+') -and $_.coffSymbolCount -ne 0) -or $_.debugInfoPresent
        }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    peFormat = if (@($binaries.Values | Where-Object { $_.format -in @('PE32', 'PE32+') }).Count -eq $binaries.Count) { 'PASS' } else { 'NOT_APPLICABLE' }
    coffSymbols = if (@($binaries.Values | Where-Object { $_.format -in @('PE32', 'PE32+') -and $_.coffSymbolCount -ne 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    debugDirectory = if (@($binaries.Values | Where-Object { $_.debugDirectoryPresent }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    debugSections = if (@($binaries.Values | Where-Object { $_.debugInfoPresent }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    namedExports = if (@($binaries.Values | Where-Object { $_.exportNameCount -ne 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    sourcePaths = if (@($binaries.Values | Where-Object { $_.sourcePathSignals.Count -gt 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
    privateNativeFiles = if ($forbidden.Count -eq 0) { 'PASS' } else { 'FAIL' }
    goTrimpath = if ($binaries.Count -eq 0) { 'NOT_APPLICABLE' } elseif (@($binaries.Values | Where-Object { $_.goMetadata.trimpath -eq 'false' }).Count -gt 0) { 'FAIL' } elseif (@($binaries.Values | Where-Object { $_.goMetadata.trimpath -eq 'NOT_AVAILABLE' }).Count -gt 0) { 'NOT_AVAILABLE' } else { 'PASS' }
  }
  $hardeningChecks = @('ownedNativeFiles', 'nativeFormat', 'nativeSymbols', 'debugDirectory', 'debugSections', 'namedExports', 'sourcePaths', 'privateNativeFiles', 'goTrimpath')
  $hardeningStatus = if (@($hardeningChecks | Where-Object { $checks[$_] -eq 'FAIL' }).Count -gt 0) { 'FAIL' } elseif (@($hardeningChecks | Where-Object { $checks[$_] -eq 'NOT_AVAILABLE' }).Count -gt 0) { 'PARTIAL' } else { 'PASS' }
  $signatureStatuses = @($binaries.Values | ForEach-Object { $_.signing.status })
  $signingStatus = if ($binaries.Count -eq 0) { 'NOT_APPLICABLE' } elseif (@($signatureStatuses | Where-Object { $_ -in @('Valid', 'NOT_APPLICABLE') }).Count -eq $binaries.Count) { 'PASS' } elseif (@($signatureStatuses | Where-Object { $_ -in @('NotSigned', 'UnknownError', 'NotTrusted', 'NOT_CHECKED') }).Count -gt 0) { 'OPEN_NOT_SIGNED_OR_UNTRUSTED' } else { 'NOT_AVAILABLE' }

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
$nativeSigningSatisfied = $result.nativeSigningStatus -in @('PASS', 'NOT_APPLICABLE')

$result.openGates = @(
  'Commercial maximum archive was not supplied; this audit cannot promote the maximum profile.',
  'Native Authenticode/Apple signing is not implemented by the current release pipeline; unsigned native binaries remain an open release gate.'
)
if ($archives.Contains('maximum'))
{
  $result.openGates = @($result.openGates | Where-Object { $_ -notlike 'Commercial maximum archive was not supplied*' })
}
if ($nativeSigningSatisfied)
{
  $result.openGates = @($result.openGates | Where-Object { $_ -notlike 'Native Authenticode/Apple signing*' })
}

$result.status = if ($result.nativeHardeningStatus -eq 'FAIL') {
  'FAIL_NATIVE_HARDENING'
} elseif (-not $archives.Contains('maximum')) {
  'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED'
} elseif (-not $nativeSigningSatisfied) {
  'PARTIAL_NATIVE_SIGNING_OPEN'
} elseif ($result.nativeHardeningStatus -ne 'PASS') {
  'PARTIAL_NATIVE_HARDENING'
} else {
  if ($result.nativeSigningStatus -eq 'NOT_APPLICABLE') {
    'PASS_NATIVE_HARDENING_NATIVE_SIGNING_NOT_APPLICABLE'
  } else {
    'PASS_NATIVE_HARDENING_AND_SIGNING'
  }
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
