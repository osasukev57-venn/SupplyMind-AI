# D9-T02/D9-T05 assemble the portable SupplyMindAI directory (frozen layout):
#   SupplyMindAI/
#     app/supplymind-backend.jar   Spring Boot JAR (frozen Day8 build)
#     app/web/                     Vue production assets (frontend/dist)
#     runtime/jre/                 bundled Java 17 JRE (build-jre.ps1)
#     data/                        visible business data (created, writable)
#     logs/
#     licenses/                    Temurin/JRE + dependency license notices
# Usage: .\scripts\package-portable.ps1 [-Root <portableRoot>] [-SkipJreBuild]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI'),
    [switch]$SkipJreBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Join-Path $PSScriptRoot '..\..'

# 1. build the bundled JRE unless the caller already built it
if (-not $SkipJreBuild) {
    & (Join-Path $PSScriptRoot 'build-jre.ps1') -OutDir (Join-Path $Root 'runtime\jre')
    if ($LASTEXITCODE -ne 0) { throw 'build-jre.ps1 failed' }
}
$jreJava = Join-Path $Root 'runtime\jre\bin\java.exe'
if (-not (Test-Path -LiteralPath $jreJava)) {
    throw "bundled JRE missing: $jreJava (run build-jre.ps1 first or use -SkipJreBuild)"
}

# 2. backend JAR must exist (frozen Day8 build, never rebuilt from Day9 sources)
$jar = Join-Path $repoRoot 'backend\target\supplymind-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw "backend JAR missing: $jar (run 'mvnw.cmd package -DskipTests' in backend/ first)"
}

# 3. frontend production build must exist
$dist = Join-Path $repoRoot 'frontend\dist'
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) {
    throw "frontend production build missing: $dist (run 'npm run build' in frontend/ first)"
}

# 4. assemble the layout
$appDir = Join-Path $Root 'app'
$webDir = Join-Path $appDir 'web'
$dataDir = Join-Path $Root 'data'
$logsDir = Join-Path $Root 'logs'
$licDir = Join-Path $Root 'licenses'
foreach ($dir in @($Root, $appDir, $webDir, $dataDir, $logsDir, $licDir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

Copy-Item -LiteralPath $jar -Destination (Join-Path $appDir 'supplymind-backend.jar') -Force
Get-ChildItem -LiteralPath $dist -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $webDir -Recurse -Force
}

# licenses: JRE license + supplymind dependency notice (frozen: no extra runtime deps)
$licenses = @(
    'https://adoptium.net/temurin/legal/',
    'Temurin (Eclipse Adoptium) JRE - GPLv2 with Classpath Exception'
)
Set-Content -LiteralPath (Join-Path $licDir 'THIRD-PARTY-NOTICES.txt') -Encoding UTF8 -Value @(
    'SupplyMind AI - Third-party notices',
    '=============================',
    '',
    'Bundled runtime:',
    '  Eclipse Temurin Java 17 runtime (GPLv2 with the Classpath Exception).',
    "  License: $($licenses[0])",
    "  Distributor: $($licenses[1])",
    '',
    'Backend (supplymind-backend.jar):',
    '  Spring Boot 3.5.15 / Spring AI 1.1.8 / Apache POI / Apache Commons CSV',
    '  (Apache License 2.0 unless noted; full POM dependency list in',
    '  docs/evidence/Day8/artifacts/maven-dependency-tree.txt)',
    '',
    'Frontend (app/web):',
    '  Vue 3 / Vue Router / Axios / Vite (MIT)',
    ''
)

Write-Host '[package-portable] layout assembled:'
Get-ChildItem -LiteralPath $Root -Recurse -Depth 2 -Directory | ForEach-Object {
    Write-Host "  $($_.FullName.Replace($Root, $Root))"
}
Write-Host "[package-portable] root: $Root"
