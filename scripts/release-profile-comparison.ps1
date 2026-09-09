# Compares cold-start and size characteristics of extracted Synesis release
# profiles. It never measures source classes or treats a missing maximum
# archive as a pass.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('cli', 'relay')]
  [string]$Component,

  [Parameter(Mandatory = $true)]
  [string]$DeveloperArchive,

  [Parameter(Mandatory = $true)]
  [string]$ProtectionLiteArchive,

  [string]$MaximumArchive,

  [string]$EvidenceFile,

  [int]$Samples = 5,

  [string]$JdkUnixDomainTempDirectory,

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

Require ($Samples -ge 1 -and $Samples -le 20) 'Samples must be between 1 and 20'

$evidencePath = if ([string]::IsNullOrWhiteSpace($EvidenceFile))
{
  Join-Path (Get-Location) "build\release-profile-comparison\$Component\profile-comparison.json"
}
else
{
  [IO.Path]::GetFullPath($EvidenceFile)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath) | Out-Null

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $temporaryRoot ("synesis-profile-comparison-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
Require ($tempRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) (
  "Comparison temporary directory escaped the system temporary root: $tempRoot")

$runtimeHome = Join-Path $tempRoot 'runtime-home'
$runtimeAppData = Join-Path $tempRoot 'runtime-appdata'
$runtimeLocalAppData = Join-Path $tempRoot 'runtime-localappdata'
$runtimeXdgData = Join-Path $tempRoot 'runtime-xdg-data'
$runtimeXdgConfig = Join-Path $tempRoot 'runtime-xdg-config'
$runtimeXdgCache = Join-Path $tempRoot 'runtime-xdg-cache'
New-Item -ItemType Directory -Force -Path @(
  $runtimeHome, $runtimeAppData, $runtimeLocalAppData, $runtimeXdgData,
  $runtimeXdgConfig, $runtimeXdgCache
) | Out-Null

$result = [ordered]@{
  schema = 1
  component = $Component
  samplesPerProfile = $Samples
  measurement = 'COLD_PROCESS_LAUNCH; BOUNDED_PROFILE_SMOKE_ONLY'
  runtime = [ordered]@{
    os = [Environment]::OSVersion.VersionString
    processArchitecture = if ([Environment]::Is64BitProcess) { 'x64' } else { 'x86' }
    jdkUnixDomainTempDirectory = if ([string]::IsNullOrWhiteSpace($JdkUnixDomainTempDirectory)) {
      'NOT_SUPPLIED'
    } else {
      [IO.Path]::GetFullPath($JdkUnixDomainTempDirectory)
    }
    scope = 'PROCESS_LOCAL_ONLY; NOT_SHIPPED_LAUNCHER_CONFIGURATION'
  }
  profiles = [ordered]@{}
  comparison = [ordered]@{}
  status = 'NOT_STARTED'
}

function Sha256([string]$Path)
{
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ResolveArchive([string]$Path, [string]$ProfileName)
{
  Require (-not [string]::IsNullOrWhiteSpace($Path)) "$ProfileName archive path is empty"
  $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
  Require (Test-Path -LiteralPath $resolved -PathType Leaf) "$ProfileName archive is missing: $resolved"
  Require ([IO.Path]::GetExtension($resolved).Equals('.zip', [StringComparison]::OrdinalIgnoreCase)) (
    "$ProfileName archive must be a ZIP: $resolved")
  return $resolved
}

function FindBundleRoot([string]$ExtractedRoot, [string]$ProfileName)
{
  $candidates = @((Get-Item -LiteralPath $ExtractedRoot))
  $candidates += @(Get-ChildItem -LiteralPath $ExtractedRoot -Directory)
  $matches = @($candidates | Where-Object {
      Test-Path -LiteralPath (Join-Path $_.FullName 'bin') -PathType Container
    })
  Require ($matches.Count -eq 1) (
    "$ProfileName archive must contain exactly one bundle root with bin/: $ExtractedRoot")
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

function BundleMeasurements([string]$BundleRoot)
{
  $files = @(Get-ChildItem -LiteralPath $BundleRoot -Recurse -File)
  $bytes = if ($files.Count -eq 0) { [long]0 } else {
    [long](($files | Measure-Object -Property Length -Sum).Sum)
  }
  return [ordered]@{
    extractedFiles = $files.Count
    extractedBytes = $bytes
  }
}

function LauncherPath([string]$BundleRoot)
{
  $names = if ($env:OS -eq 'Windows_NT') {
    if ($Component -eq 'cli') { @('bin\synesis.cmd', 'bin\synesis') } else {
      @('bin\synesis-relay.bat', 'bin\synesis-relay')
    }
  } else {
    if ($Component -eq 'cli') { @('bin/synesis') } else { @('bin/synesis-relay') }
  }
  foreach ($name in $names)
  {
    $candidate = Join-Path $BundleRoot $name
    if (Test-Path -LiteralPath $candidate -PathType Leaf)
    {
      if ($env:OS -ne 'Windows_NT')
      {
        & chmod +x -- $candidate
        Require ($LASTEXITCODE -eq 0) "Could not make launcher executable: $candidate"
      }
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  throw "Shipped $Component launcher is missing from $BundleRoot"
}

function ApplyRuntimeEnvironment([Diagnostics.ProcessStartInfo]$StartInfo)
{
  $StartInfo.Environment['HOME'] = $runtimeHome
  $StartInfo.Environment['USERPROFILE'] = $runtimeHome
  $StartInfo.Environment['APPDATA'] = $runtimeAppData
  $StartInfo.Environment['LOCALAPPDATA'] = $runtimeLocalAppData
  $StartInfo.Environment['XDG_DATA_HOME'] = $runtimeXdgData
  $StartInfo.Environment['XDG_CONFIG_HOME'] = $runtimeXdgConfig
  $StartInfo.Environment['XDG_CACHE_HOME'] = $runtimeXdgCache
  foreach ($variable in @(
    'JDK_JAVA_OPTIONS',
    '_JAVA_OPTIONS',
    'JAVA_TOOL_OPTIONS',
    'JAVA_HOME',
    'SYNESIS_JAVA',
    'SYNESIS_PROTECTION_SEED',
    'SYNESIS_MAXIMUM_PROTECTOR',
    'SYNESIS_MAXIMUM_CONFIG',
    'SYNESIS_MANIFEST_PRIVATE_KEY_B64',
    'SYNESIS_ACCEPTANCE_MANIFEST_PUBLIC_KEY_B64'
  ))
  {
    [void]$StartInfo.Environment.Remove($variable)
  }
  if (-not [string]::IsNullOrWhiteSpace($JdkUnixDomainTempDirectory))
  {
    $override = [IO.Path]::GetFullPath($JdkUnixDomainTempDirectory)
    New-Item -ItemType Directory -Force -Path $override | Out-Null
    $StartInfo.Environment['JDK_JAVA_OPTIONS'] = "-Djdk.net.unixdomain.tmpdir=$override"
  }
}

function WindowsQuote([string]$Value)
{
  Require (-not $Value.Contains('"')) "Comparison argument contains an unsupported quote: $Value"
  return '"' + $Value + '"'
}

function InvokeProfileSample([string]$Launcher, [string[]]$Arguments, [string]$Label)
{
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.WorkingDirectory = $tempRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  ApplyRuntimeEnvironment $startInfo
  if ($env:OS -eq 'Windows_NT')
  {
    $startInfo.FileName = 'cmd.exe'
    $parts = [System.Collections.Generic.List[string]]::new()
    [void]$parts.Add((WindowsQuote $Launcher))
    foreach ($argument in $Arguments) { [void]$parts.Add((WindowsQuote $argument)) }
    $startInfo.Arguments = '/d /c call ' + ($parts -join ' ')
  }
  else
  {
    $startInfo.FileName = $Launcher
    foreach ($argument in $Arguments) { [void]$startInfo.ArgumentList.Add($argument) }
  }

  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  $clock = [Diagnostics.Stopwatch]::StartNew()
  Require $process.Start() "Could not start ${Label}: $Launcher"
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Close()
  if (-not $process.WaitForExit(30000))
  {
    try { $process.Kill($true) } catch { }
    throw "$Label exceeded the 30s comparison timeout"
  }
  $clock.Stop()
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  $output = ($stdout + "`n" + $stderr).Trim()
  $peak = $null
  try {
    $candidatePeak = [long]$process.PeakWorkingSet64
    if ($candidatePeak -gt 0) { $peak = $candidatePeak }
  } catch { }
  $exitCode = $process.ExitCode
  $process.Dispose()
  if ($Component -eq 'cli')
  {
    Require ($exitCode -eq 0 -and $output -match 'SYNESIS_VERSION=') (
      "$Label did not return a valid shipped CLI version: exit=$exitCode output=$output")
  }
  else
  {
    Require ($exitCode -ne 0 -and $output -match '(?i)usage: synesis-relay') (
      "$Label did not reach the shipped relay parser: exit=$exitCode output=$output")
  }
  return [ordered]@{
    durationMs = [math]::Round($clock.Elapsed.TotalMilliseconds, 3)
    exitCode = $exitCode
    peakWorkingSetBytes = $peak
  }
}

function Median([object[]]$Values)
{
  $numbers = @($Values | ForEach-Object { [double]$_ } | Sort-Object)
  Require ($numbers.Count -gt 0) 'Cannot calculate a median with no samples'
  $middle = [int][math]::Floor($numbers.Count / 2)
  if (($numbers.Count % 2) -eq 1) { return $numbers[$middle] }
  return [math]::Round(($numbers[$middle - 1] + $numbers[$middle]) / 2, 3)
}

function MeasureProfile([string]$ProfileName, [string]$Archive, [string]$ExpectedProfile)
{
  $archivePath = ResolveArchive $Archive $ProfileName
  $extractRoot = Join-Path $tempRoot $ProfileName
  New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
  Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot -Force
  $bundleRoot = FindBundleRoot $extractRoot $ProfileName
  $actualProfile = ReadProfile $bundleRoot
  Require ($actualProfile -eq $ExpectedProfile) (
    "$ProfileName archive profile is '$actualProfile', expected '$ExpectedProfile'")
  $launcher = LauncherPath $bundleRoot
  $measurements = BundleMeasurements $bundleRoot
  $sampleResults = @()
  $arguments = if ($Component -eq 'cli') { @('version') } else { @('--not-an-option') }
  for ($index = 1; $index -le $Samples; $index++)
  {
    $sampleResults += ,(InvokeProfileSample $launcher $arguments "$ProfileName sample $index")
  }
  $durations = @($sampleResults | ForEach-Object { $_.durationMs })
  $peaks = @($sampleResults | ForEach-Object {
      if ($null -ne $_.peakWorkingSetBytes) { $_.peakWorkingSetBytes }
    })
  return [ordered]@{
    profile = $ExpectedProfile
    archive = $archivePath
    archiveSha256 = Sha256 $archivePath
    archiveBytes = [long](Get-Item -LiteralPath $archivePath).Length
    extractedFiles = $measurements.extractedFiles
    extractedBytes = $measurements.extractedBytes
    launcherSmoke = if ($Component -eq 'cli') { 'version' } else { 'guarded-parser' }
    samples = $sampleResults
    medianStartupMs = Median $durations
    maxPeakWorkingSetBytes = if ($peaks.Count -eq 0) {
      'NOT_AVAILABLE'
    } else {
      [long](($peaks | Measure-Object -Maximum).Maximum)
    }
  }
}

function AddDelta([string]$Name, [object]$Left, [object]$Right, [string]$Property)
{
  if ($null -eq $Left -or $null -eq $Right) { return }
  $result.comparison[$Name] = [ordered]@{
    from = $Left.profile
    to = $Right.profile
    property = $Property
    fromValue = $Left[$Property]
    toValue = $Right[$Property]
    delta = $Right[$Property] - $Left[$Property]
  }
}

try
{
  $result.profiles['developer'] = MeasureProfile 'developer' $DeveloperArchive 'developer'
  $result.profiles['protection-lite'] = MeasureProfile 'protection-lite' $ProtectionLiteArchive 'protection-lite'
  if (-not [string]::IsNullOrWhiteSpace($MaximumArchive))
  {
    $result.profiles['maximum-release'] = MeasureProfile 'maximum-release' $MaximumArchive 'maximum-release'
  }

  AddDelta 'developerToLiteArchiveBytes' $result.profiles['developer'] $result.profiles['protection-lite'] 'archiveBytes'
  AddDelta 'developerToLiteExtractedBytes' $result.profiles['developer'] $result.profiles['protection-lite'] 'extractedBytes'
  AddDelta 'developerToLiteMedianStartupMs' $result.profiles['developer'] $result.profiles['protection-lite'] 'medianStartupMs'
  if ($result.profiles.Contains('maximum-release'))
  {
    AddDelta 'liteToMaximumArchiveBytes' $result.profiles['protection-lite'] $result.profiles['maximum-release'] 'archiveBytes'
    AddDelta 'liteToMaximumExtractedBytes' $result.profiles['protection-lite'] $result.profiles['maximum-release'] 'extractedBytes'
    AddDelta 'liteToMaximumMedianStartupMs' $result.profiles['protection-lite'] $result.profiles['maximum-release'] 'medianStartupMs'
    $result.status = 'PASS_MEASURED_ALL_SUPPLIED_PROFILES'
  }
  else
  {
    $result.status = 'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED'
    $result.openGate = 'Supply a licensed maximum-release archive to measure and compare the commercial profile.'
  }
  $result | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Output "PROFILE_COMPARISON_STATUS=$($result.status)"
  Write-Output "PROFILE_COMPARISON_EVIDENCE=$evidencePath"
  if ($result.status -eq 'PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED') { exit 2 }
}
catch
{
  $result.status = 'FAIL'
  $result.failure = $_.Exception.Message
  $result | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $evidencePath
  Write-Error "Release profile comparison failed: $($_.Exception.Message)"
  exit 1
}
finally
{
  if (-not $KeepExtracted -and (Test-Path -LiteralPath $tempRoot))
  {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
