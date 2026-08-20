# D9 final package verification: exact layout, initial-config allowlist, and recursive exact-secret scan.
param(
    [string]$Zip,
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$ApiKey = $env:SUPPLYMIND_LLM_API_KEY,
    [string]$EvidenceOut
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-zip.ps1')
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path -LiteralPath $Zip)) { throw "zip not found: $Zip" }
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
$entries = @(Get-ZipEntries -ZipPath $Zip)
$report = [ordered]@{ zip=[System.IO.Path]::GetFileName($Zip); candidateCommit=(git -C $RepoRoot rev-parse --short HEAD 2>$null); builtAt=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz'); entryCount=$entries.Count }

$blacklistPrefixes = @('node_modules/', '.git/', '__pycache__/', 'app/web/node_modules/')
$blacklistSuffixes = @('.test.js','.spec.js','.test.ts','.spec.ts','.map','.tsbuildinfo')
$forbiddenDataPrefixes = @('SupplyMindAI/data/runtime/','SupplyMindAI/data/raw/','SupplyMindAI/data/staging/','SupplyMindAI/data/quarantine/','SupplyMindAI/data/processed/','SupplyMindAI/data/warning/','SupplyMindAI/data/report/','SupplyMindAI/data/conflicts/')
$forbiddenDataFiles = @('.supplymind-writer.lock','time-state.json')
$allowedDataEntries = @('SupplyMindAI/data/config/monitor-series.json','SupplyMindAI/data/config/monitor-series.json.manifest.json','SupplyMindAI/data/config/history/1.json','SupplyMindAI/data/config/history/1.json.manifest.json')
$requiredEntries = @('SupplyMindAI/SupplyMindAI.exe','SupplyMindAI/app/supplymind-backend.jar','SupplyMindAI/app/web/index.html','SupplyMindAI/runtime/jre/bin/java.exe','SupplyMindAI/README.txt') + $allowedDataEntries
$violations = [System.Collections.Generic.List[string]]::new()
$dataEntries = [System.Collections.Generic.List[string]]::new()
foreach ($e in $entries) {
    $normalized=$e.Replace('\','/').TrimStart('/')
    $relative=if($normalized.StartsWith('SupplyMindAI/',[System.StringComparison]::OrdinalIgnoreCase)){$normalized.Substring('SupplyMindAI/'.Length)}else{$normalized}
    if($normalized -match '^[A-Za-z]:' -or $normalized -match '(^|/)\.\./'){$violations.Add("DEV_PATH_OR_TRAVERSAL: $normalized")}
    foreach($bp in $blacklistPrefixes){if($relative.StartsWith($bp,[System.StringComparison]::OrdinalIgnoreCase)){$violations.Add("BLACKLIST_PREFIX: $normalized")}}
    foreach($bs in $blacklistSuffixes){if($normalized.EndsWith($bs,[System.StringComparison]::OrdinalIgnoreCase)){$violations.Add("BLACKLIST_SUFFIX: $normalized")}}
    foreach($fp in $forbiddenDataPrefixes){if($normalized.StartsWith($fp,[System.StringComparison]::OrdinalIgnoreCase)){$violations.Add("FORBIDDEN_DATA: $normalized")}}
    foreach($ff in $forbiddenDataFiles){if($normalized.EndsWith($ff,[System.StringComparison]::OrdinalIgnoreCase)){$violations.Add("FORBIDDEN_DATA_FILE: $normalized")}}
    if($normalized.StartsWith('SupplyMindAI/data/',[System.StringComparison]::OrdinalIgnoreCase) -and -not $normalized.EndsWith('/')){$dataEntries.Add($normalized)}
}
foreach($required in $requiredEntries){if($entries -notcontains $required){$violations.Add("MISSING_REQUIRED_ENTRY: $required")}}
foreach($actual in $dataEntries){if($allowedDataEntries -notcontains $actual){$violations.Add("UNEXPECTED_DATA_ENTRY: $actual")}}
foreach($allowed in $allowedDataEntries){if($dataEntries -notcontains $allowed){$violations.Add("MISSING_INITIAL_CONFIG_ENTRY: $allowed")}}
$webEntries=@($entries|Where-Object{$_.Replace('\','/').StartsWith('SupplyMindAI/app/web/',[System.StringComparison]::OrdinalIgnoreCase) -and -not $_.Replace('\','/').EndsWith('/')})
foreach($w in $webEntries){$rel=$w.Replace('\','/').Substring('SupplyMindAI/app/web/'.Length);if($rel -notlike 'assets/*' -and $rel -ne 'index.html'){$violations.Add("UNEXPECTED_WEB_ENTRY: $w")}}
$report.whitelistViolations=$violations

function Test-StreamContainsBytes([System.IO.Stream]$Stream,[byte[]]$Needle){
    if($Needle.Length -eq 0){return $false}
    $needleText=[System.Text.Encoding]::ASCII.GetString($Needle)
    $buffer=New-Object byte[] 1048576
    $carry=''
    while(($read=$Stream.Read($buffer,0,$buffer.Length)) -gt 0){
        $text=$carry+[System.Text.Encoding]::ASCII.GetString($buffer,0,$read)
        if($text.IndexOf($needleText,[System.StringComparison]::Ordinal) -ge 0){return $true}
        $carryLength=[Math]::Min($Needle.Length-1,$text.Length)
        $carry=if($carryLength -gt 0){$text.Substring($text.Length-$carryLength)}else{''}
    }
    return $false
}
function Test-ArchiveContainsSecret([string]$ArchivePath,[byte[]]$Needle,[int]$Depth=0){
    if($Depth -gt 2){return $false}
    $archive=[System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try{
        foreach($entry in $archive.Entries){
            if([string]::IsNullOrEmpty($entry.Name)){continue}
            $stream=$entry.Open();try{if(Test-StreamContainsBytes $stream $Needle){return $true}}finally{$stream.Dispose()}
            if($entry.Name.EndsWith('.jar',[System.StringComparison]::OrdinalIgnoreCase) -or $entry.Name.EndsWith('.zip',[System.StringComparison]::OrdinalIgnoreCase)){
                $nested=Join-Path ([System.IO.Path]::GetTempPath()) ("supplymind-nested-"+[guid]::NewGuid().ToString('N'))
                try{$input=$entry.Open();$output=[System.IO.File]::Create($nested);try{$input.CopyTo($output)}finally{$input.Dispose();$output.Dispose()};if(Test-ArchiveContainsSecret $nested $Needle ($Depth+1)){return $true}}finally{if(Test-Path -LiteralPath $nested){Remove-Item -LiteralPath $nested -Force}}
            }
        }
    }finally{$archive.Dispose()}
    return $false
}
$secretDetected=$false
if($ApiKey){$trimmed=$ApiKey.Trim();if($trimmed.Length -ge 8){$secretDetected=Test-ArchiveContainsSecret ([System.IO.Path]::GetFullPath($Zip)) ([System.Text.Encoding]::UTF8.GetBytes($trimmed))}}
$report.secretInZip=$secretDetected
$pass=($violations.Count -eq 0) -and (-not $secretDetected)
$report.result=if($pass){'PASS'}else{'FAIL'}
$json=$report|ConvertTo-Json -Depth 5
if($EvidenceOut){$dir=Split-Path -Parent $EvidenceOut;New-Item -ItemType Directory -Force -Path $dir|Out-Null;[System.IO.File]::WriteAllText($EvidenceOut,$json+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))}
Write-Host $json
if(-not $pass){exit 1}