$ErrorActionPreference = 'Stop'
$arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
$platform = switch ($arch) { 'x64' { 'x64' } 'arm64' { 'arm64' } default { throw "Unsupported Windows architecture: $arch" } }
$base = $env:SYNESIS_BOOTSTRAP_BASE_URL
if ([string]::IsNullOrWhiteSpace($base)) { throw 'Set SYNESIS_BOOTSTRAP_BASE_URL before running the installer.' }
$version = if ($env:SYNESIS_BOOTSTRAP_VERSION) { $env:SYNESIS_BOOTSTRAP_VERSION } else { '0.1.0-dev.local' }
$name = "synesis-bootstrap-$version-windows-$platform.exe"
$file = Join-Path ([IO.Path]::GetTempPath()) $name
try {
    Invoke-WebRequest "$base/$name" -OutFile $file
    $line = (Invoke-WebRequest "$base/checksums.txt").Content -split '\r?\n' | Where-Object { $_ -match "\s$([regex]::Escape($name))$" } | Select-Object -First 1
    if (-not $line) { throw "No published checksum for $name" }
    $expected = ($line -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Bootstrap checksum mismatch for $name" }
    & $file install --manifest "$base/manifest.json"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
}
