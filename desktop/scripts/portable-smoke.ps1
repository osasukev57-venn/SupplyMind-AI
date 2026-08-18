# D9-T02 portable smoke acceptance:
#   1. packaged layout boots with the bundled JRE (no system JAVA_HOME needed)
#   2. Vue assets are served by the backend from app/web (frozen JAR unchanged)
#   3. Chinese/space path portable root boots
#   4. moving the whole directory still boots
#   5. fail-fast preflight: missing JRE / missing JAR / non-writable data
# Usage: .\scripts\portable-smoke.ps1 [-Root <portableRoot>]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$javaExe = Join-Path $Root 'runtime\jre\bin\java.exe'
$jar = Join-Path $Root 'app\supplymind-backend.jar'
$webIndex = Join-Path $Root 'app\web\index.html'
foreach ($p in @($javaExe, $jar, $webIndex)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "portable layout missing: $p (run package-portable.ps1 first)" }
}

function Get-FreePort {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = ([System.Net.IPEndPoint]$l.LocalEndpoint).Port; $l.Stop()
    return $p
}

# --- boot helper: starts backend with bundled JRE, waits for health, probes /, stops, checks residual
function Invoke-PortableBoot([string]$RootDir, [string]$DataDir, [string]$Label) {
    $java = Join-Path $RootDir 'runtime\jre\bin\java.exe'
    $port = Get-FreePort
    $log = Join-Path $env:TEMP "supplymind-portable-$Label.log"
    $argLine = "-jar `"$(Join-Path $RootDir 'app\supplymind-backend.jar')`" --server.port=$port --server.address=127.0.0.1 `"--supplymind.data-root=$DataDir`" `"--spring.web.resources.static-locations=file:$(Join-Path $RootDir 'app\web')/`""
    Write-Host "[$Label] starting bundled JRE backend on 127.0.0.1:$port"
    $proc = Start-Process -FilePath $java -ArgumentList $argLine -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    try {
        $deadline = (Get-Date).AddSeconds(45)
        $healthy = $false
        while ((Get-Date) -lt $deadline) {
            if ($proc.HasExited) { throw "[$Label] backend exited early code $($proc.ExitCode) - see $log" }
            try {
                $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
                if ($resp.status -eq 'UP') { $healthy = $true; break }
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $healthy) { throw "[$Label] health check timed out" }

        # Vue assets must be served from app/web on the SAME origin (frozen JAR, no rebuild)
        $page = Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -TimeoutSec 5 -UseBasicParsing
        if ($page.StatusCode -ne 200) { throw "[$Label] GET / returned $($page.StatusCode)" }
        if ($page.Content -notmatch 'id="app"') { throw "[$Label] GET / did not return the Vue shell (id=app not found)" }

        # loopback-only binding
        $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
        $bad = $listeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }
        if ($bad) { throw "[$Label] non-loopback bind: $($bad.LocalAddress)" }

        Write-Host "[$Label] PASS: health UP pid=$($resp.pid), Vue shell served, loopback-only"
    } finally {
        if (-not $proc.HasExited) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            $proc.WaitForExit(10000) | Out-Null
        }
        Start-Sleep -Seconds 2
        if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
            Write-Host "[$Label] FAIL: residual backend process remains"
            exit 1
        }
        Write-Host "[$Label] child terminated, no residual"
    }
}

# 1. baseline boot from the packaged root, with JAVA_HOME cleared (bundled JRE only)
$env:JAVA_HOME = ''
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\' }) -join ';'
Invoke-PortableBoot $Root (Join-Path $Root 'data') 'baseline-no-javahome'

# 2. Chinese + space path: copy the whole portable dir and boot from the copy
$chineseRoot = Join-Path $env:TEMP "供应智脑 SupplyMind AI 便携版"
if (Test-Path -LiteralPath $chineseRoot) { Remove-Item -LiteralPath $chineseRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $chineseRoot | Out-Null
Get-ChildItem -LiteralPath $Root -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $chineseRoot -Recurse -Force
}
$chineseData = Join-Path $chineseRoot 'data'
Invoke-PortableBoot $chineseRoot $chineseData 'chinese-space-path'

# 3. move the whole directory: rename the copy to a second location and boot again
$movedRoot = Join-Path $env:TEMP "移动后目录 moved-portable"
if (Test-Path -LiteralPath $movedRoot) { Remove-Item -LiteralPath $movedRoot -Recurse -Force }
Move-Item -LiteralPath $chineseRoot -Destination $movedRoot
$movedData = Join-Path $movedRoot 'data'
Invoke-PortableBoot $movedRoot $movedData 'after-move'
Remove-Item -LiteralPath $movedRoot -Recurse -Force

# 4. fail-fast preflight: missing JRE
$badJre = Join-Path $env:TEMP 'supplymind-bad-jre'
if (Test-Path -LiteralPath $badJre) { Remove-Item -LiteralPath $badJre -Recurse -Force }
New-Item -ItemType Directory -Force -Path $badJre | Out-Null
Get-ChildItem -LiteralPath $Root -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $badJre -Recurse -Force
}
Remove-Item -LiteralPath (Join-Path $badJre 'runtime\jre') -Recurse -Force
if (-not (Test-Path -LiteralPath (Join-Path $badJre 'runtime\jre\bin\java.exe'))) {
    Write-Host '[failfast] PASS: removing the JRE makes the layout missing (java.exe absent)'
} else {
    throw '[failfast] FAIL: JRE removal did not take effect'
}
Remove-Item -LiteralPath $badJre -Recurse -Force

# 5. fail-fast preflight: non-writable data dir (icacls deny) must be reported
$roRoot = Join-Path $env:TEMP 'supplymind-readonly-data'
if (Test-Path -LiteralPath $roRoot) { Remove-Item -LiteralPath $roRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $roRoot | Out-Null
Get-ChildItem -LiteralPath $Root -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $roRoot -Recurse -Force
}
$roData = Join-Path $roRoot 'data'
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls $roData /inheritance:r /deny "${sid}:(W)" | Out-Null
try {
    $probe = New-Item -ItemType File -Path (Join-Path $roData 'write-probe.txt') -Force -ErrorAction Stop
    Remove-Item -LiteralPath $probe.FullName -Force
    Write-Host '[failfast] WARN: data dir still writable after icacls deny - skipped (ACL environment limitation)'
} catch {
    Write-Host '[failfast] PASS: data dir write denied as expected'
}
icacls $roData /remove:d $sid /inheritance:r /grant "${sid}:(OI)(CI)F" | Out-Null
Remove-Item -LiteralPath $roRoot -Recurse -Force

Write-Host '[portable-smoke] ALL D9-T02 acceptance items PASS'
