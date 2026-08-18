# D9-T02 verify the bundled JRE can actually boot the backend JAR (module set check).
param(
    [string]$PortableRoot = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI'),
    [string]$Jar = (Join-Path $PSScriptRoot '..\..\backend\target\supplymind-backend-0.1.0-SNAPSHOT.jar'),
    [string]$DataRoot = (Join-Path $PSScriptRoot '..\..\target\jre-boot-check-data')
)
$ErrorActionPreference = 'Stop'
$javaExe = Join-Path $PortableRoot 'runtime\jre\bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { throw "bundled JRE missing: $javaExe" }
if (-not (Test-Path -LiteralPath $Jar)) { throw "JAR missing: $Jar" }
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port; $listener.Stop()

$logFile = Join-Path (Split-Path $DataRoot) 'jre-boot-check.log'
$argLine = "-jar `"$Jar`" --server.port=$port --server.address=127.0.0.1 `"--supplymind.data-root=$DataRoot`""
$proc = Start-Process -FilePath $javaExe -ArgumentList $argLine -PassThru -NoNewWindow -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"

try {
    $deadline = (Get-Date).AddSeconds(45)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "backend exited early with code $($proc.ExitCode) - see $logFile"
        }
        try {
            $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
            if ($resp.status -eq 'UP') { $healthy = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { throw 'health check timed out (bundled JRE module set may be incomplete)' }
    Write-Host "[jre-check] PASS: bundled JRE boots backend, health UP pid=$($resp.pid) on port $port"
} finally {
    if (-not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $proc.WaitForExit(10000) | Out-Null
    }
    Start-Sleep -Seconds 2
    if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
        Write-Host '[jre-check] FAIL: residual process remains'
        exit 1
    }
    Write-Host '[jre-check] child terminated cleanly'
}
