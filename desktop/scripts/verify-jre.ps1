# D9 Final Attack F2: bundled JRE verification.
# Asserts:
#   1. bundled java.exe reports Java 17
#   2. java.net.http + jdk.crypto.ec modules present (Cloud HTTPS/TLS boundary)
#   3. the bundled JRE boots the backend JAR (health UP)
#   4. NON-BILLING TLS handshake to the official Bailian HTTPS origin (no API key,
#      no chat completion sent) proves the bundled JRE can establish the Cloud HTTPS path.
# Usage:
#   .\scripts\verify-jre.ps1 [-PortableRoot <dir>] [-Jar <path>] [-DataRoot <path>]
#   .\scripts\verify-jre.ps1 -JreOnly          # module + TLS checks only (no backend boot)
param(
    [string]$PortableRoot = (Join-Path $PSScriptRoot '..\..\target\package-staging'),
    [string]$Jar = (Join-Path $PSScriptRoot '..\..\backend\target\supplymind-backend-0.1.0-SNAPSHOT.jar'),
    [string]$DataRoot = (Join-Path $PSScriptRoot '..\..\target\jre-boot-check-data'),
    [string]$EvidenceOut,
    [switch]$JreOnly
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# locate the newest clean-package staging JRE (preferred) or the legacy portable one
$jreRoot = $null
if (Test-Path (Join-Path $PortableRoot 'SupplyMindAI\runtime\jre\bin\java.exe')) {
    $jreRoot = Join-Path $PortableRoot 'SupplyMindAI\runtime\jre'
} elseif (Test-Path (Join-Path $PortableRoot 'runtime\jre\bin\java.exe')) {
    $jreRoot = Join-Path $PortableRoot 'runtime\jre'
} else {
    $candidates = Get-ChildItem $PortableRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    foreach ($c in $candidates) {
        $try = Join-Path $c.FullName 'SupplyMindAI\runtime\jre'
        if (Test-Path (Join-Path $try 'bin\java.exe')) { $jreRoot = $try; break }
    }
}
if (-not $jreRoot) { throw "bundled JRE not found under $PortableRoot (run package-clean.ps1 first)" }
$javaExe = Join-Path $jreRoot 'bin\java.exe'

$report = [ordered]@{}
$report.phase = 'verify-jre'
$report.candidateCommit = (git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null)
$report.builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
$report.bundledJavaExe = 'runtime/jre/bin/java.exe'

# 1. Java = 17
$verLine = (& cmd /c "`"$javaExe`" -version 2>&1") | Select-Object -First 1
Write-Host "[jre-verify] java: $verLine"
if ($verLine -notmatch 'version "17\.') { throw "bundled JRE is not Java 17: $verLine" }
$report.javaVersion = $verLine

# 2. modules present: java.net.http + jdk.crypto.ec
# NOTE: the bundled image is a JRE (no javac). Probes are compiled with the system JDK 17
# (build-time tool) and EXECUTED with the bundled JRE java - which is what the desktop
# app does at runtime.
$buildJavac = 'D:\Dev\SDK\Java\jdk-17\bin\javac.exe'
$moduleProbe = @'
public class ModuleProbe {
  public static void main(String[] a) {
    System.out.println("http=" + ModuleLayer.boot().findModule("java.net.http").isPresent());
    System.out.println("ec=" + ModuleLayer.boot().findModule("jdk.crypto.ec").isPresent());
  }
}
'@
$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "supplymind-modprobe-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
Set-Content -LiteralPath (Join-Path $tmpDir 'ModuleProbe.java') -Value $moduleProbe -Encoding ASCII
try {
    & cmd /c "`"$buildJavac`" -d `"$tmpDir`" `"$tmpDir\ModuleProbe.java`" 2>&1" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'compiling ModuleProbe failed' }
    $hasHttp = 'false'
    $hasEc = 'false'
    (& cmd /c "`"$javaExe`" -cp `"$tmpDir`" ModuleProbe 2>&1") | ForEach-Object {
        if ($_ -match '^http=(true|false)$') { $hasHttp = $Matches[1] }
        if ($_ -match '^ec=(true|false)$') { $hasEc = $Matches[1] }
    }
} finally {
    if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
}
Write-Host "[jre-verify] java.net.http present=$hasHttp ; jdk.crypto.ec present=$hasEc"
if ($hasHttp -ne 'true') { throw 'java.net.http module missing from bundled JRE' }
if ($hasEc -ne 'true') { throw 'jdk.crypto.ec module missing from bundled JRE (Cloud TLS boundary)' }
$report.javaNetHttp = $hasHttp
$report.jdkCryptoEc = $hasEc

# 3. NON-BILLING TLS handshake to the official Bailian origin (no key, no completion)
$tlsProbe = @'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
public class TlsProbe {
  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
    HttpRequest req = HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .timeout(java.time.Duration.ofSeconds(20))
        .build();
    try {
      HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
      System.out.println("TLS_STATUS=" + resp.statusCode());
    } catch (Exception e) {
      System.out.println("TLS_ERROR=" + e.getClass().getSimpleName());
      e.printStackTrace(System.out);
    }
  }
}
'@
$tlsDir = Join-Path ([System.IO.Path]::GetTempPath()) "supplymind-tlsprobe-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $tlsDir | Out-Null
Set-Content -LiteralPath (Join-Path $tlsDir 'TlsProbe.java') -Value $tlsProbe -Encoding ASCII
$tlsStatus = 'NOT_ATTEMPTED'
try {
    Write-Host '[jre-verify] non-billing TLS handshake to https://dashscope.aliyuncs.com/compatible-mode/v1 ...'
    & cmd /c "`"$buildJavac`" -d `"$tlsDir`" `"$tlsDir\TlsProbe.java`" 2>&1" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'compiling TlsProbe failed' }
    $tlsOut = (& cmd /c "`"$javaExe`" -cp `"$tlsDir`" TlsProbe 2>&1")
    $tlsOut | Out-Host
    $tlsLine = $tlsOut | Where-Object { $_ -match '^TLS_(STATUS|ERROR)=' } | Select-Object -Last 1
    if ($tlsLine -match 'TLS_STATUS=(\d+)') {
        $tlsStatus = "STATUS=$($Matches[1])"
    } elseif ($tlsLine -match 'TLS_ERROR=([A-Za-z]+)') {
        $tlsStatus = "ERROR=$($Matches[1])"
    }
} finally {
    if (Test-Path $tlsDir) { Remove-Item $tlsDir -Recurse -Force }
}
# Proof of Cloud TLS boundary: a real HTTP status from the Bailian origin means the TLS
# handshake + HTTPS connectivity completed (no key, no completion payload was sent).
# DNS/connect failures or TLS errors are not proof and surface as WARN (network gate).
if ($tlsStatus -match '^STATUS=') {
    Write-Host "[jre-verify] PASS: bundled JRE completed HTTPS/TLS handshake with the Bailian origin ($tlsStatus)"
} else {
    throw "bundled JRE did not complete the Bailian TLS handshake: $tlsStatus"
}
$report.bailianTls = $tlsStatus

if ($JreOnly) {
    $report.result = 'PASS'
    Write-Host '[jre-verify] PASS: JRE module + TLS checks (backend boot skipped)'
} else {
    # 4. backend boot with the bundled JRE. Always use a UNIQUE fresh DataRoot so stale
    #    state from a previous run can never poison the boot check.
    if (-not (Test-Path -LiteralPath $Jar)) { throw "JAR missing: $Jar" }
    $DataRoot = Join-Path ([System.IO.Path]::GetTempPath()) "supplymind-jre-boot-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port; $listener.Stop()
    $logFile = Join-Path $DataRoot 'jre-boot-check.log'
    $argLine = "-jar `"$Jar`" --server.port=$port --server.address=127.0.0.1 `"--supplymind.data-root=$DataRoot`""
    $proc = Start-Process -FilePath $javaExe -ArgumentList $argLine -PassThru -NoNewWindow -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"
    try {
        $deadline = (Get-Date).AddSeconds(45)
        $healthy = $false
        while ((Get-Date) -lt $deadline) {
            if ($proc.HasExited) { throw "backend exited early with code $($proc.ExitCode) - see $logFile" }
            try {
                $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2
                if ($resp.status -eq 'UP') { $healthy = $true; break }
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $healthy) { throw 'health check timed out (bundled JRE module set may be incomplete)' }
        Write-Host "[jre-verify] PASS: bundled JRE boots backend, health UP pid=$($resp.pid) on port $port"
        $report.backendBoot = 'PASS'
    } finally {
        if (-not $proc.HasExited) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            $proc.WaitForExit(10000) | Out-Null
        }
        Start-Sleep -Seconds 2
        if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
            Write-Host '[jre-verify] FAIL: residual process remains'
            $report.result = 'FAIL'
        } else {
            $report.result = 'PASS'
        }
        Write-Host '[jre-verify] child terminated cleanly'
    }
}

if ($EvidenceOut) {
    $dir = Split-Path -Parent $EvidenceOut
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $json = $report | ConvertTo-Json -Depth 4
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    $h = (Get-FileHash -LiteralPath $EvidenceOut -Algorithm SHA256).Hash
    Write-Host "[jre-verify] evidence: $EvidenceOut (sha256=$h)"
}
$report | ConvertTo-Json -Depth 4 | Out-Host
if ($report.result -ne 'PASS') { exit 1 }