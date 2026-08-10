# capture-at-src-002-evidence.ps1
#
# DEC-056 Finding C evidence pipeline: turn the real gated surefire runner XML plus the
# AT runtime facts into the tracked summary JSON. No runner count is hardcoded anywhere:
# tests/failures/errors/skipped and the runner XML SHA-256 are parsed/computed from the
# actual surefire XML, and the result is derived from those counts.
#
# Usage (after a successful gated run, before any further full regression):
#   powershell -ExecutionPolicy Bypass -File capture-at-src-002-evidence.ps1 `
#     -EvidenceDir "D:\Dev\Projects\SupplyMind AI\docs\evidence\AT-SRC-002" `
#     -SurefireXml "D:\Dev\Projects\SupplyMind AI\backend\target\surefire-reports\TEST-com.supplymind.acceptance.AtSrc002AcceptanceTest.xml" `
#     -RuntimeFactsJson "D:\Dev\Projects\SupplyMind AI\docs\evidence\AT-SRC-002\at-src-002-runtime-facts.json" `
#     -RealGateValue "true"

param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$SurefireXml,
    [Parameter(Mandatory = $true)][string]$RuntimeFactsJson,
    [Parameter(Mandatory = $true)][string]$RealGateValue
)

$ErrorActionPreference = "Stop"

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

[xml]$suite = Get-Content -LiteralPath $SurefireXml -Raw
$tests = [int]$suite.testsuite.tests
$failures = [int]$suite.testsuite.failures
$errors = [int]$suite.testsuite.errors
$skipped = [int]$suite.testsuite.skipped
$suiteName = [string]$suite.testsuite.name

$runnerEvidenceRef = "TEST-" + $suiteName + ".xml"
$trackedXml = Join-Path $EvidenceDir $runnerEvidenceRef
Copy-Item -LiteralPath $SurefireXml -Destination $trackedXml -Force
$runnerEvidenceSha256 = (Get-FileHash -LiteralPath $trackedXml -Algorithm SHA256).Hash.ToLowerInvariant()

if ($failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0 -and $tests -gt 0) {
    $result = "PASS"
} else {
    $result = "FAIL"
}

$facts = Get-Content -LiteralPath $RuntimeFactsJson -Raw | ConvertFrom-Json
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
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$jsonText = $facts | ConvertTo-Json -Depth 12
[System.IO.File]::WriteAllText($summaryJson, $jsonText, $utf8NoBom)

Write-Output ("AT_SRC_002_CAPTURE runnerEvidenceRef=" + $runnerEvidenceRef)
Write-Output ("AT_SRC_002_CAPTURE runnerEvidenceSha256=" + $runnerEvidenceSha256)
Write-Output ("AT_SRC_002_CAPTURE tests=" + $tests + " failures=" + $failures + " errors=" + $errors + " skipped=" + $skipped)
Write-Output ("AT_SRC_002_CAPTURE result=" + $result + " (derived from actual runner counts)")
Write-Output ("AT_SRC_002_CAPTURE summary=" + $summaryJson)
