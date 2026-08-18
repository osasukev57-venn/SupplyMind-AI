# D9-T01 desktop smoke test (dev mode): starts the backend JAR with the system Java on a
# dynamic loopback port, waits for /api/health, verifies the port is loopback-only, then
# shuts the child down and asserts no residual java process from this run remains.
# Usage: .\scripts\smoke.ps1 [-Jar <path>] [-Java <path>] [-DataRoot <path>]
param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\..\backend\target\supplymind-backend-0.1.0-SNAPSHOT.jar'),
    [string]$Java = 'java',
    [string]$DataRoot = (Join-Path $PSScriptRoot '..\..\target\desktop-smoke-data')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not (Test-Path -LiteralPath $Jar)) {
    throw "JAR not found: $Jar (run 'mvnw.cmd package -DskipTests' in backend/ first)"
}

$port = $null
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null
$logFile = Join-Path (Split-Path $DataRoot) 'desktop-smoke-backend.log'
if (Test-Path $logFile) { Remove-Item $logFile }

$quotedJar = '"' + $Jar + '"'
$argLine = "-jar $quotedJar --server.port=$port --server.address=127.0.0.1 `"--supplymind.data-root=$DataRoot`""

Write-Host "[smoke] starting backend on 127.0.0.1:$port"
$proc = Start-Process -FilePath $Java -ArgumentList $argLine -PassThru -NoNewWindow -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"

try {
    $deadline = (Get-Date).AddSeconds(30)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "backend exited early with code $($proc.ExitCode)"
        }
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
            if ($resp.status -eq 'UP') {
                $healthy = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $healthy) { throw 'health check timed out' }
    Write-Host "[smoke] health UP pid=$($resp.pid)"

    # loopback-only: the listener must be bound to 127.0.0.1 (no LAN exposure)
    $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
    $badBind = $listeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }
    if ($badBind) {
        throw "backend is listening on a non-loopback address: $($badBind.LocalAddress)"
    }
    Write-Host "[smoke] smoke PASS (health UP pid=$($resp.pid), loopback bound, process alive)"
} finally {
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $proc.WaitForExit(10000) | Out-Null
    }
    Start-Sleep -Seconds 2
    $residual = Get-Process -Name 'java' -ErrorAction SilentlyContinue | Where-Object { $_.Id -eq $proc.Id }
    if ($residual) {
        Write-Host '[smoke] FAIL: residual backend process remains'
        exit 1
    }
    Write-Host '[smoke] backend child terminated, no residual process'
}
