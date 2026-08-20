# D9 Final Attack F1/F7: ZIP entry whitelist/blacklist + exact secret scan (Boolean only,
# never prints the key or any fragment).
# Usage:
#   .\scripts\verify-package.ps1 -Zip <file> [-RepoRoot <dir>] [-ApiKey <secret>]
# Outputs machine-readable JSON to stdout and writes an evidence file when -EvidenceOut is set.
param(
    [string]$Zip,
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$ApiKey = $env:SUPPLYMIND_LLM_API_KEY,
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-zip.ps1')

if (-not (Test-Path -LiteralPath $Zip)) { throw "zip not found: $Zip" }

$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
$entries = @(Get-ZipEntries -ZipPath $Zip)
$report = [ordered]@{}
$report.zip = $Zip
$report.candidateCommit = (git -C $RepoRoot rev-parse --short HEAD 2>$null)
$report.builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
$report.entryCount = $entries.Count

$blacklistPrefixes = @(
    'node_modules',
    '.git',
    '__pycache__',
    'SupplyMindAI/app/web/node_modules'
)
$blacklistSuffixes = @(
    '.test.js', '.spec.js', '.test.ts', '.spec.ts',
    '.map', '.tsbuildinfo'
)
$forbiddenDataPrefixes = @(
    'SupplyMindAI/data/runtime/',
    'SupplyMindAI/data/raw/',
    'SupplyMindAI/data/staging/',
    'SupplyMindAI/data/quarantine/',
    'SupplyMindAI/data/processed/',
    'SupplyMindAI/data/warning/',
    'SupplyMindAI/data/report/',
    'SupplyMindAI/data/conflicts/'
)
$forbiddenDataFiles = @(
    '.supplymind-writer.lock',
    'time-state.json'
)

$violations = New-Object System.Collections.Generic.List[string]
$dataEntries = @()
foreach ($e in $entries) {
    $normalized = $e.Replace('\', '/').TrimStart('/')
    # dev-absolute-path check: any drive-letter or backslash-drive marker inside the name
    if ($normalized -match '^[A-Za-z]:' -or $normalized -match '(^|/)\.\./') {
        $violations.Add("DEV_PATH_OR_TRAVERSAL: $normalized")
    }
    foreach ($bp in $blacklistPrefixes) {
        if ($normalized.TrimStart('SupplyMindAI/').StartsWith($bp, [System.StringComparison]::OrdinalIgnoreCase)) {
            $violations.Add("BLACKLIST_PREFIX: $normalized")
        }
    }
    foreach ($bs in $blacklistSuffixes) {
        if ($normalized.EndsWith($bs, [System.StringComparison]::OrdinalIgnoreCase)) {
            $violations.Add("BLACKLIST_SUFFIX: $normalized")
        }
    }
    foreach ($fp in $forbiddenDataPrefixes) {
        if ($normalized.StartsWith($fp, [System.StringComparison]::OrdinalIgnoreCase)) {
            $violations.Add("FORBIDDEN_DATA: $normalized")
        }
    }
    foreach ($ff in $forbiddenDataFiles) {
        if ($normalized.EndsWith($ff, [System.StringComparison]::OrdinalIgnoreCase)) {
            $violations.Add("FORBIDDEN_DATA_FILE: $normalized")
        }
    }
    if ($normalized.StartsWith('SupplyMindAI/data/', [System.StringComparison]::OrdinalIgnoreCase) -and
        $normalized -ne 'SupplyMindAI/data/') {
        $dataEntries += $normalized
    }
}

# data/ must be empty in a clean release (no business data, no runtime state).
if ($dataEntries.Count -ne 0) {
    foreach ($d in $dataEntries) { $violations.Add("NON_EMPTY_DATA_DIR: $d") }
}

# app/web must ONLY contain the built assets (index.html + assets/*).
$webEntries = @()
foreach ($e in $entries) {
    $n = $e.Replace('\', '/')
    if ($n.StartsWith('SupplyMindAI/app/web/', [System.StringComparison]::OrdinalIgnoreCase) -and
        $n -ne 'SupplyMindAI/app/web/') {
        $webEntries += $n
    }
}
foreach ($w in $webEntries) {
    $rel = $w.Substring('SupplyMindAI/app/web/'.Length)
    if ($rel -notlike 'assets/*' -and $rel -ne 'index.html') {
        $violations.Add("UNEXPECTED_WEB_ENTRY: $w")
    }
}

$report.whitelistViolations = $violations

# ---- blacklist scan done; now exact secret scan (Boolean only) ----
$secretDetected = $false
$refKey = $ApiKey
if ($refKey) {
    $refKey = $refKey.Trim()
    if ($refKey.Length -ge 8) {
        # extract the zip to a random temp dir and scan text-ish files byte-wise
        $scanDir = Join-Path ([System.IO.Path]::GetTempPath()) "supplymind-secret-scan-$([guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Force -Path $scanDir | Out-Null
        try {
            Expand-Archive -LiteralPath $Zip -DestinationPath $scanDir -Force
            $candidateFiles = Get-ChildItem -LiteralPath $scanDir -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Length -le 5MB }
            foreach ($file in $candidateFiles) {
                $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
                try {
                    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
                } catch { continue }
                if ($text.Contains($refKey)) {
                    $secretDetected = $true
                    break
                }
            }
        } finally {
            if (Test-Path -LiteralPath $scanDir) { Remove-Item -LiteralPath $scanDir -Recurse -Force }
        }
    }
}
$report.secretInZip = $secretDetected   # Boolean only - never the key

# ---- conclusion ----
$pass = ($violations.Count -eq 0) -and (-not $secretDetected)
$report.result = if ($pass) { 'PASS' } else { 'FAIL' }

$json = $report | ConvertTo-Json -Depth 4
if ($EvidenceOut) {
    $dir = Split-Path -Parent $EvidenceOut
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Set-Content -LiteralPath $EvidenceOut -Value $json -Encoding UTF8
    $h = (Get-FileHash -LiteralPath $EvidenceOut -Algorithm SHA256).Hash
    Write-Host "[verify-package] evidence written: $EvidenceOut (sha256=$h)"
}
Write-Host $json
if (-not $pass) { exit 1 }