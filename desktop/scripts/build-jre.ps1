# D9-T02 build the bundled Java 17 JRE with jlink.
# Produces runtime/jre inside the portable root (frozen layout, DEC-023).
# The module set is conservative (Spring Boot + Spring AI + POI/comma-csv); the
# desktop smoke test verifies the resulting image actually runs the backend JAR.
# Usage: .\scripts\build-jre.ps1 [-JdkHome <path>] [-OutDir <path>]
param(
    [string]$JdkHome = 'D:\Dev\SDK\Java\jdk-17',
    [string]$OutDir = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI\runtime\jre')
)

$ErrorActionPreference = 'Stop'

$jlink = Join-Path $JdkHome 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $jlink)) {
    throw "jlink not found at $jlink (DEC-002: bundled JRE must be Java 17)"
}

# Verify the JDK really is 17 so the runtime matches the frozen baseline.
$javaVersion = (& cmd /c "`"$(Join-Path $JdkHome 'bin\java.exe')`" -version 2>&1") | Select-Object -First 1
if ($javaVersion -notmatch 'version "17\.') {
    throw "Expected a Java 17 JDK for the bundled JRE, got: $javaVersion"
}

if (Test-Path -LiteralPath $OutDir) {
    Remove-Item -LiteralPath $OutDir -Recurse -Force
}
$parentDir = Split-Path -Parent $OutDir
New-Item -ItemType Directory -Force -Path $parentDir | Out-Null

$modules = @(
    'java.base',
    'java.logging',
    'java.naming',
    'java.management',
    'java.net.http',
    'java.sql',
    'java.xml',
    'java.security.jgss',
    'java.desktop',
    'java.instrument',
    'jdk.unsupported'
)

& $jlink --module-path (Join-Path $JdkHome 'jmods') `
    --add-modules ($modules -join ',') `
    --strip-debug --no-header-files --no-man-pages `
    --output $OutDir

if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

$javaExe = Join-Path $OutDir 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "jlink produced no java.exe at $javaExe"
}
$builtVersion = (& cmd /c "`"$javaExe`" -version 2>&1") | Select-Object -First 1
Write-Host "[build-jre] bundled JRE OK: $builtVersion"
Write-Host "[build-jre] output: $OutDir"
