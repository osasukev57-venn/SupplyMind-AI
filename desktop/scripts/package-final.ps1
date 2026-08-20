# D9-T05 build the final portable package (frozen layout, DEC-005):
#   SupplyMindAI/
#     SupplyMindAI.exe          real Electron executable (electron.exe renamed)
#     resources/app/            Electron shell JS (main + preload + modules)
#     runtime/jre/              bundled Java 17 JRE (root runtime holds ONLY the JRE)
#     app/supplymind-backend.jar
#     app/web/                  Vue production build
#     data/                     visible business data
#     logs/
#     licenses/
#     README.txt
# Plus SupplyMindAI-<version>-win32-x64.zip + SHA-256 next to the root.
# Usage: .\scripts\package-final.ps1 [-Version <v>] [-OutDir <dir>] [-SkipJreBuild]
param(
    [string]$Version = '0.9.0',
    [string]$OutDir = (Join-Path $PSScriptRoot '..\..\release'),
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI'),
    [switch]$SkipJreBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Join-Path $PSScriptRoot '..\..'
$electronSrc = Join-Path $PSScriptRoot '..\src'

# 1. assemble JRE + backend + web + licenses + README (idempotent)
& (Join-Path $PSScriptRoot 'package-portable.ps1') -Root $Root -SkipJreBuild:([bool]$SkipJreBuild)
if ($LASTEXITCODE -ne 0) { throw 'package-portable.ps1 failed' }

# 2. build the real Electron EXE: rename electron.exe to SupplyMindAI.exe and drop the
#    shell into resources/app so double-click runs the desktop app (no installer).
$electronExe = Join-Path $PSScriptRoot '..\node_modules\electron\dist\electron.exe'
if (-not (Test-Path -LiteralPath $electronExe)) {
    throw "Electron binary missing: $electronExe (run npm install in desktop/ first)"
}

$exePath = Join-Path $Root 'SupplyMindAI.exe'
Copy-Item -LiteralPath $electronExe -Destination $exePath -Force

$resourcesDir = Join-Path $Root 'resources'
$resourcesApp = Join-Path $resourcesDir 'app'
if (Test-Path -LiteralPath $resourcesApp) { Remove-Item -LiteralPath $resourcesApp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $resourcesApp | Out-Null
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

# Electron needs its support files next to the EXE (resources/ replaces the default app dir
# only; the .pak/.dll/.bin stays inside the renamed dist dir).
$electronDist = Join-Path $PSScriptRoot '..\node_modules\electron\dist'
Get-ChildItem -LiteralPath $electronDist -File | ForEach-Object {
    if ($_.Name -notin @('electron.exe', 'LICENSE')) {
        Copy-Item -LiteralPath $_.FullName -Destination $Root -Force
    }
}
Get-ChildItem -LiteralPath $electronDist -Directory | ForEach-Object {
    if ($_.Name -ne 'resources') {
        Copy-Item -LiteralPath $_.FullName -Destination $Root -Recurse -Force
    }
}

# 3. README
$readmePath = Join-Path $Root 'README.txt'
& (Join-Path $PSScriptRoot 'write-readme.ps1') -Root $Root -Version $Version

# 4. release artifacts: ZIP + SHA-256
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zipName = "SupplyMindAI-$Version-win32-x64.zip"
$zipPath = Join-Path $OutDir $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

$tmpZipDir = Join-Path $env:TEMP "supplymind-zip-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tmpZipDir | Out-Null
Copy-Item -LiteralPath $Root -Destination $tmpZipDir -Recurse -Force
Compress-Archive -Path (Join-Path $tmpZipDir 'SupplyMindAI') -DestinationPath $zipPath -CompressionLevel Optimal
Remove-Item -LiteralPath $tmpZipDir -Recurse -Force

$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
Set-Content -LiteralPath "$zipPath.sha256" -Value $hash -Encoding ASCII

$sizeMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host "[package-final] EXE: $exePath"
Write-Host "[package-final] ZIP: $zipPath"
Write-Host "[package-final] SHA-256: $hash"
Write-Host "[package-final] size: $sizeMb MB"
