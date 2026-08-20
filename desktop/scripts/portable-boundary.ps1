# D9 Final Attack F6: portable EXE boundary with bundled-JRE identity and real ACL denial.
param([string]$Zip=(Join-Path $PSScriptRoot '..\..\release\SupplyMindAI-0.9.0-win32-x64.zip'),[string]$EvidenceOut)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$repoRoot=Join-Path $PSScriptRoot '..\..'
if(-not(Test-Path -LiteralPath $Zip)){throw "ZIP not found: $Zip"}
function Strip-DevEnv{
    $env:JAVA_HOME=''
    $env:PATH=($env:PATH -split ';'|Where-Object{$_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven|git\cmd|git\\bin'}) -join ';'
    foreach($name in @('SUPPLYMIND_LLM_ENABLED','SUPPLYMIND_LLM_API_KEY','SUPPLYMIND_LLM_BASE_URL','SUPPLYMIND_LLM_MODEL','SUPPLYMIND_LLM_COMPLETIONS_PATH','SUPPLYMIND_LLM_TIMEOUT','SUPPLYMIND_LLM_PROVIDER')){Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue}
}
function Get-AppJavaProcs([string]$RootPath){
    Get-Process -Name java -ErrorAction SilentlyContinue|Where-Object{try{$_.Path -like "$RootPath*"}catch{$false}}|ForEach-Object{
        $cmdline=(Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine
        if($cmdline -match [regex]::Escape((Join-Path $RootPath 'app\supplymind-backend.jar'))){[pscustomobject]@{Id=$_.Id;Path=$_.Path;CmdLine=$cmdline}}
    }
}
function Stop-AllAppProcs([string]$RootPath){
    @(Get-AppJavaProcs $RootPath)|ForEach-Object{Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue}
    Get-Process -Name 'SupplyMindAI' -ErrorAction SilentlyContinue|Where-Object{try{$_.Path -like "$RootPath*"}catch{$false}}|ForEach-Object{Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue}
    $deadline=(Get-Date).AddSeconds(20)
    while((Get-Date)-lt $deadline){if(@(Get-AppJavaProcs $RootPath).Count -eq 0){return};Start-Sleep -Milliseconds 500}
}
function Test-PortableBoot([string]$RootPath,[string]$Label){
    $exe=Join-Path $RootPath 'SupplyMindAI.exe';$urlFile=Join-Path $RootPath 'logs\backend-url.txt';Remove-Item $urlFile -Force -ErrorAction SilentlyContinue
    $exeProc=Start-Process -FilePath $exe -PassThru;$port=$null;$deadline=(Get-Date).AddSeconds(90)
    while((Get-Date)-lt $deadline){if(Test-Path $urlFile){$c=(Get-Content $urlFile -Raw).Trim();if($c -match 'http://127\.0\.0\.1:(\d+)/?'){$port=[int]$Matches[1];break}};Start-Sleep -Seconds 1}
    if(-not $port){Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue;throw "[$Label] backend-url never appeared"}
    $healthy=$false;$deadline=(Get-Date).AddSeconds(45)
    while((Get-Date)-lt $deadline){try{$h=Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 2;if($h.status -eq 'UP'){$healthy=$true;break}}catch{Start-Sleep -Milliseconds 500}}
    $javaProcs=@(Get-AppJavaProcs $RootPath)
    $expectedJava=[System.IO.Path]::GetFullPath((Join-Path $RootPath 'runtime\jre\bin\java.exe'))
    $actualJava=if($javaProcs.Count -eq 1){[System.IO.Path]::GetFullPath($javaProcs[0].Path)}else{$null}
    $identityPass=$healthy -and $javaProcs.Count -eq 1 -and $actualJava.Equals($expectedJava,[System.StringComparison]::OrdinalIgnoreCase)
    Stop-Process -Id $exeProc.Id -Force -ErrorAction SilentlyContinue;$exeProc.WaitForExit(15000)|Out-Null
    Stop-AllAppProcs $RootPath
    $residual=@(Get-AppJavaProcs $RootPath)
    [pscustomobject]@{label=$Label;healthy=$healthy;javaProcessCount=$javaProcs.Count;javaPath=$actualJava;expectedJavaPath=$expectedJava;bundledJavaIdentity=$identityPass;residualCount=$residual.Count;pass=($identityPass -and $residual.Count -eq 0)}
}
function Invoke-PathCase([string]$Base,[string]$Label){
    New-Item -ItemType Directory -Force -Path $Base|Out-Null;Expand-Archive -LiteralPath $Zip -DestinationPath $Base -Force
    $root=Join-Path $Base 'SupplyMindAI'
    try{Test-PortableBoot $root $Label}finally{Stop-AllAppProcs $root;if(Test-Path $Base){Remove-Item $Base -Recurse -Force -ErrorAction SilentlyContinue}}
}
Strip-DevEnv
$results=[System.Collections.Generic.List[object]]::new()
$results.Add((Invoke-PathCase (Join-Path $env:TEMP ("supplymind-f6-"+[guid]::NewGuid().ToString('N').Substring(0,8))) 'plain'))
$results.Add((Invoke-PathCase (Join-Path $env:TEMP ("supply mind f6 dir "+[guid]::NewGuid().ToString('N').Substring(0,8))) 'spaces'))
$results.Add((Invoke-PathCase (Join-Path $env:TEMP ("供应链智脑 AI 便携测试 "+[guid]::NewGuid().ToString('N').Substring(0,8))) 'chinese'))

$aclBase=Join-Path $env:TEMP ("supplymind-f6-acl-"+[guid]::NewGuid().ToString('N').Substring(0,8));New-Item -ItemType Directory -Force -Path $aclBase|Out-Null
Expand-Archive -LiteralPath $Zip -DestinationPath $aclBase -Force
$aclRoot=Join-Path $aclBase 'SupplyMindAI';$dataDir=Join-Path $aclRoot 'data'
$identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$aclApplied=$false;$writeDenied=$false;$rejected=$false;$backendCreated=$false
try{
    & icacls.exe $dataDir /inheritance:r /grant:r ($identity + ':(OI)(CI)(RX)') | Out-Null
    if($LASTEXITCODE -ne 0){throw 'unable to apply read-only ACL'}
    $aclApplied=$true
    try{[System.IO.File]::WriteAllText((Join-Path $dataDir 'write-probe.tmp'),'probe');Remove-Item (Join-Path $dataDir 'write-probe.tmp') -Force}catch{$writeDenied=$true}
    if(-not $writeDenied){throw 'ACL attack did not actually deny writes'}
    $p=Start-Process -FilePath (Join-Path $aclRoot 'SupplyMindAI.exe') -PassThru
    $p.WaitForExit(20000)|Out-Null;$rejected=$p.HasExited
    $backendCreated=@(Get-AppJavaProcs $aclRoot).Count -gt 0
    if(-not $p.HasExited){Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue}
}finally{
    Stop-AllAppProcs $aclRoot
    if($aclApplied){& icacls.exe $dataDir /reset /T /C | Out-Null}
}
$aclPass=$aclApplied -and $writeDenied -and $rejected -and (-not $backendCreated) -and @(Get-AppJavaProcs $aclRoot).Count -eq 0
$results.Add([pscustomobject]@{label='readonly-data-acl';aclApplied=$aclApplied;writeDenied=$writeDenied;exeRejected=$rejected;backendCreated=$backendCreated;pass=$aclPass})
Remove-Item $aclBase -Recurse -Force -ErrorAction SilentlyContinue
$pass=@($results|Where-Object{-not $_.pass}).Count -eq 0
$report=[ordered]@{phase='portable-boundary';candidateCommit=(git -C $repoRoot rev-parse --short HEAD 2>$null);builtAt=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');results=$results;result=if($pass){'PASS'}else{'FAIL'}}
$json=$report|ConvertTo-Json -Depth 6
if($EvidenceOut){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut)|Out-Null;[System.IO.File]::WriteAllText($EvidenceOut,$json+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))}
$report|ConvertTo-Json -Depth 6|Out-Host
if(-not $pass){exit 1}
Write-Host '[f6] PASS: plain/space/Chinese roots use exactly the bundled JRE and a truly read-only data ACL fails before backend spawn'