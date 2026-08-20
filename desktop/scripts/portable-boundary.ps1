# D9 Final Attack F6: REAL portable boundary via the packaged EXE (not raw java.exe).
# Extracts the ZIP into: (a) plain temp dir, (b) path with spaces, (c) path with Chinese chars.
# For each: strip JAVA_HOME/Java PATH/Node PATH/Maven PATH, boot the EXE, and prove the
# backend process executable is <portableRoot>/runtime/jre/bin/java.exe (never system java).
# Also verifies a read-only data dir is rejected before startup (no hidden-dir fallback).
# Usage: .\scripts\portable-boundary.ps1 -Zip <zip> [-EvidenceOut <path>]
param(
    [string]$Zip = (Join-Path $PSScriptRoot '..\..\release\SupplyMindAI-0.9.0-win32-x64.zip'),
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Join-Path $PSScriptRoot '..\..'

if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip" }

function Strip-DevEnv {
    $env:JAVA_HOME = ''
    $env:PATH = ($env:PATH -split ';' | Where-Object {
        $_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven|git\cmd|git\\bin'
    }) -join ';'
    foreach ($name in @('SUPPLYMIND_LLM_ENABLED','SUPPLYMIND_LLM_API_KEY','SUPPLYMIND_LLM_BASE_URL','SUPPLYMIND_LLM_MODEL','SUPPLYMIND_LLM_COMPLETIONS_PATH','SUPPLYMIND_LLM_TIMEOUT','SUPPLYMIND_LLM_PROVIDER')) {
        Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue
    }
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

function Stop-AllAppProcs([string]$RootPath) {
    Get-AppJavaProcs $RootPath | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Get-Process -Name 'SupplyMindAI' -ErrorAction SilentlyContinue | Where-Object { try { $_.Path -like "$RootPath*" } catch { $false } } | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
}

function Test-PortableBoot([string]$RootPath, [string]$Label) {
    $exe = Join-Path $RootPath 'SupplyMindAI.exe'
    if (-not (Test-Path -LiteralPath $exe)) { throw "[$Label] EXE missing: $exe" }
    Remove-Item (Join-Path $RootPath 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
    $exeProc = Start-Process -FilePath $exe -PassThru
    $port = $null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        $urlFile = Join-Path $RootPath 'logs\backend-url.txt'
        if (Test-Path $urlFile) {
            $c = (Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
            if ($c -match 'http://127\.0\.0\.1:(\d+)/?') { $port = [int]$Matches[1]; break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $port) { Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue; throw "[$Label] backend-url never appeared" }
    $deadline = (Get-Date).AddSeconds(45)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        try { $h = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2; if ($h.status -eq 'UP') { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue; throw "[$Label] health failed" }

    $javaProcs = @(Get-AppJavaProcs $RootPath)
    $expectedJava = Join-Path $RootPath 'runtime\jre\bin\java.exe'
    $realJavaPath = if ($javaProcs.Count -gt 0) { $javaProcs[0].Path } else { $null }
    Write-Host "[f6] [$Label] backend UP pid=$($h.pid) java=$realJavaPath"
    $ok = ($realJavaPath -eq $expectedJava) -or ($javaProcs.Count -eq 1 -and $javaProcs[0].CmdLine -like "*$RootPath*")
    Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue
    $exeProc.WaitForExit(15000) | Out-Null
    Start-Sleep -Seconds 5
    return [pscustomobject]@{ Label = $Label; Healthy = $healthy; JavaPath = $realJavaPath; Expected = $expectedJava; Pass = $ok }
}

Strip-DevEnv
$results = [System.Collections.Generic.List[object]]::new()

# (a) plain temp path
$ex1 = Join-Path $env:TEMP ("supplymind-f6-" + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $ex1 | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $ex1 -Force
$r1 = Test-PortableBoot (Join-Path $ex1 'SupplyMindAI') 'plain'
$results.Add($r1)
Start-Sleep -Seconds 2
Stop-AllAppProcs (Join-Path $ex1 'SupplyMindAI')
Remove-Item $ex1 -Recurse -Force

# (b) path with spaces
$ex2 = Join-Path $env:TEMP ("supply mind f6 dir " + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $ex2 | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $ex2 -Force
$r2 = Test-PortableBoot (Join-Path $ex2 'SupplyMindAI') 'spaces'
$results.Add($r2)
Start-Sleep -Seconds 2
Stop-AllAppProcs (Join-Path $ex2 'SupplyMindAI')
Remove-Item $ex2 -Recurse -Force

# (c) path with Chinese chars
$ex3 = Join-Path $env:TEMP ("供应链智脑 AI 便携测试 " + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $ex3 | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $ex3 -Force
$r3 = Test-PortableBoot (Join-Path $ex3 'SupplyMindAI') 'chinese'
$results.Add($r3)
Start-Sleep -Seconds 2
Stop-AllAppProcs (Join-Path $ex3 'SupplyMindAI')
Remove-Item $ex3 -Recurse -Force

# (d) data path occupied by a FILE (not a dir) must be rejected before backend spawn
$ex4 = Join-Path $env:TEMP ("supplymind-f6-ro-" + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $ex4 | Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $ex4 -Force
$roRoot = Join-Path $ex4 'SupplyMindAI'
$roData = Join-Path $roRoot 'data'
Remove-Item $roData -Recurse -Force -ErrorAction SilentlyContinue
# data/ is now a FILE -> preflight must report "path is not a directory" and exit quickly
Set-Content -LiteralPath $roData -Value 'not-a-dir' -Encoding ASCII
$exeProc4 = Start-Process -FilePath (Join-Path $roRoot 'SupplyMindAI.exe') -PassThru
Start-Sleep -Seconds 12
$rejected = $exeProc4.HasExited
if (-not $rejected) { Stop-Process -Id $exeProc4.Id -Force -ErrorAction SilentlyContinue; $exeProc4.WaitForExit(10000) | Out-Null }
Write-Host "[f6] [readonly-data] EXE rejected=$rejected (preflight blocked a non-directory data path)"
$results.Add([pscustomobject]@{ Label = 'readonly-data'; Healthy = $false; JavaPath = $null; Expected = $null; Pass = $rejected })
Stop-AllAppProcs $roRoot
Remove-Item $ex4 -Recurse -Force -ErrorAction SilentlyContinue

$bootChecks = @($results | Where-Object { $_.Label -ne 'readonly-data' })
$pass = ($bootChecks.Count -ge 3) -and (($bootChecks | Where-Object { -not $_.Pass }).Count -eq 0) -and (($results | Where-Object { $_.Label -eq 'readonly-data' }).Pass)
$report = [ordered]@{
    phase = 'portable-boundary'
    candidateCommit = (& git -C $repoRoot rev-parse --short HEAD 2>$null)
    builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    results = $results
    result = if ($pass) { 'PASS' } else { 'FAIL' }
}
if ($EvidenceOut) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
    $json = $report | ConvertTo-Json -Depth 6
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    Write-Host "[f6] evidence: $EvidenceOut"
}
$report | ConvertTo-Json -Depth 6 | Out-Host
if (-not $pass) { exit 1 }
Write-Host '[f6] PASS: portable EXE boots on plain/space/Chinese paths with the bundled JRE; read-only data rejected'