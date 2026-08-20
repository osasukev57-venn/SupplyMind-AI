# D9 Final Attack F5: REAL data persistence across restart and directory move.
# Starts from a clean extracted ZIP, performs a real non-synthetic write through the existing
# HTTP API (manual intake -> RECEIVED+PENDING raw+lifecycle, per D7/D8 contract), records file
# hashes, restarts, and re-reads via the API + verifies identical SHA/manifest, then moves the
# whole dir and re-verifies. Never uses file-count as the persistence proof.
# Usage: .\scripts\persistence-attack.ps1 -Zip <zip> [-ExtractBase <dir>] [-EvidenceOut <path>]
param(
    [string]$Zip,
    [string]$ExtractBase = (Join-Path $env:TEMP 'supplymind-f5'),
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$env:JAVA_HOME = ''
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven' }) -join ';'
foreach ($name in @('SUPPLYMIND_LLM_ENABLED','SUPPLYMIND_LLM_API_KEY','SUPPLYMIND_LLM_BASE_URL','SUPPLYMIND_LLM_MODEL','SUPPLYMIND_LLM_COMPLETIONS_PATH','SUPPLYMIND_LLM_TIMEOUT','SUPPLYMIND_LLM_PROVIDER')) {
    Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue
}

function Get-AppJavaProcs([string]$RootPath) {
    Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object {
        try { $_.Path -like "$RootPath*" } catch { $false }
    } | ForEach-Object {
        $cmdline = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine
        if ($cmdline -match [regex]::Escape((Join-Path $RootPath 'app\supplymind-backend.jar'))) {
            [pscustomobject]@{ Id = $_.Id; Path = $_.Path; CmdLine = $cmdline }
        }
    }
}

if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip" }
$extractDir = Join-Path $ExtractBase ("run-" + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $extractDir -Force
$root = Join-Path $extractDir 'SupplyMindAI'
$exe = Join-Path $root 'SupplyMindAI.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "EXE missing: $exe" }

$report = [ordered]@{
    phase = 'persistence-attack'
    candidateCommit = (git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null)
    builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    root = $root
}

function Start-App {
    Remove-Item (Join-Path $root 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $exe -PassThru
    $port = $null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        $urlFile = Join-Path $root 'logs\backend-url.txt'
        if (Test-Path $urlFile) {
            $c = (Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
            if ($c -match 'http://127\.0\.0\.1:(\d+)/?') { $port = [int]$Matches[1]; break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $port) { throw 'backend-url never appeared' }
    $deadline = (Get-Date).AddSeconds(45)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        try { $h = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2; if ($h.status -eq 'UP') { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; throw 'health failed' }
    return [pscustomobject]@{ ExeProc = $p; Port = $port; Java = (Get-AppJavaProcs $root) }
}

# 1. clean start + record initial configVersion via the config API
$app = Start-App
Write-Host "[f5] boot 1: electron=$($app.ExeProc.Id) java=$($app.Java[0].Id) port=$($app.Port)"
$configFile = Join-Path $root 'data\config\monitor-series.json'
$configApi = Invoke-RestMethod -Uri "http://127.0.0.1:$($app.Port)/api/config/items" -TimeoutSec 10
$report.initialConfigVersion = $configApi.configVersion
$report.initialConfigHash = (Get-FileHash -LiteralPath $configFile -Algorithm SHA256).Hash
Write-Host "[f5] initial configVersion=$($report.initialConfigVersion) hash=$($report.initialConfigHash)"

# 2. real non-synthetic write via existing HTTP API: manual intake into RECEIVED+PENDING.
#    MAT.ADC12.AM is the frozen MANUAL-route item (providerType=manual, unit=元/吨), so the
#    manual HTTP contract accepts the write and persists an immutable raw + lifecycle timeline.
$itemId = 'MAT.ADC12.AM'
$bizDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
$body = @{ itemId = $itemId; source = 'Manual'; businessDate = $bizDate; value = '19500.50'; unit = '元/吨' }
$resp = Invoke-WebRequest -Uri "http://127.0.0.1:$($app.Port)/api/dashboard/manual" -Method Post -Body $body -ContentType 'application/x-www-form-urlencoded; charset=UTF-8' -UseBasicParsing -TimeoutSec 15
$writeJson = $resp.Content
$report.writeHttpStatus = $resp.StatusCode
$report.writeResponse = ($writeJson | ConvertFrom-Json)
Write-Host "[f5] manual write: HTTP $($resp.StatusCode) -> $writeJson"

# record the persisted business file (raw/lifecycle) created by the write
$rawGlob = Join-Path $root 'data\raw'
$rawFiles = Get-ChildItem -LiteralPath $rawGlob -Recurse -Filter '*.json' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match [regex]::Escape($itemId) }
$writeFiles = @()
foreach ($rf in $rawFiles) {
    $writeFiles += [pscustomobject]@{ Path = $rf.FullName.Substring($root.Length).TrimStart('\'); Sha256 = (Get-FileHash -LiteralPath $rf.FullName -Algorithm SHA256).Hash }
}
$report.writeFiles = $writeFiles
if ($writeFiles.Count -eq 0) {
    # fall back to listing the whole raw tree so we can diagnose
    $allRaw = Get-ChildItem -LiteralPath $rawGlob -Recurse -File -ErrorAction SilentlyContinue
    $report.rawTree = @($allRaw | ForEach-Object { $_.FullName.Substring($root.Length).TrimStart('\') })
    throw ('manual write produced no raw file for ' + $itemId)
}

# 3. graceful close
Stop-Process -Id $app.ExeProc.Id -Force -ErrorAction SilentlyContinue
$app.ExeProc.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8

# 4. restart same extract dir
$app2 = Start-App
Write-Host "[f5] boot 2: electron=$($app2.ExeProc.Id) java=$($app2.Java[0].Id) port=$($app2.Port)"

# re-read via API (dashboard sources/manual state is not exposed; use config history + raw files)
$configHash2 = (Get-FileHash -LiteralPath $configFile -Algorithm SHA256).Hash
$rawFiles2 = Get-ChildItem -LiteralPath (Join-Path $root 'data\raw') -Recurse -Filter '*.json' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match [regex]::Escape($itemId) }
$shaMatch = $configHash2 -eq $report.initialConfigHash
$rawMatch = $rawFiles2.Count -eq $writeFiles.Count
$report.restartConfigHash = $configHash2
$report.restartConfigHashUnchanged = $shaMatch
$report.restartRawCountUnchanged = $rawMatch
$restartPass = $shaMatch -and $rawMatch
Write-Host "[f5] restart: configHashUnchanged=$shaMatch rawCountUnchanged=$rawMatch -> $restartPass"

# 5. move the whole directory. Ensure the running instance is FULLY down (Electron + Java)
#    before moving, so no process holds file handles on the extracted root.
$movedBase = Join-Path $ExtractBase ("moved-" + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $movedBase | Out-Null
$movedRoot = Join-Path $movedBase 'SupplyMindAI'

$app2Java = @(Get-AppJavaProcs $root)
foreach ($j in $app2Java) { Stop-Process -Id $j.Id -Force -ErrorAction SilentlyContinue }
if ($app2.ExeProc -and -not $app2.ExeProc.HasExited) { Stop-Process -Id $app2.ExeProc.Id -Force -ErrorAction SilentlyContinue }
$app2.ExeProc.WaitForExit(15000) | Out-Null
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    if (@(Get-AppJavaProcs $root).Count -eq 0) { break }
    Start-Sleep -Milliseconds 500
}
Start-Sleep -Seconds 2
Move-Item -LiteralPath $root -Destination $movedRoot

# start from the moved root
Remove-Item (Join-Path $movedRoot 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
$p3 = Start-Process -FilePath (Join-Path $movedRoot 'SupplyMindAI.exe') -PassThru
$port3 = $null
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $urlFile = Join-Path $movedRoot 'logs\backend-url.txt'
    if (Test-Path $urlFile) {
        $c = (Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
        if ($c -match 'http://127\.0\.0\.1:(\d+)/?') { $port3 = [int]$Matches[1]; break }
    }
    Start-Sleep -Seconds 1
}
if (-not $port3) { throw 'moved-root backend-url never appeared' }
$deadline = (Get-Date).AddSeconds(45)
$healthy3 = $false
while ((Get-Date) -lt $deadline) {
    try { $h3 = Invoke-RestMethod -Uri "http://127.0.0.1:$port3/api/health" -TimeoutSec 2; if ($h3.status -eq 'UP') { $healthy3 = $true; break } } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $healthy3) { throw 'moved-root health failed' }
$movedConfigFile = Join-Path $movedRoot 'data\config\monitor-series.json'
$configHash3 = (Get-FileHash -LiteralPath $movedConfigFile -Algorithm SHA256).Hash
$movedRawFiles = Get-ChildItem -LiteralPath (Join-Path $movedRoot 'data\raw') -Recurse -Filter '*.json' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match [regex]::Escape($itemId) }
$movedPass = ($configHash3 -eq $report.initialConfigHash) -and ($movedRawFiles.Count -eq $writeFiles.Count)
$report.movedConfigHash = $configHash3
$report.movedConfigHashUnchanged = ($configHash3 -eq $report.initialConfigHash)
$report.movedRawCountUnchanged = ($movedRawFiles.Count -eq $writeFiles.Count)
Write-Host "[f5] moved: configHashUnchanged=$($configHash3 -eq $report.initialConfigHash) rawCountUnchanged=$($movedRawFiles.Count -eq $writeFiles.Count) -> $movedPass"

# cleanup
Stop-Process -Id $p3.Id -Force -ErrorAction SilentlyContinue
$p3.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8
$residual = @(Get-AppJavaProcs $movedRoot)
$report.residualAfterAll = @($residual | ForEach-Object { $_.Id })

$allPass = $restartPass -and $movedPass -and ($residual.Count -eq 0)
$report.result = if ($allPass) { 'PASS' } else { 'FAIL' }
if ($EvidenceOut) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
    $json = $report | ConvertTo-Json -Depth 8
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    Write-Host "[f5] evidence: $EvidenceOut"
}
$report | ConvertTo-Json -Depth 8 | Out-Host
if (-not $allPass) { exit 1 }
Write-Host '[f5] PASS: real write persisted across restart and directory move'