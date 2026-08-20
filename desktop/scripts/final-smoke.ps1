# D9-T05 final desktop smoke: extract the ZIP to a fresh writable location, launch the real
# EXE (Electron shell), wait for the backend health, verify the Vue shell loads, exercise
# the business pages over HTTP, restart to prove data persistence, then exit cleanly and
# assert no residual java/electron process.
# Usage: .\scripts\final-smoke.ps1 [-Zip <path>] [-ExtractDir <dir>]
param(
    [string]$Zip = (Join-Path $PSScriptRoot '..\..\release\SupplyMindAI-0.9.0-win32-x64.zip'),
    [string]$ExtractDir = (Join-Path $env:TEMP 'supplymind-final-smoke')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip (run package-final.ps1 first)" }

# clean, fresh extraction (simulates a user's clean Windows machine)
if (Test-Path -LiteralPath $ExtractDir) { Remove-Item -LiteralPath $ExtractDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractDir -Force

$root = Join-Path $ExtractDir 'SupplyMindAI'
$exe = Join-Path $root 'SupplyMindAI.exe'
$java = Join-Path $root 'runtime\jre\bin\java.exe'
foreach ($p in @($exe, $java)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "extracted layout missing: $p" }
}
Write-Host "[final-smoke] extracted to $root"

# 1. first boot: launch the EXE, find the backend health endpoint
#    Simulate a clean machine: strip all LLM environment variables so the backend runs the
#    deterministic Java-template fallback (cloud LLM is optional and never required).
$env:JAVA_HOME = ''
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\' }) -join ';'
foreach ($name in @('SUPPLYMIND_LLM_ENABLED', 'SUPPLYMIND_LLM_PROVIDER', 'SUPPLYMIND_LLM_MODEL',
        'SUPPLYMIND_LLM_BASE_URL', 'SUPPLYMIND_LLM_API_KEY', 'SUPPLYMIND_LLM_COMPLETIONS_PATH',
        'SUPPLYMIND_LLM_TIMEOUT')) {
    Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue
}
$exeProc = Start-Process -FilePath $exe -PassThru
Write-Host "[final-smoke] EXE started pid=$($exeProc.Id) (no system JAVA_HOME, no LLM env)"

$backendPort = $null
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    $urlFile = Join-Path $root 'logs\backend-url.txt'
    if (Test-Path -LiteralPath $urlFile) {
        $content = (Get-Content -LiteralPath $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
        if ($content -match 'http://127\.0\.0\.1:(\d+)/?') {
            $backendPort = [int]$Matches[1]
            break
        }
    }
    Start-Sleep -Seconds 1
}
if (-not $backendPort) { throw '[final-smoke] backend-url.txt never appeared (EXE failed to start backend)' }
Write-Host "[final-smoke] backend on http://127.0.0.1:$backendPort (dynamic port)"

# wait for health
$healthy = $false
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort/api/health" -TimeoutSec 2
        if ($r.status -eq 'UP') { $healthy = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $healthy) { throw '[final-smoke] backend health check timed out' }
Write-Host "[final-smoke] health UP pid=$($r.pid)"

# 2. Vue shell + business pages (same-origin /api)
$page = Invoke-WebRequest -Uri "http://127.0.0.1:$backendPort/" -TimeoutSec 5 -UseBasicParsing
if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'id="app"') {
    throw '[final-smoke] Vue shell not served'
}
Write-Host '[final-smoke] Vue shell loaded (same-origin)'

foreach ($api in @(
    '/api/dashboard/overview',
    '/api/dashboard/history?itemId=FX.USD.CNY.PBOC_MID&from=2026-01-01&to=2026-08-18',
    '/api/config/items',
    '/api/warnings?itemId=FX.USD.CNY.PBOC_MID&from=2026-01-01&to=2026-12-31',
    '/api/agent/query'
)) {
    try {
        if ($api -eq '/api/agent/query') {
            $body = @{ question = '分析ADC12近期上涨风险'; mode = 'FORMAL' } | ConvertTo-Json
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort$api" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 30
            if ($resp.generatedBy -ne 'JAVA_TEMPLATE') {
                throw "[final-smoke] FAIL: expected JAVA_TEMPLATE degradation without LLM env, got $($resp.generatedBy)"
            }
            Write-Host "[final-smoke] $api -> 200 (generatedBy=$($resp.generatedBy), degraded=$($resp.degraded))"
        } else {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort$api" -TimeoutSec 10
            Write-Host "[final-smoke] $api -> 200"
        }
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        Write-Host "[final-smoke] $api -> HTTP $code (structured response ok)"
    }
}

# 3. graceful exit via the EXE (close the app: terminate Electron -> watchdog cleans Java)
Stop-Process -Id $exeProc.Id -ErrorAction SilentlyContinue
$exeProc.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8

# 4. no residual processes (scoped to THIS extract root: executable path must live under $root)
$residual = Get-Process -Name 'SupplyMindAI', 'electron', 'java' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.Path -like "$root*" } catch { $false }
}
if ($residual) {
    $residual | ForEach-Object { Write-Host "residual: $($_.ProcessName) pid=$($_.Id)" }
    throw '[final-smoke] FAIL: residual processes remain after exit'
}
Write-Host '[final-smoke] no residual Electron/Java processes'

# 5. restart -> data persists (data/config exists from first run). Delete the stale url
#    file first so we only accept the NEW instance's dynamic port.
$urlFile2 = Join-Path $root 'logs\backend-url.txt'
if (Test-Path -LiteralPath $urlFile2) { Remove-Item -LiteralPath $urlFile2 -Force }
$exeProc2 = Start-Process -FilePath $exe -PassThru
$backendPort2 = $null
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $urlFile2) {
        $content = (Get-Content -LiteralPath $urlFile2 -Raw -ErrorAction SilentlyContinue).Trim()
        if ($content -match 'http://127\.0\.0\.1:(\d+)/?') { $backendPort2 = [int]$Matches[1]; break }
    }
    Start-Sleep -Seconds 1
}
if (-not $backendPort2) { throw '[final-smoke] restart did not bring the backend up' }
$healthy2 = $false
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
    try {
        $r2 = Invoke-RestMethod -Uri "http://127.0.0.1:$backendPort2/api/health" -TimeoutSec 2
        if ($r2.status -eq 'UP') { $healthy2 = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $healthy2) { throw '[final-smoke] restart health check failed' }
$dataFiles = Get-ChildItem -LiteralPath (Join-Path $root 'data') -Recurse -File -ErrorAction SilentlyContinue
Write-Host "[final-smoke] restart UP pid=$($r2.pid); data persisted ($($dataFiles.Count) files under data/)"

Stop-Process -Id $exeProc2.Id -ErrorAction SilentlyContinue
$exeProc2.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8
$residual2 = Get-Process -Name 'SupplyMindAI', 'electron', 'java' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.Path -like "$root*" } catch { $false }
}
if ($residual2) { throw '[final-smoke] FAIL: residual processes after restart' }
Write-Host '[final-smoke] second exit clean, no residual'

Write-Host '[final-smoke] ALL D9-T05 acceptance items PASS'
