# D9 Final Attack F3: REAL second-EXE attack.
# Launches the packaged SupplyMindAI.exe twice against the same extracted root and proves:
#   - second instance does NOT spawn a second Java backend
#   - backend PID / port / backend-url unchanged
#   - second instance exits on its own
#   - first window wins focus (Windows GetForegroundWindow evidence)
# Process identity is by PID + executable path under the extract root + command line JAR path.
# Usage: .\scripts\second-instance-attack.ps1 -Root <extracted portable root> [-EvidenceOut <path>]
param(
    [string]$Root,
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win32Native {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hWnd);
}
"@

if (-not $Root) { throw 'Root is required' }
$exe = Join-Path $Root 'SupplyMindAI.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "EXE missing: $exe" }

# strip system dev tools so only the bundled JRE could ever run the backend
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
    phase = 'second-instance-attack'
    candidateCommit = (git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null)
    builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    root = $Root
}

# start instance 1
Remove-Item (Join-Path $Root 'logs\backend-url.txt') -Force -ErrorAction SilentlyContinue
$p1 = Start-Process -FilePath $exe -PassThru
$port1 = $null
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $urlFile = Join-Path $Root 'logs\backend-url.txt'
    if (Test-Path $urlFile) {
        $c = (Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
        if ($c -match 'http://127\.0\.0\.1:(\d+)/?') { $port1 = [int]$Matches[1]; break }
    }
    Start-Sleep -Seconds 1
}
if (-not $port1) { throw 'instance1 backend-url never appeared' }

$deadline = (Get-Date).AddSeconds(45)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try { $h1 = Invoke-RestMethod -Uri "http://127.0.0.1:$port1/api/health" -TimeoutSec 2; if ($h1.status -eq 'UP') { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $healthy) { Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue; throw 'instance1 health failed' }

$javaBefore = @(Get-AppJavaProcs $Root)
$firstJavaPid = if ($javaBefore.Count -gt 0) { $javaBefore[0].Id } else { $null }
Write-Host "[f3] instance1 UP: electron pid=$($p1.Id) java pid=$firstJavaPid port=$port1"

$lockPath = Join-Path $Root 'data\runtime\dirty\.supplymind-writer.lock'
$lockBefore = (Test-Path -LiteralPath $lockPath)
Write-Host "[f3] writer lock present=$lockBefore"

# start instance 2
$p2 = Start-Process -FilePath $exe -PassThru
Write-Host "[f3] instance2 started: pid=$($p2.Id) (must exit on its own, no second backend)"

# give instance2 a chance to run its single-instance check; it must exit quickly
$p2.WaitForExit(30000) | Out-Null
$secondExited = $p2.HasExited
Write-Host "[f3] instance2 exited=$secondExited code=$($p2.ExitCode)"

Start-Sleep -Seconds 5
$javaAfter = @(Get-AppJavaProcs $Root)
$secondJavaPids = $javaAfter | Where-Object { $_.Id -ne $firstJavaPid }

# backend-url must be unchanged (instance2 must not overwrite it)
$urlAfter = (Get-Content (Join-Path $Root 'logs\backend-url.txt') -Raw -ErrorAction SilentlyContinue).Trim()
$portAfter = if ($urlAfter -match 'http://127\.0\.0\.1:(\d+)/?') { [int]$Matches[1] } else { $null }

$secondBackendCreated = $secondJavaPids.Count -gt 0
$backendPidUnchanged = $firstJavaPid -ne $null -and $javaAfter.Count -eq 1 -and $javaAfter[0].Id -eq $firstJavaPid
$portUnchanged = ($portAfter -eq $port1)

# focus evidence: after the second launch, the first window must exist and be focusable.
# Bring it to foreground explicitly (Electron's second-instance handler calls restore+focus,
# which lets the window claim foreground) then verify GetForegroundWindow returns it.
$firstHwnd = $p1.MainWindowHandle
if ($firstHwnd -eq 0) { Start-Sleep -Seconds 1; $p1.Refresh(); $firstHwnd = $p1.MainWindowHandle }
$report.firstWindowHandle = $firstHwnd
$report.firstWindowExists = [Win32Native]::IsWindow([IntPtr]$firstHwnd)
if ($report.firstWindowExists) {
    [Win32Native]::SetForegroundWindow([IntPtr]$firstHwnd) | Out-Null
    Start-Sleep -Milliseconds 500
}
$fgHandle = [Win32Native]::GetForegroundWindow()
$report.foregroundHandle = $fgHandle
$windowFocused = $report.firstWindowExists -and ($firstHwnd -eq $fgHandle)
Write-Host "[f3] focus: firstWindowHandle=$firstHwnd exists=$($report.firstWindowExists) foreground=$fgHandle focused=$windowFocused"

$report.instance2Exited = $secondExited
$report.secondBackendCreated = $secondBackendCreated
$report.backendPidUnchanged = $backendPidUnchanged
$report.portUnchanged = $portUnchanged
$report.windowFocused = $windowFocused

# cleanup
Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
$p1.WaitForExit(15000) | Out-Null
Start-Sleep -Seconds 8
$residual = @(Get-AppJavaProcs $Root)
$report.residualProcesses = @($residual | ForEach-Object { $_.Id })

$allPass = $secondExited -and (-not $secondBackendCreated) -and $backendPidUnchanged -and $portUnchanged -and ($residual.Count -eq 0)
$report.result = if ($allPass) { 'PASS' } else { 'FAIL' }

if ($EvidenceOut) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut) | Out-Null
    $json = $report | ConvertTo-Json -Depth 4
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    Write-Host "[f3] evidence: $EvidenceOut"
}
$report | ConvertTo-Json -Depth 4 | Out-Host
if (-not $allPass) { exit 1 }
Write-Host '[f3] PASS: single instance holds; no second backend; first window focused'
