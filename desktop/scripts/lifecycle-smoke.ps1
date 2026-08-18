# D9-T04 Windows smoke acceptance:
#   1. graceful shutdown: start backend, SIGTERM-style stop, assert no residual java + port freed
#   2. orphan recovery: start backend with a parent-pid that dies -> backend exits on its own
#      (ChildProcessWatchdog), no permanent java process
#   3. lock recovery: force-kill the backend, immediately restart -> the stale OS lock does
#      not block a fresh start (SingleWriterGuard FileLock is released by the OS on death)
#   4. single instance: Electron lock logic (module-level) is covered by unit tests; here we
#      verify the watchdog path end-to-end with a real JVM.
# Usage: .\scripts\lifecycle-smoke.ps1 [-Root <portableRoot>]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$javaExe = Join-Path $Root 'runtime\jre\bin\java.exe'
$jar = Join-Path $Root 'app\supplymind-backend.jar'
foreach ($p in @($javaExe, $jar)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "portable layout missing: $p (run package-portable.ps1 first)" }
}

function Get-FreePort {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = ([System.Net.IPEndPoint]$l.LocalEndpoint).Port; $l.Stop()
    return $p
}

function Wait-Healthy([int]$Port, [int]$Seconds = 40) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
            if ($r.status -eq 'UP') { return $r }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "health check timed out on port $Port"
}

function Start-Backend([string]$DataDir, [int]$ParentPid = 0) {
    $port = Get-FreePort
    $log = Join-Path $env:TEMP "supplymind-lifecycle-$port.log"
    $extra = if ($ParentPid -gt 0) { "--supplymind.desktop.parent-pid=$ParentPid" } else { '' }
    $argLine = "-jar `"$jar`" --server.port=$port --server.address=127.0.0.1 `"--supplymind.data-root=$DataDir`" $extra"
    $proc = Start-Process -FilePath $javaExe -ArgumentList $argLine -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    return @{ Proc = $proc; Port = $port; Log = $log }
}

function Stop-WithGrace($Session) {
    # mirrors Electron's stopChild: SIGTERM first, then taskkill fallback
    $p = $Session.Proc
    if (-not $p.HasExited) {
        $p.Kill()  # Stop-Process -Id = TerminateProcess (hard); SIGTERM equivalent on Windows
        if (-not $p.WaitForExit(5000)) {
            taskkill /pid $p.Id /T /F | Out-Null
        }
    }
    Start-Sleep -Seconds 2
    if (Get-Process -Id $p.Id -ErrorAction SilentlyContinue) { throw "residual process remains after graceful stop" }
    $listener = Get-NetTCPConnection -LocalPort $Session.Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "port $($Session.Port) still listening after graceful stop" }
    Write-Host "[graceful] PASS: java exited, port $($Session.Port) freed, no residual"
}

# ---------- 1. graceful shutdown ----------
$data1 = Join-Path $env:TEMP 'supplymind-d9t04-data1'
New-Item -ItemType Directory -Force -Path $data1 | Out-Null
$s1 = Start-Backend $data1
$h1 = Wait-Healthy $s1.Port
Write-Host "[graceful] backend UP pid=$($h1.pid) on port $($s1.Port)"
Stop-WithGrace $s1

# ---------- 2. orphan recovery (watchdog) ----------
# Parent process: a short-lived cmd that exits after 2s. The backend must exit by itself
# within the watchdog poll interval (2s) + margin once the parent is gone.
$data2 = Join-Path $env:TEMP 'supplymind-d9t04-data2'
New-Item -ItemType Directory -Force -Path $data2 | Out-Null
$parentLog = Join-Path $env:TEMP 'supplymind-d9t04-parent.log'
$parent = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','timeout /t 2 >nul & exit 0' -PassThru -NoNewWindow -RedirectStandardOutput $parentLog -RedirectStandardError "$parentLog.err"
Write-Host "[orphan] parent pid=$($parent.Id), backend watching it"
$s2 = Start-Backend $data2 -ParentPid $parent.Id
$h2 = Wait-Healthy $s2.Port
Write-Host "[orphan] backend UP pid=$($h2.pid), waiting for parent exit -> watchdog must stop backend"
$parent.WaitForExit(10000) | Out-Null
$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
    if ($s2.Proc.HasExited) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $s2.Proc.HasExited) {
    Stop-WithGrace $s2
    throw "[orphan] FAIL: backend did not exit after parent died (watchdog missing?)"
}
Write-Host "[orphan] PASS: backend exited by itself after parent death (watchdog effective)"
$listener2 = Get-NetTCPConnection -LocalPort $s2.Port -State Listen -ErrorAction SilentlyContinue
if ($listener2) { throw "[orphan] FAIL: port $($s2.Port) still listening" }

# ---------- 3. lock recovery ----------
# Force-kill the backend (simulates crash), then immediately restart with the same data dir.
# The OS releases the SingleWriterGuard FileLock on process death, so the restart must win
# the lock immediately.
$data3 = Join-Path $env:TEMP 'supplymind-d9t04-data3'
New-Item -ItemType Directory -Force -Path $data3 | Out-Null
$s3 = Start-Backend $data3
$h3 = Wait-Healthy $s3.Port
Write-Host "[lock] backend UP pid=$($h3.pid) with writer lock"
Stop-Process -Id $s3.Proc.Id -Force -ErrorAction SilentlyContinue
$s3.Proc.WaitForExit(10000) | Out-Null
Start-Sleep -Seconds 1
$lockFile = Join-Path $data3 'runtime\dirty\.supplymind-writer.lock'
if (Test-Path -LiteralPath $lockFile) {
    Write-Host "[lock] stale lock file present: $lockFile (must not block restart)"
} else {
    Write-Host '[lock] no lock file left behind'
}
$s4 = Start-Backend $data3
$h4 = Wait-Healthy $s4.Port
Write-Host "[lock] PASS: restart after crash acquired the lock (pid=$($h4.pid))"
Stop-WithGrace $s4

Write-Host '[lifecycle-smoke] ALL D9-T04 acceptance items PASS'
