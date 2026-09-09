# Exercises an extracted maximum-release candidate without mutating the source checkout.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('cli', 'relay')]
  [string]$Component,

  [Parameter(Mandatory = $true)]
  [string]$Archive,

  [string]$ArtifactManifest,

  [string]$EvidenceDirectory,

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

function Sha256([string]$Path)
{
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ReadText([string]$Path)
{
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf))
  {
    return ''
  }
  return Get-Content -Raw -LiteralPath $Path
}

$archivePath = (Resolve-Path -LiteralPath $Archive).Path
Require (Test-Path -LiteralPath $archivePath -PathType Leaf) "Maximum-release archive is missing: $archivePath"

$evidencePath = if ([string]::IsNullOrWhiteSpace($EvidenceDirectory))
{
  Join-Path (Get-Location) "build\maximum-release-acceptance\$Component"
}
else
{
  [IO.Path]::GetFullPath($EvidenceDirectory)
}
New-Item -ItemType Directory -Force -Path $evidencePath | Out-Null

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("synesis-maximum-acceptance-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$result = [ordered]@{
  schema = 1
  component = $Component
  profile = 'maximum-release'
  archive = $archivePath
  archiveSha256 = Sha256 $archivePath
  archiveBytes = (Get-Item -LiteralPath $archivePath).Length
  checks = [ordered]@{}
  status = 'NOT_STARTED'
}

function RecordCheck([string]$Name, [string]$Status, [string]$Detail)
{
  $result.checks[$Name] = [ordered]@{
    status = $Status
    detail = $Detail
  }
}

function InvokeCaptured(
  [string]$FilePath,
  [string[]]$Arguments,
  [Nullable[int]]$ExpectedExitCode,
  [string]$Label,
  [int]$TimeoutSeconds = 60
)
{
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $FilePath
  $startInfo.WorkingDirectory = $tempRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  foreach ($argument in $Arguments)
  {
    [void]$startInfo.ArgumentList.Add($argument)
  }
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  Require $process.Start() "Could not start ${Label}: $FilePath"
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Close()
  if (-not $process.WaitForExit($TimeoutSeconds * 1000))
  {
    try
    {
      $process.Kill($true)
    }
    catch
    {
      # The process may have exited between the timeout and the kill attempt.
    }
    throw "$Label exceeded the ${TimeoutSeconds}s acceptance timeout"
  }
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  $combined = ($stdout + "`n" + $stderr).Trim()
  if ($null -ne $ExpectedExitCode)
  {
    Require ($process.ExitCode -eq $ExpectedExitCode.Value) (
      "$Label exited $($process.ExitCode), expected $($ExpectedExitCode.Value): $combined"
    )
  }
  [pscustomobject]@{
    ExitCode = $process.ExitCode
    Output = $combined
  }
}

function InvokeBundle([string[]]$Arguments, [string]$Label, [Nullable[int]]$ExpectedExitCode = 0)
{
  if ($script:maximumAcceptanceIsWindows)
  {
    return InvokeCaptured 'cmd.exe' (@('/d', '/c', $script:launcherPath) + $Arguments) $ExpectedExitCode $Label
  }
  return InvokeCaptured $script:launcherPath $Arguments $ExpectedExitCode $Label
}

function EnsureExecutable([string]$Path)
{
  if (-not $script:maximumAcceptanceIsWindows)
  {
    & chmod +x -- $Path
    Require ($LASTEXITCODE -eq 0) "Could not make the shipped executable runnable: $Path"
  }
}

function VerifyPrivateManifest([string]$ManifestPath, [string]$BundleRoot, [string]$ExpectedHeader)
{
  $lines = @(Get-Content -LiteralPath $ManifestPath)
  Require ($lines.Count -gt 1 -and $lines[0] -eq $ExpectedHeader) (
    "Maximum artifact manifest header is invalid: $ManifestPath"
  )
  $seen = @{}
  foreach ($line in $lines[1..($lines.Count - 1)])
  {
    if ([string]::IsNullOrWhiteSpace($line))
    {
      continue
    }
    $parts = $line -split "`t", 2
    Require ($parts.Count -eq 2 -and $parts[0] -and $parts[1] -match '^[0-9a-f]{64}$') (
      "Malformed maximum artifact manifest line: $line"
    )
    $relative = $parts[0]
    Require (-not $seen.ContainsKey($relative)) "Duplicate maximum artifact manifest path: $relative"
    $seen[$relative] = $true
    $file = [IO.Path]::GetFullPath((Join-Path $BundleRoot ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)))
    $root = [IO.Path]::GetFullPath($BundleRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    Require $file.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) (
      "Maximum artifact manifest escapes the bundle root: $relative"
    )
    Require (Test-Path -LiteralPath $file -PathType Leaf) "Manifest file is missing from the bundle: $relative"
    Require ((Sha256 $file) -eq $parts[1]) "Maximum artifact hash mismatch: $relative"
  }
  $bundleFiles = @(Get-ChildItem -LiteralPath $BundleRoot -Recurse -File -ErrorAction SilentlyContinue)
  foreach ($bundleFile in $bundleFiles)
  {
    $relative = [IO.Path]::GetRelativePath($BundleRoot, $bundleFile.FullName).Replace('\', '/')
    Require $seen.ContainsKey($relative) "Bundle file is absent from the private manifest: $relative"
  }
  Require ($seen.Count -eq $bundleFiles.Count) (
    "Private manifest entry count $($seen.Count) does not match bundle file count $($bundleFiles.Count)"
  )
  return $seen.Count
}

function ExercisePrivateManifestTamper(
  [string]$ManifestPath,
  [string]$BundleRoot,
  [string]$ExpectedHeader
)
{
  $candidate = @(Get-ChildItem -LiteralPath $BundleRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object {
      $_.Length -gt 0 -and $_.Name -notin @('PROTECTION_PROFILE', 'VERSION')
    } |
    Sort-Object FullName |
    Select-Object -First 1)
  Require ($candidate.Count -eq 1) 'Maximum bundle has no non-marker file for the disposable manifest tamper check'
  $tamperPath = $candidate[0].FullName
  $original = [IO.File]::ReadAllBytes($tamperPath)
  try
  {
    $tampered = [byte[]]::new($original.Length + 1)
    [Array]::Copy($original, $tampered, $original.Length)
    $tampered[$original.Length] = [byte]0xA5
    [IO.File]::WriteAllBytes($tamperPath, $tampered)
    $rejected = $false
    $detail = ''
    try
    {
      [void](VerifyPrivateManifest $ManifestPath $BundleRoot $ExpectedHeader)
    }
    catch
    {
      $rejected = $true
      $detail = $_.Exception.Message
    }
    Require $rejected 'The private artifact manifest accepted a modified disposable bundle file'
    return "Modified $($tamperPath.Substring($BundleRoot.Length)) was rejected: $detail"
  }
  finally
  {
    [IO.File]::WriteAllBytes($tamperPath, $original)
  }
}

try
{
  $script:maximumAcceptanceIsWindows = $env:OS -eq 'Windows_NT'
  Expand-Archive -LiteralPath $archivePath -DestinationPath $tempRoot -Force
  $bundleCandidates = @(Get-ChildItem -LiteralPath $tempRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'PROTECTION_PROFILE') -PathType Leaf })
  Require ($bundleCandidates.Count -eq 1) "Expected exactly one maximum-release bundle root in the archive"
  $bundleRoot = $bundleCandidates[0].FullName
  $profile = (ReadText (Join-Path $bundleRoot 'PROTECTION_PROFILE')).Trim()
  Require ($profile -eq 'maximum-release') "Candidate profile marker is not maximum-release: $profile"
  RecordCheck 'profile-marker' 'PASS' $profile

  $forbidden = @(Get-ChildItem -LiteralPath $bundleRoot -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object {
      $name = $_.Name.ToLowerInvariant()
      $name -in @(
        'mapping.txt',
        'seeds.txt',
        'usage.txt',
        'provenance.json',
        'artifact-manifest.txt',
        'release-record.properties'
      ) -or
        $name.EndsWith('.java') -or $name.EndsWith('.sourcemap') -or $name.EndsWith('.pdb') -or
        $name.EndsWith('.map') -or $name.EndsWith('.dwo') -or $name.EndsWith('.debug') -or
        $name.EndsWith('.sym') -or $name.EndsWith('.retrace') -or $name.EndsWith('.mapping') -or
        $name.EndsWith('.seed') -or $name.EndsWith('.seeds') -or $name.EndsWith('.pem') -or
        $name.EndsWith('.p12') -or $name.EndsWith('.pfx') -or $name.EndsWith('.jks') -or
        $name.EndsWith('.keystore') -or $_.FullName -match '(?i)[\\/][^\\/]+\.dsym[\\/]' -or
        $_.FullName -match '(?i)[\\/](private|mappings?|symbols?)[\\/]'
    })
  Require ($forbidden.Count -eq 0) (
    "Private or source material entered the maximum customer bundle: $($forbidden.FullName -join ', ')"
  )
  RecordCheck 'private-material-leakage' 'PASS' 'No mappings, seeds, private records, source files, source maps, or native debug files found'

  if (-not [string]::IsNullOrWhiteSpace($ArtifactManifest))
  {
    $manifestPath = (Resolve-Path -LiteralPath $ArtifactManifest).Path
    $manifestHeader = if ($Component -eq 'relay') { '# SYNESIS_MAXIMUM_RELAY_MANIFEST_V1' } else { '# SYNESIS_MAXIMUM_RELEASE_MANIFEST_V1' }
    $manifestCount = VerifyPrivateManifest $manifestPath $bundleRoot $manifestHeader
    RecordCheck 'private-artifact-manifest' 'PASS' "$manifestCount bundle files matched the private SHA-256 manifest"
    $tamperDetail = ExercisePrivateManifestTamper $manifestPath $bundleRoot $manifestHeader
    RecordCheck 'private-artifact-manifest-tamper-difference' 'PASS' $tamperDetail
  }
  else
  {
    RecordCheck 'private-artifact-manifest' 'NOT_SUPPLIED' 'Pass -ArtifactManifest from the private release directory to verify every shipped file'
    RecordCheck 'private-artifact-manifest-tamper-difference' 'NOT_SUPPLIED' 'Requires -ArtifactManifest; this static digest check is not runtime tamper acceptance'
  }

  if ($Component -eq 'cli')
  {
    $script:launcherPath = Join-Path $bundleRoot 'bin\synesis.cmd'
    if (-not (Test-Path -LiteralPath $script:launcherPath -PathType Leaf))
    {
      $script:launcherPath = Join-Path $bundleRoot 'bin/synesis'
    }
    Require (Test-Path -LiteralPath $script:launcherPath -PathType Leaf) "Maximum CLI launcher is missing"
    EnsureExecutable $script:launcherPath
    $runtimeName = if ($script:maximumAcceptanceIsWindows) { 'java.exe' } else { 'java' }
    Require (Test-Path -LiteralPath (Join-Path $bundleRoot "runtime/bin/$runtimeName") -PathType Leaf) "Bundled maximum Java runtime is missing"
    $version = InvokeBundle @('version') 'maximum CLI version'
    Require ($version.Output -match 'SYNESIS_VERSION=') 'Maximum CLI version output is invalid'
    RecordCheck 'cli-version' 'PASS' $version.Output.Trim()
    $help = InvokeBundle @('--help') 'maximum CLI help'
    Require ($help.Output -match '(?i)Usage:') 'Maximum CLI help output is invalid'
    RecordCheck 'cli-help' 'PASS' 'Usage text returned from the shipped launcher'
    $uiHelp = InvokeBundle @('ui', '--help') 'maximum UI command help'
    Require ($uiHelp.Output -match 'Start Synesis locally') 'Maximum UI command metadata is missing'
    RecordCheck 'ui-command' 'PASS' 'Packaged UI command metadata returned from the shipped launcher'

    $installerName = if ($script:maximumAcceptanceIsWindows) { 'synesis-installer.exe' } else { 'synesis-installer' }
    $installer = Join-Path $bundleRoot "bin/$installerName"
    Require (Test-Path -LiteralPath $installer -PathType Leaf) 'Maximum native installer is missing'
    EnsureExecutable $installer
    $installerVersion = InvokeCaptured $installer @('version') 0 'maximum native installer version'
    Require ($installerVersion.Output -match 'SYNESIS_BOOTSTRAP_VERSION=') 'Maximum native installer version output is invalid'
    RecordCheck 'native-installer' 'PASS' $installerVersion.Output.Trim()

    $installRoot = Join-Path $tempRoot 'installed-cli'
    $installResult = InvokeCaptured $installer @(
      'install',
      '--bundle', $bundleRoot,
      '--install-dir', $installRoot,
      '--skip-path-update'
    ) 0 'maximum CLI disposable install'
    Require ($installResult.Output -match 'INSTALL_RESULT=SUCCESS') 'Maximum CLI disposable install did not report success'
    $script:launcherPath = Join-Path $installRoot 'bin\synesis.cmd'
    if (-not (Test-Path -LiteralPath $script:launcherPath -PathType Leaf))
    {
      $script:launcherPath = Join-Path $installRoot 'bin/synesis'
    }
    Require (Test-Path -LiteralPath $script:launcherPath -PathType Leaf) 'Maximum installed stable launcher is missing'
    EnsureExecutable $script:launcherPath
    $installedVersion = InvokeBundle @('version') 'maximum installed CLI version'
    Require ($installedVersion.Output -match 'SYNESIS_VERSION=') 'Maximum installed CLI version output is invalid'
    RecordCheck 'installed-cli-launcher' 'PASS' 'Disposable installation used --skip-path-update and the stable launcher reached the protected payload'

    $pointerPath = Join-Path $installRoot 'current.json'
    $pointer = Get-Content -Raw -LiteralPath $pointerPath | ConvertFrom-Json
    $payloadRoot = Join-Path (Join-Path $installRoot 'versions') $pointer.payloadDirectory
    $tamperCandidates = @(Get-ChildItem -LiteralPath $payloadRoot -Recurse -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notin @('manifest.json', 'PROTECTION_PROFILE') -and $_.Length -gt 0 } |
      Sort-Object FullName |
      Select-Object -First 1)
    Require ($tamperCandidates.Count -eq 1) 'Installed maximum payload has no disposable tamper candidate'
    $tamperPath = $tamperCandidates[0].FullName
    $tamperOriginal = [IO.File]::ReadAllBytes($tamperPath)
    try
    {
      if ($script:maximumAcceptanceIsWindows)
      {
        & attrib -R $tamperPath
        Require ($LASTEXITCODE -eq 0) "Could not make the disposable payload file writable: $tamperPath"
      }
      else
      {
        & chmod u+w -- $tamperPath
        Require ($LASTEXITCODE -eq 0) "Could not make the disposable payload file writable: $tamperPath"
      }
      $tampered = [byte[]]::new($tamperOriginal.Length + 1)
      [Array]::Copy($tamperOriginal, $tampered, $tamperOriginal.Length)
      $tampered[$tamperOriginal.Length] = [byte]0xA5
      [IO.File]::WriteAllBytes($tamperPath, $tampered)
      $tamperResult = InvokeBundle @('version') 'maximum launcher after immutable-payload tamper' 1
      Require ($tamperResult.ExitCode -ne 0) 'Maximum stable launcher accepted an edited immutable payload'
      RecordCheck 'runtime-tamper-refusal' 'PASS' "Stable launcher refused an edited payload file: $($tamperPath.Substring($payloadRoot.Length + 1))"
    }
    finally
    {
      [IO.File]::WriteAllBytes($tamperPath, $tamperOriginal)
      if ($script:maximumAcceptanceIsWindows)
      {
        & attrib +R $tamperPath
      }
      else
      {
        & chmod u-w -- $tamperPath
      }
    }

    $mutableState = Join-Path $installRoot 'Link\acceptance-state.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $mutableState) | Out-Null
    Set-Content -LiteralPath $mutableState -Value 'mutable acceptance state'
    [void](InvokeBundle @('version') 'maximum launcher with mutable installation state')
    RecordCheck 'mutable-state-no-false-positive' 'PASS' 'Stable launcher continued after mutable install state was added outside the immutable payload'

    $nativeMcpName = if ($script:maximumAcceptanceIsWindows) { 'synesis-mcp.exe' } else { 'synesis-mcp' }
    $nativeMcp = Join-Path $bundleRoot "bin/$nativeMcpName"
    Require (Test-Path -LiteralPath $nativeMcp -PathType Leaf) 'Maximum native MCP launcher is missing'
    EnsureExecutable $nativeMcp
    $nativeMcpVersion = InvokeCaptured $nativeMcp @('version') 0 'maximum native MCP version'
    Require ($nativeMcpVersion.Output -match 'SYNESIS_VERSION=') 'Maximum native MCP launcher did not reach the shipped CLI'
    RecordCheck 'native-mcp' 'PASS' $nativeMcpVersion.Output.Trim()

    $project = Join-Path $tempRoot 'acceptance-project'
    New-Item -ItemType Directory -Force -Path $project | Out-Null
    & git -C $project init | Out-Null
    Require ($LASTEXITCODE -eq 0) 'Could not initialize the disposable maximum acceptance project'
    & git -C $project config user.name 'Synesis Maximum Acceptance'
    & git -C $project config user.email 'synesis-maximum-acceptance@example.invalid'
    Set-Content -LiteralPath (Join-Path $project 'README.md') -Value 'Synesis maximum acceptance project'
    & git -C $project add README.md
    & git -C $project commit -m 'Initial maximum acceptance baseline' | Out-Null
    Require ($LASTEXITCODE -eq 0) 'Could not commit the disposable maximum acceptance project'
    [void](InvokeBundle @('init', '--project', $project) 'maximum project init')
    [void](InvokeBundle @('provider', 'list', '--project', $project) 'maximum provider list')
    [void](InvokeBundle @('provider', 'install', 'claude', '--project', $project) 'maximum provider install')
    [void](InvokeBundle @('provider', 'status', 'claude', '--project', $project) 'maximum provider status')
    [void](InvokeBundle @('provider', 'uninstall', 'claude', '--project', $project) 'maximum provider uninstall')
    [void](InvokeBundle @('doctor', '--project', $project) 'maximum doctor')
    [void](InvokeBundle @('ui', '--project', $project, '--duration-seconds', '1', '--no-browser') 'maximum UI/control-plane smoke')
    RecordCheck 'cli-ui-control-plane-provider' 'PASS' 'Disposable project, provider boundary, doctor, and no-browser UI/control-plane smoke passed'

    $mcpInfo = [Diagnostics.ProcessStartInfo]::new()
    if ($script:maximumAcceptanceIsWindows)
    {
      $mcpInfo.FileName = 'cmd.exe'
      foreach ($argument in (@('/d', '/c', $script:launcherPath, 'mcp', '--provider', 'codex', '--project', $project, '--connection-instance-id', 'maximum-acceptance-1')))
      {
        [void]$mcpInfo.ArgumentList.Add($argument)
      }
    }
    else
    {
      $mcpInfo.FileName = $script:launcherPath
      foreach ($argument in @('mcp', '--provider', 'codex', '--project', $project, '--connection-instance-id', 'maximum-acceptance-1'))
      {
        [void]$mcpInfo.ArgumentList.Add($argument)
      }
    }
    $mcpInfo.WorkingDirectory = $tempRoot
    $mcpInfo.UseShellExecute = $false
    $mcpInfo.CreateNoWindow = $true
    $mcpInfo.RedirectStandardInput = $true
    $mcpInfo.RedirectStandardOutput = $true
    $mcpInfo.RedirectStandardError = $true
    $mcpProcess = [Diagnostics.Process]::new()
    $mcpProcess.StartInfo = $mcpInfo
    Require $mcpProcess.Start() 'Could not start maximum shipped MCP process'
    $mcpProcess.StandardInput.WriteLine('{"jsonrpc":"2.0","id":1,"method":"initialize"}')
    $mcpProcess.StandardInput.WriteLine('{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
    $mcpProcess.StandardInput.WriteLine('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ensure_session","arguments":{}}}')
    $mcpProcess.StandardInput.Close()
    $mcpOutputTask = $mcpProcess.StandardOutput.ReadToEndAsync()
    $mcpErrorTask = $mcpProcess.StandardError.ReadToEndAsync()
    Require $mcpProcess.WaitForExit(60000) 'Maximum shipped MCP process exceeded the 60s timeout'
    $mcpOutput = $mcpOutputTask.GetAwaiter().GetResult()
    $mcpError = $mcpErrorTask.GetAwaiter().GetResult()
    Require ($mcpProcess.ExitCode -eq 0) "Maximum shipped MCP process failed: $mcpOutput`n$mcpError"
    Require ($mcpOutput -match 'protocolVersion' -and $mcpOutput -match 'ensure_session' -and $mcpOutput -match 'ready') 'Maximum shipped MCP protocol smoke was incomplete'
    RecordCheck 'mcp-boundary' 'PASS' 'Shipped MCP initialize, tools/list, and ensure_session exchange completed; native Link/overlay acceptance remains separate'
  }
  else
  {
    $script:launcherPath = Join-Path $bundleRoot 'bin\synesis-relay.bat'
    if (-not (Test-Path -LiteralPath $script:launcherPath -PathType Leaf))
    {
      $script:launcherPath = Join-Path $bundleRoot 'bin/synesis-relay'
    }
    Require (Test-Path -LiteralPath $script:launcherPath -PathType Leaf) 'Maximum relay launcher is missing'
    EnsureExecutable $script:launcherPath
    $relay = InvokeBundle @('--not-an-option') 'maximum relay guarded parser' $null
    Require ($relay.ExitCode -ne 0 -and $relay.Output -match '(?i)usage: synesis-relay') 'Maximum relay launcher did not reach its guarded parser'
    RecordCheck 'relay-launcher' 'PASS' 'Shipped relay launcher reached its guarded parser'
    RecordCheck 'relay-auth-forwarding' 'NOT_EXECUTED' 'A licensed protected relay socket/authentication scenario is still required'
  }

  $result.status = 'PASS_WITH_EXPLICIT_OPEN_GATES'
  $result.extractedBundle = $bundleRoot
  $result.keepExtracted = [bool]$KeepExtracted
  $result.evidenceDirectory = $evidencePath
  $resultPath = Join-Path $evidencePath 'maximum-release-acceptance.json'
  $result | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $resultPath
  Write-Output "PASS: maximum-release shipped-artifact acceptance recorded at $resultPath"
}
catch
{
  $result.status = 'FAIL'
  $result.failure = $_.Exception.Message
  $resultPath = Join-Path $evidencePath 'maximum-release-acceptance.json'
  $result | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $resultPath
  Write-Error "Maximum-release acceptance failed: $($_.Exception.Message)"
  exit 1
}
finally
{
  if (-not $KeepExtracted -and (Test-Path -LiteralPath $tempRoot))
  {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}
