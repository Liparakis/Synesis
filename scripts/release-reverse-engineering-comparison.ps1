# Compares static reverse-engineering signals in extracted Synesis release
# archives. It never inspects source classes and never treats a missing
# maximum archive or an unrun decompiler inspection as a pass.
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
  Join-Path (Get-Location) "build\release-reverse-engineering-comparison\$Component\comparison.json"
}
else
{
  [IO.Path]::GetFullPath($EvidenceFile)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $temporaryRoot ("synesis-re-comparison-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
Require ($tempRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) (
  "Comparison temporary directory escaped the system temporary root: $tempRoot")

Add-Type -AssemblyName System.IO.Compression.FileSystem

$architectureTerms = @(
  'authority', 'claim', 'coordination', 'provider', 'continuity',
  'membership', 'overlay', 'routing', 'route', 'relay', 'onboarding',
  'workspace', 'diagnostic', 'peer', 'session', 'reconciliation',
  'integration', 'ownership', 'capability', 'protection', 'manifest'
)

$sourceSignals = [ordered]@{
  'source-file-attribute' = 'sourcefile'
  'java-filename' = '.java'
  'kotlin-filename' = '.kt'
  'scala-filename' = '.scala'
  'groovy-filename' = '.groovy'
  'source-tree-path' = 'src/main'
  'windows-local-path' = 'c:\'
  'windows-local-path-slash' = 'c:/'
  'unix-local-path' = '/home/'
  'unix-user-path' = '/users/'
}

$privateNamePatterns = @(
  'mapping.txt', 'seeds.txt', 'usage.txt', 'provenance.json',
  'artifact-manifest.txt', 'release-record.properties',
  '.sourcemap', '.map', '.pdb', '.dwo', '.debug', '.sym', '.retrace',
  '.mapping', '.seed', '.seeds', '.pem', '.p12', '.pfx', '.jks',
  '.keystore', '.java', '.kt', '.scala', '.groovy', '.ts', '.tsx'
)

$result = [ordered]@{
  schema = 1
  component = $Component
  scope = 'EXTRACTED_CUSTOMER_ARCHIVES_ONLY; STATIC_SIGNAL_SCAN; NOT_SOURCE_INSPECTION'
  profiles = [ordered]@{}
  comparisons = [ordered]@{}
  inspectionTools = [ordered]@{}
  status = 'NOT_STARTED'
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

function RelativePath([string]$Root, [string]$Path)
{
  $relative = $Path.Substring($Root.Length)
  return $relative.TrimStart([char]'\', [char]'/').Replace('\', '/')
}

function ReadZipEntryBytes([IO.Compression.ZipArchiveEntry]$Entry)
{
  # Do not turn a large nested payload into an unbounded memory operation.
  if ($Entry.Length -gt 64MB)
  {
    return $null
  }
  $input = $null
  $memory = $null
  try
  {
    $input = $Entry.Open()
    $memory = [IO.MemoryStream]::new()
    $input.CopyTo($memory)
    return $memory.ToArray()
  }
  finally
  {
    if ($null -ne $memory) { $memory.Dispose() }
    if ($null -ne $input) { $input.Dispose() }
  }
}

function AnalyzeClassBytes([byte[]]$Bytes)
{
  if ($null -eq $Bytes)
  {
    return [ordered]@{
      skipped = $true
      architectureHits = @()
      sourceSignals = @()
    }
  }
  $text = [Text.Encoding]::ASCII.GetString($Bytes).ToLowerInvariant()
  $hits = @(
    $architectureTerms | Where-Object {
      $text.Contains($_)
    }
  )
  $sources = @(
    $sourceSignals.Keys | Where-Object {
      $text.Contains($sourceSignals[$_])
    }
  )
  return [ordered]@{
    skipped = $false
    architectureHits = $hits
    sourceSignals = $sources
  }
}

function ClassAnalysisRecord(
  [string]$LogicalName,
  [bool]$Internal,
  [System.Collections.Specialized.OrderedDictionary]$Analysis
)
{
  return [ordered]@{
    name = $LogicalName
    internal = $Internal
    skipped = $Analysis.skipped
    architectureHits = @($Analysis.architectureHits)
    sourceSignals = @($Analysis.sourceSignals)
  }
}

function ScanBundle([string]$BundleRoot, [string]$ArchivePath, [string]$ExpectedProfile)
{
  $files = @(Get-ChildItem -LiteralPath $BundleRoot -Recurse -File -Force)
  $nestedArchives = @(
    $files | Where-Object {
      $_.Extension.ToLowerInvariant() -in @('.jar', '.zip', '.jmod')
    }
  )
  $classAnalyses = [System.Collections.Generic.List[object]]::new()
  $internalPackages = [System.Collections.Generic.List[string]]::new()
  $allLogicalEntries = [System.Collections.Generic.List[string]]::new()
  $forbiddenFiles = [System.Collections.Generic.List[string]]::new()
  $serviceLoaderEntries = [System.Collections.Generic.List[string]]::new()
  $outerFileBytes = [long]0

  foreach ($file in $files)
  {
    $outerFileBytes += [long]$file.Length
    $relative = RelativePath $BundleRoot $file.FullName
    $lowerName = $relative.ToLowerInvariant()
    $privateHit = $false
    foreach ($pattern in $privateNamePatterns)
    {
      if ($lowerName.EndsWith($pattern) -or $lowerName.Contains('/private/') -or
          $lowerName.Contains('/mappings/') -or $lowerName.Contains('/symbols/'))
      {
        $privateHit = $true
        break
      }
    }
    if ($privateHit) { [void]$forbiddenFiles.Add($relative) }

    if ($file.Extension.Equals('.class', [StringComparison]::OrdinalIgnoreCase))
    {
      $internal = $relative.ToLowerInvariant().StartsWith('org/synesis/')
      $logical = $relative
      if ($internal)
      {
        $parts = ($relative -replace '\.class$', '') -split '/'
        if ($parts.Count -ge 3)
        {
          [void]$internalPackages.Add(($parts[0..2] -join '/'))
        }
      }
      $analysis = AnalyzeClassBytes ([IO.File]::ReadAllBytes($file.FullName))
      [void]$classAnalyses.Add((ClassAnalysisRecord $logical $internal $analysis))
    }
  }

  foreach ($archive in $nestedArchives)
  {
    $zip = $null
    try
    {
      $zip = [IO.Compression.ZipFile]::OpenRead($archive.FullName)
      foreach ($entry in $zip.Entries)
      {
        if ([string]::IsNullOrWhiteSpace($entry.FullName) -or
            $entry.FullName.EndsWith('/'))
        {
          continue
        }
        $entryName = $entry.FullName.Replace('\', '/')
        $logical = (RelativePath $BundleRoot $archive.FullName) + '::' + $entryName
        [void]$allLogicalEntries.Add($logical)
        $entryLower = $entryName.ToLowerInvariant()
        if ($entryLower.StartsWith('meta-inf/services/'))
        {
          [void]$serviceLoaderEntries.Add($logical)
        }
        $privateHit = $false
        foreach ($pattern in $privateNamePatterns)
        {
          if ($entryLower.EndsWith($pattern) -or $entryLower.Contains('/private/') -or
              $entryLower.Contains('/mappings/') -or $entryLower.Contains('/symbols/'))
          {
            $privateHit = $true
            break
          }
        }
        if ($privateHit) { [void]$forbiddenFiles.Add($logical) }

        if ($entryLower.EndsWith('.class'))
        {
          $internal = $entryLower.StartsWith('org/synesis/')
          if ($internal)
          {
            $parts = ($entryName -replace '\.class$', '') -split '/'
            if ($parts.Count -ge 3)
            {
              [void]$internalPackages.Add(($parts[0..2] -join '/'))
            }
          }
          $analysis = AnalyzeClassBytes (ReadZipEntryBytes $entry)
          [void]$classAnalyses.Add((ClassAnalysisRecord $logical $internal $analysis))
        }
      }
    }
    finally
    {
      if ($null -ne $zip) { $zip.Dispose() }
    }
  }

  $classCount = $classAnalyses.Count
  $internal = @($classAnalyses | Where-Object { $_.internal })
  $analyzed = @($classAnalyses | Where-Object { -not $_.skipped })
  $architectureClasses = @($analyzed | Where-Object { $_.architectureHits.Count -gt 0 })
  $sourceMetadataClasses = @($analyzed | Where-Object {
      $_.sourceSignals -contains 'source-file-attribute' -or
      $_.sourceSignals -contains 'java-filename' -or
      $_.sourceSignals -contains 'kotlin-filename' -or
      $_.sourceSignals -contains 'scala-filename' -or
      $_.sourceSignals -contains 'groovy-filename'
    })
  $pathClasses = @($analyzed | Where-Object {
      $_.sourceSignals -contains 'windows-local-path' -or
      $_.sourceSignals -contains 'windows-local-path-slash' -or
      $_.sourceSignals -contains 'unix-local-path' -or
      $_.sourceSignals -contains 'unix-user-path' -or
      $_.sourceSignals -contains 'source-tree-path'
    })
  $termCounts = [ordered]@{}
  foreach ($term in $architectureTerms)
  {
    $termCounts[$term] = @($analyzed | Where-Object {
        $_.architectureHits -contains $term
      }).Count
  }
  $sourceSignalCounts = [ordered]@{}
  foreach ($signal in $sourceSignals.Keys)
  {
    $sourceSignalCounts[$signal] = @($analyzed | Where-Object {
        $_.sourceSignals -contains $signal
      }).Count
  }
  $sourceMapFiles = @($files | Where-Object {
      $_.Name.ToLowerInvariant().EndsWith('.map') -or
      $_.Name.ToLowerInvariant().EndsWith('.sourcemap')
    }).Count
  $nestedSourceMapEntries = @($allLogicalEntries | Where-Object {
      $_.ToLowerInvariant().EndsWith('.map') -or
      $_.ToLowerInvariant().EndsWith('.sourcemap')
    }).Count
  $sampleClasses = @(
    $internal | Select-Object -First 20 | ForEach-Object { $_.name }
  )
  $architectureSamples = @(
    $architectureClasses | Select-Object -First 20 | ForEach-Object {
      [ordered]@{ name = $_.name; hits = @($_.architectureHits) }
    }
  )

  return [ordered]@{
    profile = $ExpectedProfile
    archive = $ArchivePath
    archiveSha256 = Sha256 $ArchivePath
    archiveBytes = [long](Get-Item -LiteralPath $ArchivePath).Length
    extractedFiles = $files.Count
    extractedBytes = $outerFileBytes
    nestedArchives = $nestedArchives.Count
    nestedEntries = $allLogicalEntries.Count
    classEntries = $classCount
    internalClassEntries = $internal.Count
    internalPackagePrefixes = @($internalPackages | Sort-Object -Unique)
    architectureTermHitClassCount = $architectureClasses.Count
    architectureTermClassCounts = $termCounts
    architectureClassSamples = $architectureSamples
    sourceMetadataClassCount = $sourceMetadataClasses.Count
    localPathSignalClassCount = $pathClasses.Count
    sourceSignalClassCounts = $sourceSignalCounts
    sourceMapFiles = $sourceMapFiles + $nestedSourceMapEntries
    privateMaterialEntryCount = $forbiddenFiles.Count
    privateMaterialEntries = @($forbiddenFiles | Sort-Object -Unique)
    serviceLoaderEntryCount = $serviceLoaderEntries.Count
    classAnalysisSkipped = @($classAnalyses | Where-Object { $_.skipped }).Count
    internalClassSamples = $sampleClasses
    staticInspection = [ordered]@{
      decompiler = 'NOT_RUN'
      reason = 'The scanner records available tools and comparative signals; it does not expose proprietary decompiled code.'
    }
  }
}

function AddComparison(
  [string]$Name,
  [System.Collections.IDictionary]$Left,
  [System.Collections.IDictionary]$Right
)
{
  $properties = @(
    'archiveBytes', 'extractedBytes', 'extractedFiles', 'classEntries',
    'internalClassEntries', 'architectureTermHitClassCount',
    'sourceMetadataClassCount', 'localPathSignalClassCount', 'sourceMapFiles',
    'privateMaterialEntryCount', 'serviceLoaderEntryCount'
  )
  $deltas = [ordered]@{}
  foreach ($property in $properties)
  {
    $deltas[$property] = [ordered]@{
      from = $Left.profile
      to = $Right.profile
      fromValue = $Left[$property]
      toValue = $Right[$property]
      delta = [long]$Right[$property] - [long]$Left[$property]
    }
  }
  $deltas['architecturePackageDelta'] = [ordered]@{
    from = $Left.profile
    to = $Right.profile
    removed = @($Left.internalPackagePrefixes | Where-Object {
        $_ -notin @($Right.internalPackagePrefixes)
      })
    retained = @($Left.internalPackagePrefixes | Where-Object {
        $_ -in @($Right.internalPackagePrefixes)
      })
    added = @($Right.internalPackagePrefixes | Where-Object {
        $_ -notin @($Left.internalPackagePrefixes)
      })
  }
  $result.comparisons[$Name] = $deltas
}

function ToolInfo([string]$Name)
{
  try
  {
    $command = Get-Command $Name -ErrorAction Stop | Select-Object -First 1
    return [ordered]@{ available = $true; path = $command.Source }
  }
  catch
  {
    return [ordered]@{ available = $false; path = 'NOT_FOUND' }
  }
}

try
{
  $result.inspectionTools['javap'] = ToolInfo 'javap'
  $result.inspectionTools['cfr'] = ToolInfo 'cfr'
  $result.inspectionTools['procyon'] = ToolInfo 'procyon'
  $result.inspectionTools['jd-cli'] = ToolInfo 'jd-cli'

  $archives = [ordered]@{
    developer = $DeveloperArchive
    'protection-lite' = $ProtectionLiteArchive
    'maximum-release' = $MaximumArchive
  }
  foreach ($entry in $archives.GetEnumerator())
  {
    if ([string]::IsNullOrWhiteSpace([string]$entry.Value))
    {
      continue
    }
    $label = [string]$entry.Key
    $archivePath = ResolveArchive ([string]$entry.Value) $label
    $extractRoot = Join-Path $tempRoot $label
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot -Force
    $bundleRoot = FindBundleRoot $extractRoot $label
    $actualProfile = ReadProfile $bundleRoot
    Require ($actualProfile -eq $label) (
      "$label archive profile is '$actualProfile', expected '$label'")
    $result.profiles[$label] = ScanBundle $bundleRoot $archivePath $label
  }

  Require ($result.profiles.Contains('developer')) 'Developer archive was not measured'
  Require (
    $result.profiles.Contains('protection-lite') -or
    $result.profiles.Contains('maximum-release')
  ) 'No protected archive was measured'

  if ($result.profiles.Contains('protection-lite'))
  {
    AddComparison 'developerToProtectionLite' $result.profiles['developer'] $result.profiles['protection-lite']
  }
  if ($result.profiles.Contains('maximum-release'))
  {
    AddComparison 'developerToMaximum' $result.profiles['developer'] $result.profiles['maximum-release']
    if ($result.profiles.Contains('protection-lite'))
    {
      AddComparison 'protectionLiteToMaximum' $result.profiles['protection-lite'] $result.profiles['maximum-release']
    }
  }

  $result.openGates = @(
    'Run ordinary decompiler inspection against selected crown-jewel methods; this scanner intentionally does not publish decompiled code.',
    'Inspect protected output to prove genuine control-flow transformation, virtualization, and protected loading; static signal counts cannot prove those rings.',
    'Run the full shipped-artifact acceptance and private retrace against the licensed maximum archive.'
  )
  if (-not $result.profiles.Contains('maximum-release'))
  {
    $result.status = 'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED'
    $result.openGates += 'Supply a licensed maximum-release archive for the required developer-versus-maximum comparison.'
  }
  else
  {
    $result.status = 'PARTIAL_STATIC_AUDIT_ONLY'
  }

  $result | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Output "RE_COMPARISON_STATUS=$($result.status)"
  Write-Output "RE_COMPARISON_EVIDENCE=$evidencePath"
  if ($result.status -eq 'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED') { exit 2 }
}
catch
{
  $result.status = 'FAIL'
  $result.failure = $_.Exception.Message
  $result | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Error "Reverse-engineering comparison failed: $($_.Exception.Message)"
  exit 1
}
finally
{
  if (-not $KeepExtracted -and (Test-Path -LiteralPath $tempRoot))
  {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
