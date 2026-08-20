# D9 Final Attack F4: lifecycle / orphan / port / lock attack against the REAL packaged EXE.
# Uses the exact extracted root + recorded PIDs + command-line JAR path to identify app processes
# (never a bare process-name count that could flag unrelated system Java processes).
# Usage: .\scripts\lifecycle-attack.ps1 -Root <extracted portable root> [-EvidenceOut <path>]
param(
    [string]$Root,
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $Root) { throw 'Root is required' }
$exe = Join-Path $Root 'SupplyMindAI.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "EXE missing: $exe" }
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

$report = [ordered]@{
    phase = 'lifecycle-attack'
    candidateCommit = (git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null)
    builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    root = $Root
}

function Start-App {
    Remove-Item (Join-Path $Root 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $exe -PassThru
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
    if (-not $port) { throw 'backend-url never appeared' }
    $deadline = (Get-Date).AddSeconds(45)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        try { $h = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2; if ($h.status -eq 'UP') { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; throw 'health failed' }
    return [pscustomobject]@{ ExeProc = $p; Port = $port; Java = (Get-AppJavaProcs $Root) }
}

# ---- 1. normal exit: close gracefully -> Electron + Java both gone, port freed ----
$app1 = Start-App
$firstJava = @($app1.Java | Select-Object -First 1)
$javaPid1 = if ($firstJava.Count -gt 0) { $firstJava[0].Id } else { $null }
Write-Host "[f4] normal-exit: app UP (electron=$($app1.ExeProc.Id) java=$javaPid1 port=$($app1.Port))"
if (-not $javaPid1) { Stop-Process -Id $app1.ExeProc.Id -Force -ErrorAction SilentlyContinue; throw 'no java backend found' }

# graceful close: ask Electron to quit (close main window via WM_CLOSE does run will-quit)
$mainHwnd = $app1.ExeProc.MainWindowHandle
if ($mainHwnd -ne 0) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win32Close {
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
}
"@
    [Win32Close]::SendMessage([IntPtr]$mainHwnd, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
}
$app1.ExeProc.WaitForExit(20000) | Out-Null
Start-Sleep -Seconds 10
$residualNorm = @(Get-AppJavaProcs $Root)
$portListens = Get-NetTCPConnection -LocalPort $app1.Port -State Listen -ErrorAction SilentlyContinue
$normalExitPass = $app1.ExeProc.HasExited -and ($residualNorm.Count -eq 0) -and (-not $portListens)
$report.normalExit = if ($normalExitPass) { 'PASS' } else { 'FAIL' }
Write-Host "[f4] normal-exit: electronExited=$($app1.ExeProc.HasExited) residualJava=$($residualNorm.Count) portListen=$([bool]$portListens) -> $($report.normalExit)"

# ---- 2. forced Electron exit -> watchdog must stop the Java backend ----
$app2 = Start-App
$javaPid2 = @($app2.Java | Select-Object -First 1)
$javaPid2 = if ($javaPid2.Count -gt 0) { $javaPid2[0].Id } else { $null }
Write-Host "[f4] forced: app UP (electron=$($app2.ExeProc.Id) java=$javaPid2 port=$($app2.Port))"
if (-not $javaPid2) { Stop-Process -Id $app2.ExeProc.Id -Force -ErrorAction SilentlyContinue; throw 'no java backend found' }
Stop-Process -Id $app2.ExeProc.Id -Force -ErrorAction SilentlyContinue  # kill Electron hard
$deadline = (Get-Date).AddSeconds(30)
$watchdogStopped = $false
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $javaPid2 -ErrorAction SilentlyContinue)) { $watchdogStopped = $true; break }
    Start-Sleep -Milliseconds 500
}
$port2Listens = Get-NetTCPConnection -LocalPort $app2.Port -State Listen -ErrorAction SilentlyContinue
$forcedPass = $watchdogStopped -and (-not $port2Listens)
$report.forcedElectronExit = if ($forcedPass) { 'PASS' } else { 'FAIL' }
Write-Host "[f4] forced-exit: java $javaPid2 stopped=$watchdogStopped portListen=$([bool]$port2Listens) -> $($report.forcedElectronExit)"
Start-Sleep -Seconds 5

# ---- 3. Java killed hard -> Electron reports and exits ----
$app3 = Start-App
$javaPid3 = @($app3.Java | Select-Object -First 1)
$javaPid3 = if ($javaPid3.Count -gt 0) { $javaPid3[0].Id } else { $null }
Write-Host "[f4] java-kill: app UP (electron=$($app3.ExeProc.Id) java=$javaPid3 port=$($app3.Port))"
if (-not $javaPid3) { Stop-Process -Id $app3.ExeProc.Id -Force -ErrorAction SilentlyContinue; throw 'no java backend found' }
Stop-Process -Id $javaPid3 -Force -ErrorAction SilentlyContinue
$deadline = (Get-Date).AddSeconds(30)
$app3.ExeProc.WaitForExit(30000) | Out-Null
Start-Sleep -Seconds 5
$javaKillPass = $app3.ExeProc.HasExited
$report.javaKilledRecovery = if ($javaKillPass) { 'PASS' } else { 'FAIL' }
Write-Host "[f4] java-kill: electronExited=$($app3.ExeProc.HasExited) -> $($report.javaKilledRecovery)"

# ---- 4. writer lock recovery: force-kill backend, immediately restart, lock must be re-acquired ----
$app4 = Start-App
$javaPid4 = @($app4.Java | Select-Object -First 1)
$javaPid4 = if ($javaPid4.Count -gt 0) { $javaPid4[0].Id } else { $null }
$lockFile = Join-Path $Root 'data\runtime\dirty\.supplymind-writer.lock'
Stop-Process -Id $javaPid4 -Force -ErrorAction SilentlyContinue
$app4.ExeProc.WaitForExit(20000) | Out-Null
Start-Sleep -Seconds 3
$lockStale = Test-Path -LiteralPath $lockFile
$app5 = Start-App
$javaPid5 = @($app5.Java | Select-Object -First 1)
$javaPid5 = if ($javaPid5.Count -gt 0) { $javaPid5[0].Id } else { $null }
$lockRecoveryPass = ($javaPid5 -ne $null) -and (Test-Path -LiteralPath $lockFile)
$report.writerLockRecovery = if ($lockRecoveryPass) { 'PASS' } else { 'FAIL' }
Write-Host "[f4] lock-recovery: staleLock=$lockStale, newBackendPid=$javaPid5, lockReacquired=$(Test-Path $lockFile) -> $($report.writerLockRecovery)"

# cleanup
Stop-Process -Id $app5.ExeProc.Id -Force -ErrorAction SilentlyContinue
$app5.ExeProc.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8
$residualFinal = @(Get-AppJavaProcs $Root)
$report.residualAfterAll = @($residualFinal | ForEach-Object { $_.Id })

$allPass = $normalExitPass -and $forcedPass -and $javaKillPass -and $lockRecoveryPass -and ($residualFinal.Count -eq 0)
$report.result = if ($allPass) { 'PASS' } else { 'FAIL' }

if ($EvidenceOut) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
    $json = $report | ConvertTo-Json -Depth 4
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    Write-Host "[f4] evidence: $EvidenceOut"
}
$report | ConvertTo-Json -Depth 4 | Out-Host
if (-not $allPass) { exit 1 }
Write-Host '[f4] PASS: normal exit, forced exit, java-kill and lock recovery all verified'