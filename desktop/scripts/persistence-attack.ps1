# D9 Final Attack F5: immutable raw/timeline persistence across restart and directory move.
param([string]$Zip,[string]$ExtractBase=(Join-Path $env:TEMP 'supplymind-f5'),[string]$EvidenceOut)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$env:JAVA_HOME=''
$env:PATH=($env:PATH -split ';'|Where-Object{$_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven'}) -join ';'
foreach($name in @('SUPPLYMIND_LLM_ENABLED','SUPPLYMIND_LLM_API_KEY','SUPPLYMIND_LLM_BASE_URL','SUPPLYMIND_LLM_MODEL','SUPPLYMIND_LLM_COMPLETIONS_PATH','SUPPLYMIND_LLM_TIMEOUT','SUPPLYMIND_LLM_PROVIDER')){Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue}
function Get-AppJavaProcs([string]$RootPath){
    Get-Process -Name java -ErrorAction SilentlyContinue|Where-Object{try{$_.Path -like "$RootPath*"}catch{$false}}|ForEach-Object{
        $cmdline=(Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine
        if($cmdline -match [regex]::Escape((Join-Path $RootPath 'app\supplymind-backend.jar'))){[pscustomobject]@{Id=$_.Id;Path=$_.Path;CmdLine=$cmdline}}
    }
}
function Start-App([string]$RootPath){
    $urlFile=Join-Path $RootPath 'logs\backend-url.txt';Remove-Item $urlFile -Force -ErrorAction SilentlyContinue
    $p=Start-Process -FilePath (Join-Path $RootPath 'SupplyMindAI.exe') -PassThru
    $port=$null;$deadline=(Get-Date).AddSeconds(90)
    while((Get-Date)-lt $deadline){if(Test-Path $urlFile){$c=(Get-Content $urlFile -Raw).Trim();if($c -match 'http://127\.0\.0\.1:(\d+)/?'){$port=[int]$Matches[1];break}};Start-Sleep -Seconds 1}
    if(-not $port){throw 'backend-url never appeared'}
    $healthy=$false;$deadline=(Get-Date).AddSeconds(45)
    while((Get-Date)-lt $deadline){try{$h=Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2;if($h.status -eq 'UP'){$healthy=$true;break}}catch{Start-Sleep -Milliseconds 500}}
    if(-not $healthy){Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue;throw 'health failed'}
    [pscustomobject]@{ExeProc=$p;Port=$port;Java=@(Get-AppJavaProcs $RootPath)}
}
function Stop-App([string]$RootPath,$App){
    if($App -and $App.ExeProc -and -not $App.ExeProc.HasExited){Stop-Process -Id $App.ExeProc.Id -Force -ErrorAction SilentlyContinue;$App.ExeProc.WaitForExit(15000)|Out-Null}
    $deadline=(Get-Date).AddSeconds(30)
    while((Get-Date)-lt $deadline){$java=@(Get-AppJavaProcs $RootPath);if($java.Count -eq 0){return};Start-Sleep -Milliseconds 500}
    @(Get-AppJavaProcs $RootPath)|ForEach-Object{Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue}
    Start-Sleep -Seconds 2
}
function Resolve-DataRef([string]$RootPath,[string]$Ref){
    if([string]::IsNullOrWhiteSpace($Ref) -or $Ref.Contains('..') -or [System.IO.Path]::IsPathRooted($Ref)){throw "illegal data ref: $Ref"}
    $dataRoot=[System.IO.Path]::GetFullPath((Join-Path $RootPath 'data')).TrimEnd('\')
    $resolved=[System.IO.Path]::GetFullPath((Join-Path $dataRoot ($Ref.Replace('/','\'))))
    if(-not $resolved.StartsWith($dataRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw "data ref escaped root: $Ref"}
    $resolved
}
function Get-VerifiedSnapshot([string]$RootPath,[string]$Ref){
    $dataPath=Resolve-DataRef $RootPath $Ref
    $manifestPath=$dataPath+'.manifest.json'
    if(-not(Test-Path -LiteralPath $dataPath) -or -not(Test-Path -LiteralPath $manifestPath)){throw "missing data or manifest for $Ref"}
    $bytes=[System.IO.File]::ReadAllBytes($dataPath)
    $sha=(Get-FileHash -LiteralPath $dataPath -Algorithm SHA256).Hash
    $manifestText=[System.IO.File]::ReadAllText($manifestPath,[System.Text.Encoding]::UTF8)
    $manifest=$manifestText|ConvertFrom-Json
    $valid=$manifest.fileName -eq [System.IO.Path]::GetFileName($dataPath) -and
        $manifest.fileSha256 -eq $sha -and [int64]$manifest.byteLength -eq $bytes.Length -and
        $manifest.commitState -eq 'COMMITTED'
    [pscustomobject]@{ref=$Ref;dataSha256=$sha;dataLength=$bytes.Length;manifestSha256=(Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash;manifestValid=$valid}
}
function Get-PersistenceSnapshot([string]$RootPath,$Response){
    $refs=@($Response.rawRef,$Response.timelineRef,'config/monitor-series.json','config/history/1.json')
    @($refs|ForEach-Object{Get-VerifiedSnapshot $RootPath $_})
}
function Test-SnapshotEqual($Expected,$Actual){
    if($Expected.Count -ne $Actual.Count){return $false}
    foreach($e in $Expected){$a=$Actual|Where-Object{$_.ref -eq $e.ref}|Select-Object -First 1;if(-not $a -or -not $a.manifestValid -or $a.dataSha256 -ne $e.dataSha256 -or $a.dataLength -ne $e.dataLength -or $a.manifestSha256 -ne $e.manifestSha256){return $false}}
    return $true
}
if(-not(Test-Path -LiteralPath $Zip)){throw "ZIP not found: $Zip"}
$extractDir=Join-Path $ExtractBase ("run-"+[guid]::NewGuid().ToString('N').Substring(0,8));New-Item -ItemType Directory -Force -Path $extractDir|Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $extractDir -Force
$root=Join-Path $extractDir 'SupplyMindAI'
$report=[ordered]@{phase='persistence-attack';candidateCommit=(git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null);builtAt=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')}
$app=Start-App $root
$configApi=Invoke-RestMethod -Uri "http://127.0.0.1:$($app.Port)/api/config/items" -TimeoutSec 10
$report.initialConfigVersion=$configApi.configVersion
$itemId='MAT.ADC12.AM';$bizDate=(Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
$fields=[ordered]@{itemId=$itemId;source='Manual';businessDate=$bizDate;value='19500.50';unit='元/吨'}
$encoded=@($fields.GetEnumerator()|ForEach-Object{[System.Uri]::EscapeDataString([string]$_.Key)+'='+[System.Uri]::EscapeDataString([string]$_.Value)}) -join '&'
$bodyBytes=[System.Text.Encoding]::UTF8.GetBytes($encoded)
$resp=Invoke-WebRequest -Uri "http://127.0.0.1:$($app.Port)/api/dashboard/manual" -Method Post -Body $bodyBytes -ContentType 'application/x-www-form-urlencoded; charset=UTF-8' -UseBasicParsing -TimeoutSec 15
$write=$resp.Content|ConvertFrom-Json
$report.writeHttpStatus=$resp.StatusCode
$report.writeResponse=[ordered]@{status=$write.status;itemId=$write.itemId;unit=$write.unit;businessDate=$write.businessDate;value=$write.value;runId=$write.runId;rawRef=$write.rawRef;timelineRef=$write.timelineRef}
if($resp.StatusCode -ne 200 -or $write.status -ne 'PENDING' -or $write.unit -ne '元/吨' -or $write.itemId -ne $itemId){throw 'manual HTTP write contract mismatch'}
$initialSnapshot=@(Get-PersistenceSnapshot $root $write)
if(@($initialSnapshot|Where-Object{-not $_.manifestValid}).Count -gt 0){throw 'initial raw/timeline/config manifest binding failed'}
$report.initialSnapshot=$initialSnapshot
Stop-App $root $app
$app2=Start-App $root
$restartConfig=Invoke-RestMethod -Uri "http://127.0.0.1:$($app2.Port)/api/config/items" -TimeoutSec 10
$restartSnapshot=@(Get-PersistenceSnapshot $root $write)
$restartPass=$restartConfig.configVersion -eq $report.initialConfigVersion -and (Test-SnapshotEqual $initialSnapshot $restartSnapshot)
$report.restartApiConfigVersion=$restartConfig.configVersion
$report.restartSnapshot=$restartSnapshot
$report.restartExactBytesAndManifest=$restartPass
Stop-App $root $app2
$movedBase=Join-Path $ExtractBase ("moved-"+[guid]::NewGuid().ToString('N').Substring(0,8));New-Item -ItemType Directory -Force -Path $movedBase|Out-Null
$movedRoot=Join-Path $movedBase 'SupplyMindAI';Move-Item -LiteralPath $root -Destination $movedRoot
$app3=Start-App $movedRoot
$movedConfig=Invoke-RestMethod -Uri "http://127.0.0.1:$($app3.Port)/api/config/items" -TimeoutSec 10
$movedSnapshot=@(Get-PersistenceSnapshot $movedRoot $write)
$movedPass=$movedConfig.configVersion -eq $report.initialConfigVersion -and (Test-SnapshotEqual $initialSnapshot $movedSnapshot)
$report.movedApiConfigVersion=$movedConfig.configVersion
$report.movedSnapshot=$movedSnapshot
$report.movedExactBytesAndManifest=$movedPass
Stop-App $movedRoot $app3
$residual=@(Get-AppJavaProcs $movedRoot)
$report.residualAfterAll=@($residual|ForEach-Object{$_.Id})
$allPass=$restartPass -and $movedPass -and $residual.Count -eq 0
$report.result=if($allPass){'PASS'}else{'FAIL'}
$json=$report|ConvertTo-Json -Depth 8
if($EvidenceOut){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut)|Out-Null;[System.IO.File]::WriteAllText($EvidenceOut,$json+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))}
$report|ConvertTo-Json -Depth 8|Out-Host
if(-not $allPass){exit 1}
Write-Host '[f5] PASS: exact raw/timeline/config bytes and manifests survived restart and directory move'