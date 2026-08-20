# D9 Final Attack F2 #5: keyed portable EXE real Cloud gate.
# This gate issues a REAL, possibly-BILLING chat completion via the bundled/desktop backend
# using the user's SUPPLYMIND_LLM_API_KEY. It runs ONLY when explicitly authorized via
# env SUPPLYMIND_CLOUD_GATE_AUTHORIZED=explicitly-yes.
#
# Secret handling: the API key is read from the environment, passed to the child backend
# ONLY through the inherited environment (never a CLI arg), and the report stores ONLY
# generatedBy/provider/model + counts. No prompt, answer, headers, Authorization or key.
#
# Usage:
#   $env:SUPPLYMIND_CLOUD_GATE_AUTHORIZED='explicitly-yes'
#   .\scripts\keyed-cloud-gate.ps1 -Root <extracted portable root>
param(
    [string]$Root,
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:GitExe = 'C:\Program Files\Git\cmd\git.exe'
if (-not (Test-Path $script:GitExe)) { $script:GitExe = 'git' }
function Get-Head { & $script:GitExe -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null }

if ($env:SUPPLYMIND_CLOUD_GATE_AUTHORIZED -ne 'explicitly-yes') {
    Write-Host '[cloud-gate] READY_FOR_USER_AUTHORIZATION'
    Write-Host '[cloud-gate] Not executed: a real chat completion may bill the account.'
    Write-Host '[cloud-gate] Set SUPPLYMIND_CLOUD_GATE_AUTHORIZED=explicitly-yes to run.'
    if ($EvidenceOut) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
        $json = @{ result='READY_FOR_USER_AUTHORIZATION'; reason='explicit user authorization required for a potentially-billing cloud request'; candidateCommit=(Get-Head); builtAt=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz') } | ConvertTo-Json
        Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
        Write-Host "[cloud-gate] evidence: $EvidenceOut"
    }
    exit 0
}

if (-not $Root) { throw 'Root is required' }
$exe = Join-Path $Root 'SupplyMindAI.exe'
$java = Join-Path $Root 'runtime\jre\bin\java.exe'
foreach ($p in @($exe, $java)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "portable root missing: $p" }
}

# strip system Java/Node/Maven from PATH so only the bundled JRE could run the backend
$env:JAVA_HOME = ''
$env:PATH = ($env:PATH -split ';' | Where-Object {
    $_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven'
}) -join ';'

# The key stays in the environment only (inherited by the spawned EXE -> child JVM).
# SUPPLYMIND_LLM_ENABLED is required for the Cloud path.
if (-not $env:SUPPLYMIND_LLM_API_KEY) { throw 'SUPPLYMIND_LLM_API_KEY is required for the keyed gate' }
$env:SUPPLYMIND_LLM_ENABLED = 'true'
if (-not $env:SUPPLYMIND_LLM_BASE_URL) { $env:SUPPLYMIND_LLM_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1' }
if (-not $env:SUPPLYMIND_LLM_MODEL) { $env:SUPPLYMIND_LLM_MODEL = 'qwen-plus' }
$env:SUPPLYMIND_LLM_COMPLETIONS_PATH = '/chat/completions'

Remove-Item (Join-Path $Root 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
$exeProc = Start-Process -FilePath $exe -PassThru

$port = $null
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $urlFile = Join-Path $Root 'logs\backend-url.txt'
    if (Test-Path $urlFile) {
        $c = (Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
        if ($c -match 'http://127\.0\.0\.1:(\d+)/?') { $port = [int]$Matches[1]; break }
    }
    Start-Sleep -Seconds 1
}
if (-not $port) { Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue; throw 'backend-url never appeared' }

$deadline = (Get-Date).AddSeconds(45)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
        if ($h.status -eq 'UP') { $healthy = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $healthy) { Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue; throw 'health check failed' }

# real, possibly-billing chat completion through the desktop backend
$body = @{ question = 'What is the latest available daily average value? Reply with OK only.'; mode = 'FORMAL' } | ConvertTo-Json
try {
    $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/agent/query" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 120
} catch {
    Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue
    Write-Host "[cloud-gate] FAIL: agent query error: $($_.Exception.Message)"
    if ($EvidenceOut) { Set-Content -LiteralPath $EvidenceOut -Value (@{ result='FAIL'; error=$_.Exception.Message; candidateCommit=((Get-Head)) } | ConvertTo-Json) -Encoding UTF8 }
    exit 1
}

# record ONLY non-secret facts (tests/counts/generatedBy/provider/model) - never prompt/answer/headers/key
$gateReport = [ordered]@{
    result = if ($resp.generatedBy -eq 'LLM') { 'PASS' } else { 'FAIL' }
    generatedBy = $resp.generatedBy
    provider = $resp.provider
    model = $resp.model
    degraded = $resp.degraded
    candidateCommit = ((Get-Head))
    builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    tests = 1
    failures = 0
    errors = 0
    skipped = 0
}

Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue
$exeProc.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8

$residual = Get-Process -Name 'SupplyMindAI','electron','java' -ErrorAction SilentlyContinue | Where-Object {
    ($_.Path -like "$Root*") -or ($_.ProcessName -eq 'java' -and $_.Path -like "$Root*")
}
if ($residual) { 
    $residual | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

if ($EvidenceOut) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
    Set-Content -LiteralPath $EvidenceOut -Value ($gateReport | ConvertTo-Json) -Encoding UTF8
    $h = (Get-FileHash -LiteralPath $EvidenceOut -Algorithm SHA256).Hash
    Write-Host "[cloud-gate] evidence: $EvidenceOut (sha256=$h)"
}
$gateReport | ConvertTo-Json | Out-Host
if ($gateReport.result -ne 'PASS') { exit 1 }
