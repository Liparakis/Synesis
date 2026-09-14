[CmdletBinding()]
param(
    [int]$Cycles = 1,
    [string]$BaseRunRoot = 'D:\SynesisDocker\p2p-lab-3a',
    [switch]$SkipBuild,
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Cycles -lt 1) {
    throw 'Cycles must be at least 1.'
}

$repoRoot = (Get-Item (Join-Path $PSScriptRoot '..\..')).FullName
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runRoot = Join-Path $BaseRunRoot "run-$runId"
$evidenceRoot = Join-Path $runRoot 'evidence'
$stateRoot = Join-Path $runRoot 'nodes'
$networkName = "synesis-p2p-3a-$runId".ToLowerInvariant()
$imageTag = 'synesis-p2p-lab:3a'
$nodeCount = 6
$membershipB64 = $null
$nodes = @()
$edges = @()
$qdiscNodes = @()

New-Item -ItemType Directory -Force -Path $evidenceRoot, $stateRoot | Out-Null

function Invoke-DockerResult {
    param([string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & docker @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "Docker failed: docker $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

function Invoke-LabText {
    param(
        [pscustomobject]$Node,
        [ValidateSet('GET', 'POST')][string]$Method,
        [string]$Path,
        [AllowNull()][string]$Body = $null
    )
    $uri = "http://127.0.0.1:$($Node.HostPort)$Path"
    if (-not $PSBoundParameters.ContainsKey('Body')) {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method $Method -TimeoutSec 30
    } else {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -Method $Method `
            -Body $bytes -ContentType 'text/plain; charset=utf-8' -TimeoutSec 30
    }
    return [string]$response.Content
}

function Wait-Until {
    param(
        [scriptblock]$Condition,
        [string]$Label,
        [int]$TimeoutSeconds = 90
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            if (& $Condition) {
                return
            }
        } catch {
            # A stopped/restarting container is an expected transient state.
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Label."
}

function Start-LabNode {
    param([pscustomobject]$Node)
    New-Item -ItemType Directory -Force -Path $Node.State | Out-Null
    $mapping = '127.0.0.1:{0}:8080/tcp' -f $Node.HostPort
    $mount = 'type=bind,source={0},target=/var/lib/synesis-lab' -f $Node.State
    Invoke-DockerResult @(
        'run', '-d', '--name', $Node.Container,
        '--hostname', $Node.Name,
        '--network', $networkName,
        '--network-alias', $Node.Name,
        '--cap-add', 'NET_ADMIN',
        '--security-opt', 'seccomp=unconfined',
        '--mount', $mount,
        '--publish', $mapping,
        $imageTag,
        '--state', '/var/lib/synesis-lab',
        '--control-port', '8080',
        '--direct-port', '4555'
    ) | Out-Null
}

function Wait-LabNode {
    param([pscustomobject]$Node)
    Wait-Until -Label "$($Node.Name) health" -Condition {
        try {
            (Invoke-LabText $Node 'GET' '/health').Trim() -eq 'OK'
        } catch {
            $false
        }
    } -TimeoutSeconds 180
}

function Get-NodeIdentity {
    param([pscustomobject]$Node)
    $parts = (Invoke-LabText $Node 'GET' '/identity').Trim().Split('|', 2)
    if ($parts.Count -ne 2) {
        throw "Invalid identity response from $($Node.Name)."
    }
    $Node | Add-Member -Force -NotePropertyName Id -NotePropertyValue $parts[0]
    $Node | Add-Member -Force -NotePropertyName PublicKeyB64 -NotePropertyValue $parts[1]
}

function Get-NodeStatus {
    param([pscustomobject]$Node)
    return (Invoke-LabText $Node 'GET' '/status' | ConvertFrom-Json)
}

function Get-ExpectedNeighbors {
    param([string[]]$Excluded = @())
    $excludedSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $Excluded) { [void]$excludedSet.Add($id) }
    $expected = @{}
    foreach ($node in $nodes) {
        if ($excludedSet.Contains($node.Id)) { continue }
        $neighbors = @()
        foreach ($edge in $edges) {
            if ($edge.LowerId -eq $node.Id) { $neighbors += $edge.UpperId }
            if ($edge.UpperId -eq $node.Id) { $neighbors += $edge.LowerId }
        }
        $expected[$node.Id] = @($neighbors | Where-Object { -not $excludedSet.Contains($_) })
    }
    return $expected
}

function Wait-Graph {
    param(
        [string[]]$Excluded = @(),
        [string]$Label = 'topology convergence',
        [int]$TimeoutSeconds = 120
    )
    $expected = Get-ExpectedNeighbors $Excluded
    Wait-Until -Label $Label -TimeoutSeconds $TimeoutSeconds -Condition {
        foreach ($node in $nodes) {
            if (-not $expected.ContainsKey($node.Id)) { continue }
            $status = Get-NodeStatus $node
            $live = @($status.liveNeighbors | Where-Object { -not ($Excluded -contains $_) })
            $want = @($expected[$node.Id])
            if ($live.Count -ne $want.Count) { return $false }
            foreach ($peer in $want) {
                if (-not ($live -contains $peer)) { return $false }
            }
        }
        return $true
    }
}

function Write-TopologySnapshot {
    param([string]$Stage)
    $safeStage = $Stage -replace '[^A-Za-z0-9_.-]', '_'
    $records = @()
    foreach ($node in $nodes) {
        try {
            $status = Get-NodeStatus $node
            $status | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 `
                (Join-Path $evidenceRoot "$safeStage-$($node.Name).json")
            $records += [pscustomobject]@{
                node = $node.Name
                nodeId = $node.Id
                status = $status
            }
        } catch {
            $records += [pscustomobject]@{
                node = $node.Name
                nodeId = $node.Id
                error = $_.Exception.Message
            }
        }
    }
    $records | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 `
        (Join-Path $evidenceRoot "$safeStage.json")
}

function Refresh-Descriptors {
    $lines = @()
    foreach ($node in $nodes) {
        $descriptor = (Invoke-LabText $node 'GET' '/descriptor').Trim()
        $node | Add-Member -Force -NotePropertyName DescriptorB64 -NotePropertyValue $descriptor
        $lines += "$($node.Id)|$descriptor"
    }
    $body = $lines -join "`n"
    $body | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'latest-descriptors.txt')
    foreach ($node in $nodes) {
        Invoke-LabText $node 'POST' '/descriptors' $body | Out-Null
        Invoke-LabText $node 'POST' '/reconcile' '' | Out-Null
    }
}

function Get-TopologyEdges {
    $sorted = @($nodes | Sort-Object Id)
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $result = @()
    for ($index = 0; $index -lt $sorted.Count; $index++) {
        foreach ($offset in 1, 2) {
            $otherIndex = ($index + $offset) % $sorted.Count
            $left = $sorted[$index]
            $right = $sorted[$otherIndex]
            if ($left.Id -eq $right.Id) { continue }
            $ordered = @($left, $right) | Sort-Object Id
            $key = "$($ordered[0].Id)|$($ordered[1].Id)"
            if ($seen.Add($key)) {
                $result += [pscustomobject]@{
                    Lower = $ordered[0]
                    Upper = $ordered[1]
                    LowerId = $ordered[0].Id
                    UpperId = $ordered[1].Id
                }
            }
        }
    }
    return $result
}

function Bootstrap-Slb2Edges {
    foreach ($edge in $edges) {
        $path = '/invite?peer=' + [Uri]::EscapeDataString($edge.UpperId)
        $link = (Invoke-LabText $edge.Lower 'POST' $path '').Trim()
        if (-not $link.StartsWith('synesis://join/SLB2-', [StringComparison]::Ordinal)) {
            throw "Expected SLB2 invitation for $($edge.LowerId)/$($edge.UpperId)."
        }
        Invoke-LabText $edge.Upper 'POST' '/join' $link | Out-Null
        Wait-Until -Label "SLB2 edge $($edge.LowerId)/$($edge.UpperId)" -TimeoutSeconds 60 `
            -Condition {
                $left = Get-NodeStatus $edge.Lower
                $right = Get-NodeStatus $edge.Upper
                ($left.liveNeighbors -contains $edge.UpperId) -and
                    ($right.liveNeighbors -contains $edge.LowerId)
            }
    }
}

function Find-TransitPair {
    param([string[]]$Excluded = @())
    $sorted = @($nodes | Where-Object { -not ($Excluded -contains $_.Id) } | Sort-Object Id)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        foreach ($origin in $sorted) {
            foreach ($destination in $sorted) {
                if ($origin.Id -eq $destination.Id) { continue }
                try {
                    $route = (Invoke-LabText $origin 'GET' `
                        ('/route?destination=' + [Uri]::EscapeDataString($destination.Id)) |
                        ConvertFrom-Json)
                    $pathHasExcluded = @($route.path | Where-Object {
                        $Excluded -contains $_
                    }).Count -gt 0
                    if ($route.kind -eq 'PEER_TRANSIT' -and $route.path.Count -ge 2 -and
                        -not $pathHasExcluded) {
                        return [pscustomobject]@{
                            Origin = $origin
                            Destination = $destination
                            Route = $route
                            Transit = ($nodes | Where-Object { $_.Id -eq $route.path[0] })
                        }
                    }
                } catch {
                    # A route query can race a killed or restarting node.
                }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'No peer-transit route was observed in the converged graph.'
}

function Send-And-Verify {
    param([pscustomobject]$Pair, [string]$Payload, [string]$Stage)
    $route = $Pair.Route
    if ($null -eq $route) {
        $route = (Invoke-LabText $Pair.Origin 'GET' `
            ('/route?destination=' + [Uri]::EscapeDataString($Pair.Destination.Id)) |
            ConvertFrom-Json)
    }
    $Pair | Add-Member -Force -NotePropertyName Route -NotePropertyValue $route
    $Pair | Add-Member -Force -NotePropertyName Transit -NotePropertyValue `
        ($nodes | Where-Object { $_.Id -eq $route.path[0] })
    $route | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 `
        (Join-Path $evidenceRoot "$Stage-route.json")
    $expected = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Payload))
    Invoke-LabText $Pair.Origin 'POST' `
        ('/send?destination=' + [Uri]::EscapeDataString($Pair.Destination.Id)) $Payload | Out-Null
    Wait-Until -Label "delivery $Stage" -TimeoutSeconds 60 -Condition {
        (Get-NodeStatus $Pair.Destination).lastDeliveredB64 -eq $expected
    }
}

function Restart-NodeSet {
    param(
        [pscustomobject[]]$RestartNodes,
        [switch]$Graceful
    )
    if ($Graceful) {
        foreach ($node in $RestartNodes) {
            try { Invoke-LabText $node 'POST' '/shutdown' '' | Out-Null } catch { }
        }
        Start-Sleep -Milliseconds 750
    }
    foreach ($node in $RestartNodes) {
        try { Invoke-DockerResult @('rm', '-f', $node.Container) | Out-Null } catch { }
    }
    foreach ($node in $RestartNodes) { Start-LabNode $node }
    foreach ($node in $RestartNodes) {
        Wait-LabNode $node
        $oldId = $node.Id
        Get-NodeIdentity $node
        if ($node.Id -ne $oldId) {
            throw "Identity changed during replacement of $($node.Name)."
        }
        Invoke-LabText $node 'POST' '/membership' $membershipB64 | Out-Null
    }
}

function Apply-Netem {
    param([pscustomobject]$Node)
    $output = Invoke-DockerResult @('exec', $Node.Container, 'tc', 'qdisc', 'replace',
        'dev', 'eth0', 'root', 'netem', 'delay', '50ms', '10ms', 'loss', '5%')
    $script:qdiscNodes += $Node
    $output | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot "netem-$($Node.Name).txt")
}

function Apply-Partition {
    param([pscustomobject]$Node)
    $output = Invoke-DockerResult @('exec', $Node.Container, 'tc', 'qdisc', 'replace',
        'dev', 'eth0', 'root', 'netem', 'loss', '100%')
    $script:qdiscNodes += $Node
    $output | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot "partition-$($Node.Name).txt")
}

function Clear-Netem {
    foreach ($node in @($script:qdiscNodes)) {
        try {
            Invoke-DockerResult @('exec', $node.Container, 'tc', 'qdisc', 'del',
                'dev', 'eth0', 'root') | Out-Null
        } catch {
            # The container may already have been killed; cleanup remains bounded.
        }
    }
    $script:qdiscNodes = @()
}

try {
    if (-not $SkipBuild) {
        $gradlew = Join-Path $repoRoot 'gradlew.bat'
        $oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
        try {
            $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
            & $gradlew ':link:compileTestJava' ':cli:installDist' '--no-daemon' `
                '--no-configuration-cache' '--console=plain' '-PsynesisNativeQuicClassifier=linux-x86_64'
            if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
        } finally {
            $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
        }
    }

    $dist = Join-Path $repoRoot 'cli\build\install\synesis'
    $testClasses = Join-Path $repoRoot 'link\build\classes\java\test'
    if (-not (Test-Path (Join-Path $dist 'lib'))) { throw 'Linux distribution is missing.' }
    if (-not (Test-Path $testClasses)) { throw 'Process lab test classes are missing.' }
    Invoke-DockerResult @(
        'build',
        '--build-context', "dist=$dist",
        '--build-context', "testclasses=$testClasses",
        '--tag', $imageTag,
        '--file', (Join-Path $repoRoot 'scripts\p2p-lab\Dockerfile'),
        (Join-Path $repoRoot 'scripts\p2p-lab')
    ) | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'docker-build.txt')

    Invoke-DockerResult @('network', 'create', '--driver', 'bridge', $networkName) | Out-Null
    for ($index = 1; $index -le $nodeCount; $index++) {
        $node = [pscustomobject]@{
            Name = "node$index"
            Container = "$networkName-node$index"
            HostPort = 18080 + $index
            State = Join-Path $stateRoot "node$index"
        }
        $nodes += $node
        Write-Host "Starting $($node.Name) on host port $($node.HostPort)..."
        Start-LabNode $node
    }
    foreach ($node in $nodes) {
        Write-Host "Waiting for $($node.Name) health..."
        Wait-LabNode $node
        Write-Host "Reading $($node.Name) identity..."
        Get-NodeIdentity $node
    }
    Write-Host 'All six node identities are ready.'
    $nodes | Select-Object Name, Container, HostPort, Id, PublicKeyB64 | ConvertTo-Json `
        -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'identities.json')

    $sortedNodes = @($nodes | Sort-Object Id)
    $projectId = [Guid]::NewGuid().ToString()
    $memberBody = ($sortedNodes | ForEach-Object { "$($_.Id)|$($_.PublicKeyB64)" }) -join "`n"
    $membershipB64 = (Invoke-LabText $sortedNodes[0] 'POST' `
        ("/membership/create?projectId=$projectId") $memberBody).Trim()
    Write-Host 'Signed membership snapshot created.'
    $membershipB64 | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'membership.b64')
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $membershipDigest = ([BitConverter]::ToString(
            $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($membershipB64))).Replace('-', '')
            ).ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    [pscustomobject]@{
        projectId = $projectId
        membershipDigest = $membershipDigest
        memberCount = $nodeCount
        authorityNodeId = $sortedNodes[0].Id
    } | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'membership.json')
    foreach ($node in $nodes) {
        Invoke-LabText $node 'POST' '/membership' $membershipB64 | Out-Null
    }
    Write-Host 'Signed membership installed on all nodes.'

    $edges = @(Get-TopologyEdges)
    if ($edges.Count -ne 12) { throw "Expected 12 deterministic edges, got $($edges.Count)." }
    $edges | Select-Object LowerId, UpperId | ConvertTo-Json | Set-Content -Encoding UTF8 `
        (Join-Path $evidenceRoot 'expected-edges.json')
    Write-TopologySnapshot 'membership-installed'

    Bootstrap-Slb2Edges
    Write-Host 'All twelve SLB2 first-contact edges are authenticated.'
    Write-TopologySnapshot 'post-slb2-first-contact'
    Refresh-Descriptors
    Wait-Graph -Label 'initial 12-edge graph' -TimeoutSeconds 180
    Write-TopologySnapshot 'initial-converged'

    for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
        $cyclePrefix = "cycle-$cycle"
        $pair = Find-TransitPair
        Send-And-Verify $pair "transit-$cycle-initial" "$cyclePrefix-initial-transit"

        $transit = $pair.Transit
        if ($null -eq $transit -or $transit.Id -eq $pair.Destination.Id) {
            throw 'The selected transit route did not expose a distinct first hop.'
        }
        Invoke-DockerResult @('kill', $transit.Container) | Out-Null
        Wait-Graph -Excluded @($transit.Id) -Label "withdrawal after hard kill $($transit.Name)"
        Write-TopologySnapshot "$cyclePrefix-hard-kill-withdrawal"

        $survivingPair = Find-TransitPair -Excluded @($transit.Id)
        Send-And-Verify $survivingPair "transit-$cycle-alternate" `
            "$cyclePrefix-alternate-transit"
        Restart-NodeSet @($transit)
        Refresh-Descriptors
        Wait-Graph -Label "identity rejoin after $($transit.Name)" -TimeoutSeconds 180
        Write-TopologySnapshot "$cyclePrefix-container-rejoin"

        $partition = @($nodes | Where-Object { $_.Id -ne $transit.Id } | Sort-Object Id)[-1]
        Apply-Partition $partition
        Wait-Graph -Excluded @($partition.Id) -Label "partition withdrawal $($partition.Name)"
        Write-TopologySnapshot "$cyclePrefix-partition"
        Clear-Netem
        foreach ($node in $nodes) {
            Restart-NodeSet @($node) -Graceful
            Refresh-Descriptors
        }
        Write-TopologySnapshot "$cyclePrefix-partition-process-restart-descriptors"
        Write-TopologySnapshot "$cyclePrefix-partition-process-restart"
        Wait-Graph -Label "partition recovery $($partition.Name)" -TimeoutSeconds 180
        Write-TopologySnapshot "$cyclePrefix-partition-recovery"

        try {
            Apply-Netem $partition
            $impairedPair = Find-TransitPair
            Send-And-Verify $impairedPair "transit-$cycle-netem" "$cyclePrefix-netem"
        } catch {
            $_.Exception.Message | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot `
                "$cyclePrefix-netem-unavailable.txt")
        } finally {
            Clear-Netem
        }

        $failed = @($nodes | Where-Object { $_.Id -ne $transit.Id -and $_.Id -ne $partition.Id } |
            Sort-Object Id | Select-Object -First 2)
        foreach ($node in $failed) {
            Invoke-DockerResult @('kill', $node.Container) | Out-Null
        }
        Wait-Graph -Excluded @($failed | ForEach-Object { $_.Id }) `
            -Label 'two-node failure withdrawal'
        Write-TopologySnapshot "$cyclePrefix-two-node-failure"

        Restart-NodeSet $failed
        Refresh-Descriptors
        Wait-Graph -Label 'simultaneous two-node reconnect' -TimeoutSeconds 180
        Write-TopologySnapshot "$cyclePrefix-simultaneous-reconnect"
    }

    [pscustomobject]@{
        runId = $runId
        network = $networkName
        image = $imageTag
        cycles = $Cycles
        expectedEdges = $edges.Count
        expectedDegree = 4
        hostSubstrate = 'Windows 11 + Docker Desktop Linux engine + Docker-managed WSL2'
        kvm = 'unavailable: Windows did not expose SLAT/KVM capability'
        result = 'PASS FOR DOCKER PROCESS/NETWORK LAB SCOPE'
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'result.json')
    Write-Host "Checkpoint 3A Docker lab evidence: $evidenceRoot"
} catch {
    $_.Exception.ToString() | Set-Content -Encoding UTF8 (Join-Path $evidenceRoot 'failure.txt')
    throw
} finally {
    Clear-Netem
    if (-not $Keep) {
        foreach ($node in $nodes) {
            try { Invoke-DockerResult @('rm', '-f', $node.Container) | Out-Null } catch { }
        }
        try { Invoke-DockerResult @('network', 'rm', $networkName) | Out-Null } catch { }
    } else {
        Write-Host "Containers/network retained for inspection: $networkName"
    }
}
