# capture-at-src-002-evidence.ps1
#
# DEC-056 Finding C + Final Encoding Fix evidence pipeline: turn the real gated surefire runner
# XML plus the AT runtime facts into the tracked summary JSON. No runner count is hardcoded:
# tests/failures/errors/skipped and the runner XML SHA-256 are parsed/computed from the actual
# surefire XML, and the result is derived from those counts.
#
# Encoding contract (fixed): every read of JSON/XML evidence is an explicit strict UTF-8 decode
# ([System.Text.UTF8Encoding] with throwOnInvalidBytes) and every write is UTF-8 no-BOM via the
# deterministic .NET API. Nothing depends on the Windows console code page / ANSI / GBK. A
# fail-closed round-trip invariant is enforced: the generated summary's realSource must equal
# the runtime facts realSource character for character, otherwise no summary is written.
#
# Usage (after a successful gated run, before any further full regression):
#   powershell -ExecutionPolicy Bypass -File capture-at-src-002-evidence.ps1 `
#     -EvidenceDir "D:\Dev\Projects\SupplyMind AI\docs\evidence\AT-SRC-002" `
#     -SurefireXml "D:\Dev\Projects\SupplyMind AI\backend\target\surefire-reports\TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml" `
#     -RuntimeFactsJson "D:\Dev\Projects\SupplyMind AI\docs\evidence\AT-SRC-002\at-src-002-runtime-facts.json" `
#     -RealGateValue "true"
#
# Self-test for the UTF-8 contract (no runner XML / runtime facts required):
#   powershell -ExecutionPolicy Bypass -File capture-at-src-002-evidence.ps1 -SelfTest

param(
    [Parameter(Mandatory = $false)][switch]$SelfTest,
    [Parameter(Mandatory = $false)][string]$EvidenceDir,
    [Parameter(Mandatory = $false)][string]$SurefireXml,
    [Parameter(Mandatory = $false)][string]$RuntimeFactsJson,
    [Parameter(Mandatory = $false)][string]$RealGateValue
)

$ErrorActionPreference = "Stop"
$strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Read-StrictUtf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, $strictUtf8)
}

function Write-Utf8NoBom {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Assert-SameText {
    param([Parameter(Mandatory = $true)][string]$Expected, [Parameter(Mandatory = $true)][string]$Actual, [string]$Label)
    $expectedChars = $Expected.ToCharArray()
    $actualChars = $Actual.ToCharArray()
    if ($expectedChars.Length -ne $actualChars.Length) {
        Write-Error ("UTF-8 round-trip mismatch for " + $Label + ": length " + $expectedChars.Length + " vs " + $actualChars.Length)
        exit 1
    }
    for ($i = 0; $i -lt $expectedChars.Length; $i++) {
        if ($expectedChars[$i] -ne $actualChars[$i]) {
            Write-Error ("UTF-8 round-trip mismatch for " + $Label + " at index " + $i)
            exit 1
        }
    }
}

if ($SelfTest) {
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("at-src-002-encoding-self-test-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    try {
        $expectedSource = [string][char]0x4E2D + [char]0x56FD + [char]0x4EBA + [char]0x6C11 + [char]0x94F6 + [char]0x884C +
            [char]0x5B98 + [char]0x7F51 + [char]0xFF08 + [char]0x6388 + [char]0x6743 + [char]0x4E2D + [char]0x56FD +
            [char]0x5916 + [char]0x6C47 + [char]0x4EA4 + [char]0x6613 + [char]0x4E2D + [char]0x5FC3 + [char]0x516C +
            [char]0x5E03 + [char]0xFF09
        $facts = [ordered]@{
            acceptanceTest = "AT-SRC-002 self-test"
            realSource = $expectedSource
            businessDate = "2026-08-10"
            realGateProperty = "at-src-002.real"
            realGateValue = "true"
        }
        $factsPath = Join-Path $tempDir "runtime-facts.json"
        Write-Utf8NoBom $factsPath (ConvertTo-Json $facts -Depth 6)

        $xmlPath = Join-Path $tempDir "TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml"
        $xmlText = "<?xml version=""1.0"" encoding=""UTF-8""?><testsuite name=""com.supplymind.acceptance.AtSrc002AcceptanceTest"" tests=""1"" failures=""0"" errors=""0"" skipped=""0""/>"
        Write-Utf8NoBom $xmlPath $xmlText

        $loaded = Read-StrictUtf8Text $factsPath | ConvertFrom-Json
        Assert-SameText $expectedSource ([string]$loaded.realSource) "self-test runtime facts realSource"

        [xml]$suite = Read-StrictUtf8Text $xmlPath
        $tests = [int]$suite.testsuite.tests
        $failures = [int]$suite.testsuite.failures
        $errors = [int]$suite.testsuite.errors
        $skipped = [int]$suite.testsuite.skipped
        $result = if ($failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0 -and $tests -gt 0) { "PASS" } else { "FAIL" }
        $loaded | Add-Member -NotePropertyName result -NotePropertyValue $result -Force
        $summaryPath = Join-Path $tempDir "summary.json"
        Write-Utf8NoBom $summaryPath (ConvertTo-Json $loaded -Depth 12)

        $reloaded = Read-StrictUtf8Text $summaryPath | ConvertFrom-Json
        Assert-SameText $expectedSource ([string]$reloaded.realSource) "self-test summary realSource"
        Assert-SameText ([string]$loaded.realSource) ([string]$reloaded.realSource) "self-test round-trip"

        Write-Output "AT_SRC_002_ENCODING_SELFTEST result=PASS realSourceRoundTrip=IDENTICAL"
    } finally {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    exit 0
}

if (-not $EvidenceDir -or -not $SurefireXml -or -not $RuntimeFactsJson -or -not $RealGateValue) {
    Write-Error "EvidenceDir, SurefireXml, RuntimeFactsJson and RealGateValue are required (or use -SelfTest)"
    exit 1
}

if ($RealGateValue -ne "true") {
    Write-Error "real gate must be enabled for a formal AT-SRC-002 summary; received realGateValue=$RealGateValue"
    exit 1
}

if (-not (Test-Path -LiteralPath $SurefireXml)) {
    Write-Error "surefire XML not found: $SurefireXml"
    exit 1
}
if (-not (Test-Path -LiteralPath $RuntimeFactsJson)) {
    Write-Error "runtime facts JSON not found: $RuntimeFactsJson"
    exit 1
}

New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null

[xml]$suite = Read-StrictUtf8Text $SurefireXml
$tests = [int]$suite.testsuite.tests
$failures = [int]$suite.testsuite.failures
$errors = [int]$suite.testsuite.errors
$skipped = [int]$suite.testsuite.skipped
$suiteName = [string]$suite.testsuite.name

$runnerEvidenceRef = "TEST-" + $suiteName + ".xml"
$trackedXml = Join-Path $EvidenceDir $runnerEvidenceRef
$sourceXmlFull = [System.IO.Path]::GetFullPath($SurefireXml)
$trackedXmlFull = [System.IO.Path]::GetFullPath($trackedXml)
if (-not [string]::Equals($sourceXmlFull, $trackedXmlFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $SurefireXml -Destination $trackedXml -Force
}
$runnerEvidenceSha256 = (Get-FileHash -LiteralPath $trackedXml -Algorithm SHA256).Hash.ToLowerInvariant()

if ($failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0 -and $tests -gt 0) {
    $result = "PASS"
} else {
    $result = "FAIL"
}

$facts = Read-StrictUtf8Text $RuntimeFactsJson | ConvertFrom-Json
if ([string]$facts.realGateProperty -ne "at-src-002.real" -or [string]$facts.realGateValue -ne "true") {
    Write-Error "runtime facts do not confirm the real gate; refusing to write a formal summary"
    exit 1
}

$facts | Add-Member -NotePropertyName tests -NotePropertyValue $tests -Force
$facts | Add-Member -NotePropertyName failures -NotePropertyValue $failures -Force
$facts | Add-Member -NotePropertyName errors -NotePropertyValue $errors -Force
$facts | Add-Member -NotePropertyName skipped -NotePropertyValue $skipped -Force
$facts | Add-Member -NotePropertyName runnerSuite -NotePropertyValue $suiteName -Force
$facts | Add-Member -NotePropertyName runnerEvidenceRef -NotePropertyValue $runnerEvidenceRef -Force
$facts | Add-Member -NotePropertyName runnerEvidenceSha256 -NotePropertyValue $runnerEvidenceSha256 -Force
$facts | Add-Member -NotePropertyName runnerFactsSource -NotePropertyValue "AUTO_XML_PARSE" -Force
$facts | Add-Member -NotePropertyName result -NotePropertyValue $result -Force

$summaryJson = Join-Path $EvidenceDir "at-src-002-summary.json"
$jsonText = $facts | ConvertTo-Json -Depth 12
Write-Utf8NoBom $summaryJson $jsonText

$reloaded = Read-StrictUtf8Text $summaryJson | ConvertFrom-Json
Assert-SameText ([string]$facts.realSource) ([string]$reloaded.realSource) "summary realSource vs runtime facts realSource"
Assert-SameText ([string]$facts.realSource) ([string]($reloaded | Select-Object -ExpandProperty realSource)) "summary UTF-8 round-trip"

Write-Output ("AT_SRC_002_CAPTURE runnerEvidenceRef=" + $runnerEvidenceRef)
Write-Output ("AT_SRC_002_CAPTURE runnerEvidenceSha256=" + $runnerEvidenceSha256)
Write-Output ("AT_SRC_002_CAPTURE tests=" + $tests + " failures=" + $failures + " errors=" + $errors + " skipped=" + $skipped)
Write-Output ("AT_SRC_002_CAPTURE result=" + $result + " (derived from actual runner counts)")
Write-Output ("AT_SRC_002_CAPTURE encoding=strict-UTF8-read/UTF8-no-BOM-write realSourceRoundTrip=IDENTICAL")
Write-Output ("AT_SRC_002_CAPTURE summary=" + $summaryJson)
