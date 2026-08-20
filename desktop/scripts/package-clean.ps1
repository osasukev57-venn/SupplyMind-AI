# D9 Final Attack F1: clean deterministic portable artifact pipeline.
#
# Design:
#   - Each run builds into a FRESH unique staging root under <repo>/target/package-staging/
#     (GUID suffix). Nothing is ever wiped from a previous run; no reused portable root.
#   - Staging root passes a resolved-absolute-path guard: it MUST sit under
#     <repo>/target (project build output) - never arbitrary/unsafe paths.
#   - backend JAR + frontend dist are rebuilt THIS run (no stale artifacts).
#   - Bundled JRE is rebuilt THIS run via build-jre.ps1.
#   - ZIP is produced by the deterministic writer (fixed timestamps, sorted entries).
#   - data/ contains only canonical config v1 + manifests; logs/ starts empty.
# Usage:
#   .\scripts\package-clean.ps1 -Version 0.9.0

param(
    [string]$Version = '0.9.0',
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [switch]$SkipBackendBuild,
    [switch]$SkipFrontendBuild,
    [switch]$SkipJreBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. (Join-Path $PSScriptRoot 'lib-zip.ps1')

# ---------------------------------------------------------------- repo + staging guards
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
$stagingBase = Join-Path $RepoRoot 'target\package-staging'
New-Item -ItemType Directory -Force -Path $stagingBase | Out-Null
$resolvedStagingBase = [System.IO.Path]::GetFullPath($stagingBase)
if (-not $resolvedStagingBase.StartsWith($RepoRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "staging base escaped repo root: $resolvedStagingBase"
}
if ($resolvedStagingBase -ne (Join-Path $RepoRoot 'target').TrimEnd('\') -and
    -not $resolvedStagingBase.StartsWith((Join-Path $RepoRoot 'target') + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "staging base must live under <repo>/target: $resolvedStagingBase"
}

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path $stagingBase "$runId-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$root = Join-Path $stage 'SupplyMindAI'
$resolvedStage = [System.IO.Path]::GetFullPath($stage)
if (-not $resolvedStage.StartsWith($resolvedStagingBase + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "staging path escaped staging base: $resolvedStage"
}
New-Item -ItemType Directory -Force -Path $root | Out-Null
Write-Host "[package-clean] staging root: $root"

# ---------------------------------------------------------------- backend JAR (this run)
$jarDir = Join-Path $RepoRoot 'backend\target'
if (-not $SkipBackendBuild) {
    Write-Host '[package-clean] building backend JAR (mvn clean package)'
    Push-Location (Join-Path $RepoRoot 'backend')
    try {
        & .\mvnw.cmd clean package -DskipTests | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'backend maven build failed' }
    } finally {
        Pop-Location
    }
}
$jar = Join-Path $jarDir 'supplymind-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw "backend JAR missing after build: $jar"
}
$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash

# ---------------------------------------------------------------- frontend dist (this run)
$dist = Join-Path $RepoRoot 'frontend\dist'
if (-not $SkipFrontendBuild) {
    Write-Host '[package-clean] building frontend dist (npm run build)'
    Push-Location (Join-Path $RepoRoot 'frontend')
    try {
        & .\node_modules\.bin\vue-tsc.cmd --noEmit; if ($LASTEXITCODE -ne 0) { throw 'vue-tsc failed' }
        & .\node_modules\.bin\vite.cmd build | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'vite build failed' }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) {
    throw "frontend dist missing after build: $dist"
}
$distHash = (Get-FileHash -LiteralPath (Join-Path $dist 'index.html') -Algorithm SHA256).Hash

# ---------------------------------------------------------------- bundled JRE (this run)
$jreDir = Join-Path $root 'runtime\jre'
if (-not $SkipJreBuild) {
    Write-Host '[package-clean] building bundled JRE (jlink)'
    & (Join-Path $PSScriptRoot 'build-jre.ps1') -OutDir $jreDir
    if ($LASTEXITCODE -ne 0) { throw 'build-jre.ps1 failed' }
}
$javaExe = Join-Path $jreDir 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "bundled JRE missing: $javaExe (run build-jre.ps1 first or use -SkipJreBuild)"
}

# ---------------------------------------------------------------- assemble layout
$appDir = Join-Path $root 'app'
$webDir = Join-Path $appDir 'web'
$dataDir = Join-Path $root 'data'
$logsDir = Join-Path $root 'logs'
$licDir = Join-Path $root 'licenses'
foreach ($dir in @($appDir, $webDir, $dataDir, $logsDir, $licDir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

Copy-Item -LiteralPath $jar -Destination (Join-Path $appDir 'supplymind-backend.jar') -Force
Get-ChildItem -LiteralPath $dist -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $webDir -Recurse -Force
}

# Generate the canonical initial config through the production storage code with a fixed
# packaging timestamp. The resulting four files are deterministic and are the only data/
# entries permitted in the release ZIP.
$seedInstant = '2026-08-20T00:00:00+08:00'
$seedMain = 'com.supplymind.desktop.PortableInitialConfigExporter'
& $javaExe "-Dloader.main=$seedMain" -cp (Join-Path $appDir 'supplymind-backend.jar') org.springframework.boot.loader.launch.PropertiesLauncher $dataDir $seedInstant
if ($LASTEXITCODE -ne 0) { throw 'canonical initial config export failed' }

# ---------------------------------------------------------------- licenses
Set-Content -LiteralPath (Join-Path $licDir 'THIRD-PARTY-NOTICES.txt') -Encoding UTF8 -Value @(
    'SupplyMind AI - Third-party notices',
    '=============================',
    '',
    'Bundled runtime (runtime/jre):',
    '  Eclipse Temurin Java 17 runtime (GPLv2 with the Classpath Exception).',
    '  License: https://adoptium.net/temurin/legal/',
    '',
    'Desktop shell (SupplyMindAI.exe):',
    '  Electron 33 (MIT) - https://github.com/electron/electron/blob/main/LICENSE',
    '',
    'Backend (app/supplymind-backend.jar):',
    '  Spring Boot 3.5.15 / Spring AI 1.1.8 (Apache License 2.0)',
    '  Apache POI 5.2.5 / Apache Commons CSV 1.11.0 (Apache License 2.0)',
    '',
    'Frontend (app/web):',
    '  Vue 3 / Vue Router / Axios / Vite / Vitest / TypeScript (MIT)',
    ''
)

# ---------------------------------------------------------------- Electron EXE
$electronExe = Join-Path $PSScriptRoot '..\node_modules\electron\dist\electron.exe'
if (-not (Test-Path -LiteralPath $electronExe)) {
    throw "Electron binary missing: $electronExe (run npm install in desktop/ first)"
}
$exePath = Join-Path $root 'SupplyMindAI.exe'
Copy-Item -LiteralPath $electronExe -Destination $exePath -Force
$exeHash = (Get-FileHash -LiteralPath $exePath -Algorithm SHA256).Hash

$resourcesApp = Join-Path $root 'resources\app'
New-Item -ItemType Directory -Force -Path $resourcesApp | Out-Null
$electronSrc = Join-Path $PSScriptRoot '..\src'
foreach ($file in @('main.js', 'preload.js', 'paths.js', 'port.js', 'health.js', 'backend.js', 'instance.js', 'lifecycle.js')) {
    Copy-Item -LiteralPath (Join-Path $electronSrc $file) -Destination $resourcesApp -Force
}
@'
{
  "name": "supplymind-shell",
  "version": "0.9.0",
  "main": "main.js",
  "private": true
}
'@ | Set-Content -LiteralPath (Join-Path $resourcesApp 'package.json') -Encoding UTF8

$electronDist = Join-Path $PSScriptRoot '..\node_modules\electron\dist'
Get-ChildItem -LiteralPath $electronDist -File | ForEach-Object {
    if ($_.Name -notin @('electron.exe', 'LICENSE')) {
        Copy-Item -LiteralPath $_.FullName -Destination $root -Force
    }
}
Get-ChildItem -LiteralPath $electronDist -Directory | ForEach-Object {
    if ($_.Name -ne 'resources') {
        Copy-Item -LiteralPath $_.FullName -Destination $root -Recurse -Force
    }
}

# ---------------------------------------------------------------- README
& (Join-Path $PSScriptRoot 'write-readme.ps1') -Root $root -Version $Version

# ---------------------------------------------------------------- ZIP + manifest
$outDir = Join-Path $RepoRoot 'release'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$zipName = "SupplyMindAI-$Version-win32-x64.zip"
$zipPath = Join-Path $outDir $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

New-DeterministicZip -SourceDir $root -ZipPath $zipPath -RootEntryName 'SupplyMindAI'
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
Set-Content -LiteralPath "$zipPath.sha256" -Value $zipHash -Encoding ASCII

$javaHash = (Get-FileHash -LiteralPath $javaExe -Algorithm SHA256).Hash
$webIndex = Get-ChildItem -LiteralPath $webDir -Recurse -Filter 'index.html' -File | Select-Object -First 1
$webIndexHash = (Get-FileHash -LiteralPath $webIndex.FullName -Algorithm SHA256).Hash
$assets = Get-ChildItem -LiteralPath (Join-Path $webDir 'assets') -Recurse -File -ErrorAction SilentlyContinue
$assetHashes = $assets | ForEach-Object {
    [pscustomobject]@{ Name = $_.FullName.Substring($webDir.Length).TrimStart('\'); Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
}

$manifest = [ordered]@{
    schemaVersion = '1.0'
    candidateCommit = (git -C $RepoRoot rev-parse --short HEAD 2>$null)
    builtAt = $seedInstant
    windowsVersion = [System.Environment]::OSVersion.VersionString
    version = $Version
    inputs = [ordered]@{
        backendJar = 'backend/target/supplymind-backend-0.1.0-SNAPSHOT.jar'
        backendJarSha256 = $jarHash
        frontendDistIndexSha256 = $distHash
        electronExe = 'desktop/node_modules/electron/dist/electron.exe'
        bundledJavaExe = 'runtime/jre/bin/java.exe'
        bundledJavaExeSha256 = $javaHash
    }
    artifacts = [ordered]@{
        zip = $zipName
        zipSha256 = $zipHash
        zipSizeBytes = (Get-Item $zipPath).Length
        exe = 'SupplyMindAI/SupplyMindAI.exe'
        exeSha256 = $exeHash
        backendJarSha256 = $jarHash
        bundledJava = [ordered]@{ path = 'runtime/jre/bin/java.exe'; sha256 = $javaHash }
        webIndex = [ordered]@{ path = $webIndex.FullName.Substring($webDir.Length).TrimStart('\'); sha256 = $webIndexHash }
        assets = $assetHashes
    }
    free = [ordered]@{
        featureFreeze = 'EFFECTIVE'
        dataRoot = 'INITIAL_CONFIG_V1_ONLY'
        logs = 'EMPTY_AT_PACKAGE'
    }
}
$manifestPath = Join-Path $outDir "$zipName.manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash

Write-Host '[package-clean] clean staging + deterministic zip complete.'
Write-Host "[package-clean] RUN_ID=$runId STAGE=$stage"
Write-Host "[package-clean] ZIP=$zipPath"
Write-Host "[package-clean] ZIP_SHA256=$zipHash"
Write-Host "[package-clean] MANIFEST=$manifestPath (sha256=$manifestHash)"