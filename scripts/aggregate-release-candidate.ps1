param(
    [Parameter(Mandatory = $true)] [string] $InputDirectory,
    [string] $OutputDirectory = "build/release-candidate",
    [Parameter(Mandatory = $true)] [string] $Version,
    [switch] $RequireSignature,
    [string] $PublicKeyHex
)

$repoRoot = (Get-Location).Path
$inputPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $InputDirectory))
$outputPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
$arguments = @(
    "run", "./cmd/aggregate-release-candidate",
    "-input", $inputPath,
    "-output", $outputPath,
    "-version", $Version,
    "-repo-root", $repoRoot
)
if ($RequireSignature)
{
    $arguments += "-require-signature=true"
}
if ($PublicKeyHex)
{
    $arguments += @("-public-key-hex", $PublicKeyHex)
}
Push-Location (Join-Path $repoRoot "bootstrap")
try
{
    go @arguments
    $exitCode = $LASTEXITCODE
}
finally
{
    Pop-Location
}
if ($exitCode -ne 0)
{
    exit $exitCode
}
