# Builds a GitHub-ready source archive from the exact committed HEAD.
# Generated release files are intentionally ignored by Git.
param(
    [string]$Version = '0.9.0',
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..'),
    [string]$OutDir = (Join-Path $PSScriptRoot '..\release')
)

$ErrorActionPreference = 'Stop'
$RepoRoot = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
$OutDir = [IO.Path]::GetFullPath($OutDir).TrimEnd('\')
if (-not $OutDir.StartsWith($RepoRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "source output must remain inside the repository: $OutDir"
}
if ((git -C $RepoRoot status --porcelain=v1 --untracked-files=all).Count -ne 0) {
    throw 'source archive requires a clean working tree'
}
foreach ($required in @('README.md', 'LICENSE', 'docs/07-WINDOWS-DEPLOYMENT-MANUAL.md', 'backend/pom.xml', 'frontend/package.json', 'desktop/package.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $required))) {
        throw "required source-delivery file missing: $required"
    }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zipName = "SupplyMindAI-$Version-source.zip"
$zipPath = Join-Path $OutDir $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
$prefix = "SupplyMindAI-$Version-source/"
& git -C $RepoRoot archive --format=zip "--prefix=$prefix" "--output=$zipPath" HEAD
if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
Set-Content -LiteralPath "$zipPath.sha256" -Value $hash -Encoding ASCII
$manifest = [ordered]@{
    schemaVersion = '1.0'
    version = $Version
    commit = (git -C $RepoRoot rev-parse HEAD)
    archive = $zipName
    sha256 = $hash
    sizeBytes = (Get-Item -LiteralPath $zipPath).Length
    requiredPaths = @('README.md', 'LICENSE', 'docs/07-WINDOWS-DEPLOYMENT-MANUAL.md')
    excludes = @('.git', 'node_modules', 'target', 'dist', 'release', 'portable', '.env', 'runtime data', 'logs')
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$zipPath.manifest.json" -Encoding UTF8
Write-Host "SOURCE_ZIP=$zipPath"
Write-Host "SOURCE_SHA256=$hash"