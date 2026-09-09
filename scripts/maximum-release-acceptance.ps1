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

  [string]$JdkUnixDomainTempDirectory,

  [string]$LocalAppDataOverride,

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

if (-not [string]::IsNullOrWhiteSpace($JdkUnixDomainTempDirectory))
{
  $JdkUnixDomainTempDirectory = [IO.Path]::GetFullPath($JdkUnixDomainTempDirectory)
  New-Item -ItemType Directory -Force -Path $JdkUnixDomainTempDirectory | Out-Null
}
if (-not [string]::IsNullOrWhiteSpace($LocalAppDataOverride))
{
  $LocalAppDataOverride = [IO.Path]::GetFullPath($LocalAppDataOverride)
  New-Item -ItemType Directory -Force -Path $LocalAppDataOverride | Out-Null
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("synesis-maximum-acceptance-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$runtimeUserStateRoot = Join-Path $tempRoot 'runtime-user-state'
$runtimeHome = Join-Path $runtimeUserStateRoot 'profile'
$runtimeAppData = Join-Path $runtimeUserStateRoot 'roaming'
$runtimeLocalAppData = if ([string]::IsNullOrWhiteSpace($LocalAppDataOverride)) {
  Join-Path $runtimeUserStateRoot 'local'
} else {
  $LocalAppDataOverride
}
$runtimeXdgData = Join-Path $runtimeUserStateRoot 'xdg-data'
$runtimeXdgConfig = Join-Path $runtimeUserStateRoot 'xdg-config'
$runtimeXdgCache = Join-Path $runtimeUserStateRoot 'xdg-cache'
$runtimeCodexHome = Join-Path $runtimeHome '.codex'
$runtimeClaudeConfig = Join-Path $runtimeHome '.claude'
New-Item -ItemType Directory -Force -Path $runtimeHome, $runtimeAppData, $runtimeLocalAppData, $runtimeXdgData, $runtimeXdgConfig, $runtimeXdgCache, $runtimeCodexHome, $runtimeClaudeConfig | Out-Null
$result = [ordered]@{
  schema = 1
  component = $Component
  profile = 'maximum-release'
  archive = $archivePath
  archiveSha256 = Sha256 $archivePath
  archiveBytes = (Get-Item -LiteralPath $archivePath).Length
  runtimeOverrides = [ordered]@{
    jdkUnixDomainTempDirectory = if ([string]::IsNullOrWhiteSpace($JdkUnixDomainTempDirectory)) { 'NOT_SUPPLIED' } else { $JdkUnixDomainTempDirectory }
    localAppData = $runtimeLocalAppData
    userStateRoot = $runtimeUserStateRoot
    scope = 'PROCESS_LOCAL_ONLY; NOT_SHIPPED_LAUNCHER_CONFIGURATION'
  }
  checks = [ordered]@{}
  status = 'NOT_STARTED'
}
$script:environmentBlocked = $false
$script:runtimeBlocked = $false

function RecordCheck([string]$Name, [string]$Status, [string]$Detail)
{
  $result.checks[$Name] = [ordered]@{
    status = $Status
    detail = $Detail
  }
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
  $StartInfo.Environment['CODEX_HOME'] = $runtimeCodexHome
  $StartInfo.Environment['CLAUDE_CONFIG_DIR'] = $runtimeClaudeConfig
  # Do not let a developer or CI secret/configuration silently change the
  # shipped-artifact result. The bundled launcher/runtime must be the subject
  # of this acceptance run; only the explicit JDK compatibility override may
  # add a JVM option.
  foreach ($variable in @(
    'JDK_JAVA_OPTIONS',
    '_JAVA_OPTIONS',
    'JAVA_TOOL_OPTIONS',
    'JAVA_HOME',
    'SYNESIS_JAVA',
    'SYNESIS_MANIFEST_PRIVATE_KEY_B64',
    'SYNESIS_ACCEPTANCE_MANIFEST_PUBLIC_KEY_B64',
    'SYNESIS_PROTECTION_SEED',
    'SYNESIS_MAXIMUM_PROTECTOR',
    'SYNESIS_MAXIMUM_CONFIG',
    'SYNESIS_MANIFEST_URL'
  ))
  {
    [void]$StartInfo.Environment.Remove($variable)
  }
  if (-not [string]::IsNullOrWhiteSpace($JdkUnixDomainTempDirectory))
  {
    $StartInfo.Environment['JDK_JAVA_OPTIONS'] = "-Djdk.net.unixdomain.tmpdir=$JdkUnixDomainTempDirectory"
  }
}

function InvokeCaptured(
  [string]$FilePath,
  [string[]]$Arguments,
  [Nullable[int]]$ExpectedExitCode,
  [string]$Label,
  [int]$TimeoutSeconds = 60,
  [string]$RawArguments = $null
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
  ApplyRuntimeEnvironment $startInfo
  if (-not [string]::IsNullOrEmpty($RawArguments))
  {
    $startInfo.Arguments = $RawArguments
  }
  else
  {
    foreach ($argument in $Arguments)
    {
      [void]$startInfo.ArgumentList.Add($argument)
    }
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
    Require ($process.ExitCode -eq [int]$ExpectedExitCode) (
      "$Label exited $($process.ExitCode), expected $([int]$ExpectedExitCode): $combined"
    )
  }
  [pscustomobject]@{
    ExitCode = $process.ExitCode
    Output = $combined
  }
}

function GetWindowsLauncherCommandLine([string[]]$Arguments)
{
  $parts = [System.Collections.Generic.List[string]]::new()
  [void]$parts.Add('"' + $script:launcherPath + '"')
  foreach ($argument in $Arguments)
  {
    Require (-not $argument.Contains('"')) (
      "Windows acceptance argument contains an unsupported quote: $argument")
    [void]$parts.Add('"' + $argument + '"')
  }
  return ($parts -join ' ')
}

function InvokeBundle([string[]]$Arguments, [string]$Label, [Nullable[int]]$ExpectedExitCode = 0)
{
  if ($script:maximumAcceptanceIsWindows)
  {
    $commandLine = GetWindowsLauncherCommandLine $Arguments
    return InvokeCaptured -FilePath 'cmd.exe' -Arguments @() -ExpectedExitCode $ExpectedExitCode -Label $Label -RawArguments ('/d /s /c "' + $commandLine + '"')
  }
  return InvokeCaptured $script:launcherPath $Arguments $ExpectedExitCode $Label
}

function StartBundleServer(
  [string[]]$Arguments,
  [string]$Label,
  [int]$ReadyTimeoutSeconds = 20
)
{
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  if ($script:maximumAcceptanceIsWindows)
  {
    $startInfo.FileName = 'cmd.exe'
    $startInfo.Arguments = '/d /s /c "' + (GetWindowsLauncherCommandLine $Arguments) + '"'
  }
  else
  {
    $startInfo.FileName = $script:launcherPath
    foreach ($argument in $Arguments)
    {
      [void]$startInfo.ArgumentList.Add($argument)
    }
  }
  $startInfo.WorkingDirectory = $tempRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  ApplyRuntimeEnvironment $startInfo
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  Require $process.Start() "Could not start ${Label}: $script:launcherPath"
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Close()
  $lines = [System.Collections.Generic.List[string]]::new()
  $ready = $null
  $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
  $lineTask = $process.StandardOutput.ReadLineAsync()
  while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited)
  {
    if (-not $lineTask.Wait(250))
    {
      continue
    }
    $line = $lineTask.GetAwaiter().GetResult()
    if ($null -eq $line)
    {
      break
    }
    [void]$lines.Add($line)
    if ($line -match 'COORDINATION_SERVE_READY endpoint=(?<endpoint>\S+)\s+project=\S+.*\s+controlBootstrap=(?<bootstrap>\S+)\s+uiRoute=/')
    {
      $ready = [pscustomobject]@{
        Endpoint = $Matches['endpoint'].TrimEnd('/')
        Bootstrap = $Matches['bootstrap']
        Line = $line
      }
      break
    }
    $lineTask = $process.StandardOutput.ReadLineAsync()
  }
  if ($null -eq $ready)
  {
    try
    {
      if (-not $process.HasExited) { $process.Kill($true) }
      [void]$process.WaitForExit(5000)
    }
    catch
    {
      # Preserve the readiness failure as the useful acceptance result.
    }
    $stderr = ''
    try
    {
      if ($stderrTask.Wait(2000)) { $stderr = $stderrTask.GetAwaiter().GetResult() }
    }
    catch
    {
      $stderr = $_.Exception.Message
    }
    throw "$Label did not report COORDINATION_SERVE_READY within ${ReadyTimeoutSeconds}s. stdout=$($lines -join "`n") stderr=$stderr"
  }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  return [pscustomobject]@{
    Process = $process
    StdoutTask = $stdoutTask
    StderrTask = $stderrTask
    Ready = $ready
  }
}

function StopBundleServer([object]$State)
{
  if ($null -eq $State)
  {
    return
  }
  try
  {
    if (-not $State.Process.HasExited) { $State.Process.Kill($true) }
    [void]$State.Process.WaitForExit(5000)
  }
  catch
  {
    # The bounded acceptance process may already have exited.
  }
  foreach ($task in @($State.StdoutTask, $State.StderrTask))
  {
    if ($null -eq $task) { continue }
    try { [void]$task.GetAwaiter().GetResult() } catch { }
  }
  try { $State.Process.Dispose() } catch { }
}

function StartBundleLineProcess(
  [string[]]$Arguments,
  [string]$Label,
  [string]$ReadyPattern,
  [int]$ReadyTimeoutSeconds = 45
)
{
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  if ($script:maximumAcceptanceIsWindows)
  {
    $startInfo.FileName = 'cmd.exe'
    $startInfo.Arguments = '/d /s /c "' + (GetWindowsLauncherCommandLine $Arguments) + '"'
  }
  else
  {
    $startInfo.FileName = $script:launcherPath
    foreach ($argument in $Arguments)
    {
      [void]$startInfo.ArgumentList.Add($argument)
    }
  }
  $startInfo.WorkingDirectory = $tempRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  ApplyRuntimeEnvironment $startInfo
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  Require $process.Start() "Could not start ${Label}: $script:launcherPath"
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Close()
  $lines = [System.Collections.Generic.List[string]]::new()
  $match = $null
  $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
  $lineTask = $process.StandardOutput.ReadLineAsync()
  while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited)
  {
    if (-not $lineTask.Wait(250))
    {
      continue
    }
    $line = $lineTask.GetAwaiter().GetResult()
    if ($null -eq $line)
    {
      break
    }
    [void]$lines.Add($line)
    if ($line -match $ReadyPattern)
    {
      $match = @{}
      foreach ($key in $Matches.Keys)
      {
        $match[$key] = $Matches[$key]
      }
      break
    }
    $lineTask = $process.StandardOutput.ReadLineAsync()
  }
  if ($null -eq $match)
  {
    try
    {
      if (-not $process.HasExited) { $process.Kill($true) }
      [void]$process.WaitForExit(5000)
    }
    catch
    {
      # Preserve the readiness failure as the useful acceptance result.
    }
    $stderr = ''
    try
    {
      if ($stderrTask.Wait(2000)) { $stderr = $stderrTask.GetAwaiter().GetResult() }
    }
    catch
    {
      $stderr = $_.Exception.Message
    }
    throw "$Label did not report the expected readiness line within ${ReadyTimeoutSeconds}s. stdout=$($lines -join "`n") stderr=$stderr"
  }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  return [pscustomobject]@{
    Process = $process
    StdoutTask = $stdoutTask
    StderrTask = $stderrTask
    Match = $match
  }
}

function FinishBundleProcess(
  [object]$State,
  [string]$Label,
  [int]$ExpectedExitCode = 0,
  [int]$TimeoutSeconds = 60
)
{
  Require $State.Process.WaitForExit($TimeoutSeconds * 1000) "$Label exceeded the ${TimeoutSeconds}s acceptance timeout"
  $stdout = $State.StdoutTask.GetAwaiter().GetResult()
  $stderr = $State.StderrTask.GetAwaiter().GetResult()
  $combined = ($stdout + "`n" + $stderr).Trim()
  Require ($State.Process.ExitCode -eq $ExpectedExitCode) (
    "$Label exited $($State.Process.ExitCode), expected ${ExpectedExitCode}: $combined"
  )
  return [pscustomobject]@{
    ExitCode = $State.Process.ExitCode
    Output = $combined
  }
}

function InvokeLinkAcceptance([string]$Project)
{
  $hostState = $null
  $hostProfile = Join-Path $tempRoot 'link-host-profile'
  $joinProfile = Join-Path $tempRoot 'link-join-profile'
  New-Item -ItemType Directory -Force -Path $hostProfile, $joinProfile | Out-Null
  try
  {
    $hostIdentity = InvokeBundle @(
      'identity', 'show', '--project', $Project, '--profile', $hostProfile
    ) 'maximum Link host identity'
    $hostMatch = [regex]::Match($hostIdentity.Output, '(?m)^NODE_ID=(sl1-[0-9a-f]{64})\s*$')
    Require $hostMatch.Success 'Maximum Link host identity output was incomplete'
    $hostId = $hostMatch.Groups[1].Value

    $joinIdentity = InvokeBundle @(
      'identity', 'show', '--project', $Project, '--profile', $joinProfile
    ) 'maximum Link join identity'
    $joinMatch = [regex]::Match($joinIdentity.Output, '(?m)^NODE_ID=(sl1-[0-9a-f]{64})\s*$')
    Require $joinMatch.Success 'Maximum Link join identity output was incomplete'
    $joinId = $joinMatch.Groups[1].Value
    Require ($hostId -ne $joinId) 'Maximum Link fixture generated duplicate identities'

    [void](InvokeBundle @(
      'project', 'create', '--project', $Project, '--profile', $hostProfile, '--peer', $joinId
    ) 'maximum Link host project configuration')
    [void](InvokeBundle @(
      'project', 'create', '--project', $Project, '--profile', $joinProfile, '--peer', $hostId
    ) 'maximum Link join project configuration')

    $hostState = StartBundleLineProcess @(
      'sync', 'host', '--project', $Project, '--profile', $hostProfile
    ) 'maximum shipped Link host' '^INVITATION=(?<invitation>\S+)\s*$'
    $invitation = $hostState.Match['invitation']
    Require (-not [string]::IsNullOrWhiteSpace($invitation)) 'Maximum Link invitation was empty'
    $joinResult = InvokeBundle @(
      'sync', 'join', $invitation, '--project', $Project, '--profile', $joinProfile,
      '--expect-host', $hostId
    ) 'maximum shipped Link join'
    Require ($joinResult.Output -match "(?m)^AUTHENTICATED_REMOTE=$hostId\s*$" -and
      $joinResult.Output -match '(?m)^SYNC_RESULT=(SUCCESS|PARTIAL_SUCCESS)\s*$') (
      "Maximum shipped Link join did not report authenticated synchronization: $($joinResult.Output)")
    $hostResult = FinishBundleProcess $hostState 'maximum shipped Link host'
    Require ($hostResult.Output -notmatch '(?i)^ERROR=') 'Maximum Link host reported an error'
    return "Two installed CLI processes completed signed invitation exchange, authenticated PeerSession, project synchronization, and cleanup between $hostId and $joinId"
  }
  finally
  {
    StopBundleServer $hostState
  }
}

function SendHttpText(
  [System.Net.Http.HttpClient]$Client,
  [System.Net.Http.HttpMethod]$Method,
  [string]$Uri,
  [hashtable]$Headers,
  [string]$Body
)
{
  $request = [System.Net.Http.HttpRequestMessage]::new($Method, $Uri)
  try
  {
    if ($null -ne $Headers)
    {
      foreach ($name in $Headers.Keys)
      {
        [void]$request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name])
      }
    }
    if ($null -ne $Body)
    {
      $request.Content = [System.Net.Http.StringContent]::new(
        $Body, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $Client.SendAsync($request).GetAwaiter().GetResult()
    try
    {
      $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
      return [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Body = $responseBody
      }
    }
    finally
    {
      $response.Dispose()
    }
  }
  finally
  {
    $request.Dispose()
  }
}

function ReadSseSnapshot(
  [System.Net.Http.HttpClient]$Client,
  [string]$Uri,
  [string]$SessionToken
)
{
  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Get, $Uri)
  $response = $null
  $stream = $null
  $cancellation = $null
  try
  {
    [void]$request.Headers.TryAddWithoutValidation(
      'Accept', 'text/event-stream')
    [void]$request.Headers.TryAddWithoutValidation(
      'X-Synesis-Control-Session', $SessionToken)
    $response = $Client.SendAsync(
      $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).
      GetAwaiter().GetResult()
    Require ([int]$response.StatusCode -eq 200) (
      "Control-plane SSE returned HTTP $([int]$response.StatusCode)")
    Require ($null -ne $response.Content.Headers.ContentType -and
      $response.Content.Headers.ContentType.MediaType -eq 'text/event-stream') (
      'Control-plane SSE response did not advertise text/event-stream')
    $stream = $response.Content.ReadAsStream()
    $cancellation = [Threading.CancellationTokenSource]::new(5000)
    $buffer = [byte[]]::new(8192)
    $text = [Text.StringBuilder]::new()
    while ($text.Length -lt 262144)
    {
      $read = $stream.ReadAsync($buffer, 0, $buffer.Length, $cancellation.Token).
        GetAwaiter().GetResult()
      if ($read -eq 0) { break }
      [void]$text.Append([Text.Encoding]::UTF8.GetString($buffer, 0, $read))
      $current = $text.ToString()
      if ($current -match '(?m)^event:\s*snapshot\s*$' -and
        $current -match '(?m)^data:')
      {
        return $current
      }
    }
    Require $false 'Control-plane SSE did not emit an initial snapshot event'
    return $text.ToString()
  }
  finally
  {
    if ($null -ne $cancellation) { $cancellation.Dispose() }
    if ($null -ne $stream) { $stream.Dispose() }
    if ($null -ne $response) { $response.Dispose() }
    $request.Dispose()
  }
}

function InvokeControlPlaneHttpAcceptance([string]$Project)
{
  $server = $null
  $client = $null
  $handler = $null
  try
  {
    $server = StartBundleServer @(
      'ui', '--project', $Project, '--duration-seconds', '30', '--no-browser'
    ) 'maximum UI/control-plane HTTP acceptance'
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    $base = $server.Ready.Endpoint
    $health = SendHttpText $client ([System.Net.Http.HttpMethod]::Get) "$base/api/v1/health" $null $null
    Require ($health.StatusCode -eq 200 -and $health.Body -match '"loopbackOnly"\s*:\s*true') (
      "Control-plane health failed: HTTP $($health.StatusCode) $($health.Body)")
    $root = SendHttpText $client ([System.Net.Http.HttpMethod]::Get) "$base/" $null $null
    Require ($root.StatusCode -eq 200 -and $root.Body -match '(?i)<html') (
      "Packaged browser UI root failed: HTTP $($root.StatusCode)")

    $invalidBody = (@{ bootstrapToken = 'invalid-maximum-acceptance-token' } |
      ConvertTo-Json -Compress)
    $invalidSession = SendHttpText $client ([System.Net.Http.HttpMethod]::Post) "$base/api/v1/session" $null $invalidBody
    Require ($invalidSession.StatusCode -eq 401) (
      "Invalid control bootstrap was not rejected: HTTP $($invalidSession.StatusCode)")

    $sessionBody = (@{ bootstrapToken = $server.Ready.Bootstrap } | ConvertTo-Json -Compress)
    $session = SendHttpText $client ([System.Net.Http.HttpMethod]::Post) "$base/api/v1/session" $null $sessionBody
    Require ($session.StatusCode -eq 201) "Control-plane session failed: HTTP $($session.StatusCode)"
    $credentials = $session.Body | ConvertFrom-Json
    $sessionToken = [string]$credentials.sessionToken
    $csrfToken = [string]$credentials.csrfToken
    Require (-not [string]::IsNullOrWhiteSpace($sessionToken) -and
      -not [string]::IsNullOrWhiteSpace($csrfToken)) 'Control-plane session credentials were incomplete'
    $reused = SendHttpText $client ([System.Net.Http.HttpMethod]::Post) "$base/api/v1/session" $null $sessionBody
    Require ($reused.StatusCode -eq 401) 'Control-plane bootstrap token was reusable'

    $sessionHeaders = @{ 'X-Synesis-Control-Session' = $sessionToken }
    $snapshot = SendHttpText $client ([System.Net.Http.HttpMethod]::Get) "$base/api/v1/snapshot" $sessionHeaders $null
    Require ($snapshot.StatusCode -eq 200 -and $snapshot.Body -match '"project"' -and
      $snapshot.Body -match '"diagnostics"' -and $snapshot.Body -match '"network"' -and
      $snapshot.Body -notlike "*$($server.Ready.Bootstrap)*") (
      "Authenticated control-plane snapshot failed: HTTP $($snapshot.StatusCode)")
    $diagnostics = SendHttpText $client ([System.Net.Http.HttpMethod]::Get) "$base/api/v1/diagnostics" $sessionHeaders $null
    Require ($diagnostics.StatusCode -eq 200 -and $diagnostics.Body -match 'schemaVersion') (
      "Authenticated diagnostics failed: HTTP $($diagnostics.StatusCode)")
    $network = SendHttpText $client ([System.Net.Http.HttpMethod]::Get) "$base/api/v1/network" $sessionHeaders $null
    Require ($network.StatusCode -eq 200 -and $network.Body -match '"relay"') (
      "Authenticated network projection failed: HTTP $($network.StatusCode)")
    $missingCsrf = (@{ operationId = 'missing'; answerUri = 'missing' } |
      ConvertTo-Json -Compress)
    $csrfCheck = SendHttpText $client ([System.Net.Http.HttpMethod]::Post) "$base/api/v1/commands/answer" $sessionHeaders $missingCsrf
    Require ($csrfCheck.StatusCode -eq 403) 'Control-plane mutation without CSRF was not rejected'
    $sse = ReadSseSnapshot $client "$base/api/v1/events" $sessionToken
    Require ($sse -match '"snapshot"') 'Control-plane SSE snapshot payload was incomplete'
  }
  finally
  {
    if ($null -ne $client) { $client.Dispose() }
    if ($null -ne $handler) { $handler.Dispose() }
    StopBundleServer $server
  }
}

function IsLoopbackEnvironmentFailure([string]$Message)
{
  return $Message -match '(?i)COORDINATION_ERROR=Unable to establish loopback connection|Unable to establish loopback connection|SocketException: Invalid argument: connect'
}

function IsLinkRuntimeBlock([string]$Message)
{
  return $Message -match '(?i)ERROR=(TRANSPORT_FAILED|SYNC_FAILED)|NO_USABLE_CANDIDATE|CONNECTION_FAILED|HOST_TIMEOUT|QUIC'
}

function EnsureExecutable([string]$Path)
{
  if (-not $script:maximumAcceptanceIsWindows)
  {
    & chmod +x -- $Path
    Require ($LASTEXITCODE -eq 0) "Could not make the shipped executable runnable: $Path"
  }
}

function MakeDisposablePayloadFileWritable([string]$Path)
{
  if ($script:maximumAcceptanceIsWindows)
  {
    & attrib -R $Path
    Require ($LASTEXITCODE -eq 0) "Could not make the disposable payload file writable: $Path"
  }
  else
  {
    & chmod u+w -- $Path
    Require ($LASTEXITCODE -eq 0) "Could not make the disposable payload file writable: $Path"
  }
}

function RestoreDisposablePayloadFileMode([string]$Path)
{
  if ($script:maximumAcceptanceIsWindows)
  {
    & attrib +R $Path
  }
  else
  {
    & chmod u-w -- $Path
  }
}

function FindFrontendAsset([string]$PayloadRoot)
{
  $jars = @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue |
    Sort-Object FullName)
  foreach ($jar in $jars)
  {
    $zip = $null
    try
    {
      $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
      $entry = @($zip.Entries |
        Where-Object {
          -not $_.FullName.EndsWith('/') -and
          $_.FullName -match '(?i)^web-ui/(index\.html|assets/.+\.(js|css))$'
        } |
        Sort-Object FullName |
        Select-Object -First 1)
      if ($entry.Count -eq 1)
      {
        return [pscustomobject]@{
          ArchivePath = $jar.FullName
          EntryName = $entry[0].FullName
        }
      }
    }
    catch
    {
      # A protected third-party JAR may not be a readable ZIP; keep looking.
    }
    finally
    {
      if ($null -ne $zip) { $zip.Dispose() }
    }
  }
  return $null
}

function ReadZipEntryBytes([string]$ArchivePath, [string]$EntryName)
{
  $zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
  try
  {
    $entry = $zip.GetEntry($EntryName)
    Require ($null -ne $entry) "Frontend asset entry is missing from the protected JAR: $EntryName"
    $input = $entry.Open()
    $memory = [IO.MemoryStream]::new()
    try
    {
      $input.CopyTo($memory)
      return ,([byte[]]$memory.ToArray())
    }
    finally
    {
      $memory.Dispose()
      $input.Dispose()
    }
  }
  finally
  {
    $zip.Dispose()
  }
}

function ReplaceZipEntryBytes([string]$ArchivePath, [string]$EntryName, [byte[]]$Bytes)
{
  $zip = [IO.Compression.ZipFile]::Open($ArchivePath, [IO.Compression.ZipArchiveMode]::Update)
  try
  {
    $entry = $zip.GetEntry($EntryName)
    Require ($null -ne $entry) "Frontend asset entry is missing from the protected JAR: $EntryName"
    $entry.Delete()
    $replacement = $zip.CreateEntry($EntryName)
    $output = $replacement.Open()
    try
    {
      $output.Write($Bytes, 0, $Bytes.Length)
    }
    finally
    {
      $output.Dispose()
    }
  }
  finally
  {
    $zip.Dispose()
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
  $forbiddenPaths = @($forbidden | ForEach-Object { $_.FullName }) -join ', '
  Require ($forbidden.Count -eq 0) (
    "Private or source material entered the maximum customer bundle: $forbiddenPaths"
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
      MakeDisposablePayloadFileWritable $tamperPath
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
      RestoreDisposablePayloadFileMode $tamperPath
    }

    $frontendAsset = FindFrontendAsset $payloadRoot
    Require ($null -ne $frontendAsset) 'Installed maximum payload contains no packaged web-ui asset for targeted tamper acceptance'
    $frontendJarOriginal = [IO.File]::ReadAllBytes($frontendAsset.ArchivePath)
    $frontendAssetOriginal = ReadZipEntryBytes $frontendAsset.ArchivePath $frontendAsset.EntryName
    try
    {
      MakeDisposablePayloadFileWritable $frontendAsset.ArchivePath
      $frontendAssetTampered = [byte[]]::new($frontendAssetOriginal.Length + 1)
      [Array]::Copy($frontendAssetOriginal, $frontendAssetTampered, $frontendAssetOriginal.Length)
      $frontendAssetTampered[$frontendAssetOriginal.Length] = [byte]0x5A
      ReplaceZipEntryBytes $frontendAsset.ArchivePath $frontendAsset.EntryName $frontendAssetTampered
      $frontendTamperResult = InvokeBundle @('version') 'maximum launcher after packaged frontend tamper' 1
      Require ($frontendTamperResult.ExitCode -ne 0) 'Maximum stable launcher accepted an edited packaged frontend asset'
      RecordCheck 'frontend-asset-tamper-refusal' 'PASS' "Stable launcher refused an edited packaged frontend asset: $($frontendAsset.EntryName)"
    }
    finally
    {
      [IO.File]::WriteAllBytes($frontendAsset.ArchivePath, $frontendJarOriginal)
      RestoreDisposablePayloadFileMode $frontendAsset.ArchivePath
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
    # Keep the fixture's index and worktree byte-stable when the shipped CLI
    # invokes Git with global/system configuration disabled.
    & git -C $project config core.autocrlf false
    Set-Content -LiteralPath (Join-Path $project 'README.md') -Value 'Synesis maximum acceptance project'
    & git -C $project add README.md
    & git -C $project commit -m 'Initial maximum acceptance baseline' | Out-Null
    Require ($LASTEXITCODE -eq 0) 'Could not commit the disposable maximum acceptance project'
    [void](InvokeBundle @('init', '--project', $project) 'maximum project init')
    [void](InvokeBundle @('provider', 'list', '--project', $project) 'maximum provider list')
    [void](InvokeBundle @('provider', 'install', 'claude', '--project', $project) 'maximum provider install')
    [void](InvokeBundle @('provider', 'status', 'claude', '--project', $project) 'maximum provider status')
    [void](InvokeBundle @('provider', 'uninstall', 'claude', '--project', $project) 'maximum provider uninstall')
    $codexInstall = InvokeBundle @('provider', 'install', 'codex', '--project', $project) 'maximum Codex provider install'
    Require ($codexInstall.Output -match 'PROVIDER_INSTALL_RESULT=(SUCCESS|ALREADY_INSTALLED|DEGRADED)') 'Maximum Codex provider installation did not return a supported result'
    Require ($codexInstall.Output -match 'SYNTHETIC_CHECK=PASSED') 'Maximum Codex provider synthetic check did not pass'
    [void](InvokeBundle @('doctor', '--project', $project) 'maximum doctor')
    RecordCheck 'cli-project-provider-doctor' 'PASS' 'Disposable project initialization, Claude lifecycle, Codex installation, and doctor passed'

    try
    {
      $linkDetail = InvokeLinkAcceptance $project
      RecordCheck 'cli-link-onboarding-peer-session' 'PASS' $linkDetail
    }
    catch
    {
      if (IsLoopbackEnvironmentFailure $_.Exception.Message)
      {
        $script:environmentBlocked = $true
        RecordCheck 'cli-link-onboarding-peer-session' 'BLOCKED_ENVIRONMENT' 'The installed two-process Link probe reached the host loopback compatibility boundary; no PeerSession pass was claimed'
      }
      elseif (IsLinkRuntimeBlock $_.Exception.Message)
      {
        $script:runtimeBlocked = $true
        RecordCheck 'cli-link-onboarding-peer-session' 'BLOCKED_RUNTIME' 'The installed two-process Link probe reached a bounded native/QUIC runtime boundary; no PeerSession pass was claimed'
      }
      else
      {
        throw
      }
    }

    try
    {
      [void](InvokeBundle @('ui', '--project', $project, '--duration-seconds', '1', '--no-browser') 'maximum UI/control-plane smoke')
      RecordCheck 'cli-ui-control-plane' 'PASS' 'Shipped UI/control-plane server completed the no-browser smoke'
    }
    catch
    {
      if (-not (IsLoopbackEnvironmentFailure $_.Exception.Message))
      {
        throw
      }
      $script:environmentBlocked = $true
      RecordCheck 'cli-ui-control-plane' 'BLOCKED_ENVIRONMENT' 'The shipped UI/control-plane smoke reached the runtime but the host JDK could not establish its loopback wakeup connection; rerun on a host with working Java loopback support'
    }

    if ($script:environmentBlocked)
    {
      RecordCheck 'cli-control-plane-http-sse' 'BLOCKED_ENVIRONMENT' 'The live authenticated browser/control-plane HTTP and SSE probe was not attempted because the same host loopback compatibility check was blocked'
    }
    else
    {
      try
      {
        InvokeControlPlaneHttpAcceptance $project
        RecordCheck 'cli-control-plane-http-sse' 'PASS' 'Installed launcher served the packaged UI root and passed health, one-time bootstrap/session, authenticated snapshot/diagnostics/network, CSRF refusal, and initial SSE snapshot checks'
      }
      catch
      {
        if (-not (IsLoopbackEnvironmentFailure $_.Exception.Message))
        {
          throw
        }
        $script:environmentBlocked = $true
        RecordCheck 'cli-control-plane-http-sse' 'BLOCKED_ENVIRONMENT' 'The live authenticated browser/control-plane HTTP and SSE probe reached a host loopback compatibility failure; rerun on a host with working Java loopback support'
      }
    }

    $mcpInfo = [Diagnostics.ProcessStartInfo]::new()
    if ($script:maximumAcceptanceIsWindows)
    {
      $mcpInfo.FileName = 'cmd.exe'
      $mcpInfo.Arguments = '/d /s /c "' + (GetWindowsLauncherCommandLine @(
        'mcp', '--provider', 'codex', '--project', $project,
        '--connection-instance-id', 'maximum-acceptance-1')) + '"'
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
    ApplyRuntimeEnvironment $mcpInfo
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
    Require ($mcpOutput -match 'protocolVersion' -and $mcpOutput -match 'ensure_session') 'Maximum shipped MCP protocol smoke was incomplete'
    Require ($mcpOutput -match '(?i)\\?"status\\?"\s*:\s*\\?"(ready|retry_required|blocked)\\?"') 'Maximum shipped MCP session response was missing a bounded status'
    RecordCheck 'mcp-boundary' 'PASS' 'Shipped MCP initialize, tools/list, and bounded ensure_session exchange completed; native Link/overlay acceptance remains separate'
    if ($mcpOutput -match '(?i)\\?"status\\?"\s*:\s*\\?"ready\\?"')
    {
      RecordCheck 'mcp-session-admission' 'PASS' 'Shipped MCP ensure_session reached ready on the disposable provider workspace'
    }
    else
    {
      $script:runtimeBlocked = $true
      $sessionDetail = if ($mcpOutput -match '(?i)\\?"status\\?"\s*:\s*\\?"retry_required\\?"') { 'retry_required/workspace_not_ready' } else { 'blocked' }
      RecordCheck 'mcp-session-admission' 'BLOCKED_RUNTIME' "Shipped MCP remained fail-closed at $sessionDetail; a verified provider workspace is required for ready evidence"
    }
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

  $result.status = if ($script:environmentBlocked -or $script:runtimeBlocked) { 'PARTIAL_ACCEPTANCE_BLOCKED' } else { 'PASS_WITH_EXPLICIT_OPEN_GATES' }
  $result.extractedBundle = $bundleRoot
  $result.keepExtracted = [bool]$KeepExtracted
  $result.evidenceDirectory = $evidencePath
  $resultPath = Join-Path $evidencePath 'maximum-release-acceptance.json'
  $result | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $resultPath
  if ($script:environmentBlocked -or $script:runtimeBlocked)
  {
    Write-Warning "Maximum-release acceptance completed with blocked acceptance checks; evidence recorded at $resultPath"
    exit 2
  }
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
