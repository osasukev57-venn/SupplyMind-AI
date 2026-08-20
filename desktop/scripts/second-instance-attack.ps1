# D9 Final Attack F3: real second-EXE attack with production-observed activation.
param([string]$Root,[string]$EvidenceOut)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win32Native {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hWnd);
}
"@
if(-not $Root){throw 'Root is required'}
$exe=Join-Path $Root 'SupplyMindAI.exe'
if(-not(Test-Path -LiteralPath $exe)){throw "EXE missing: $exe"}
$env:JAVA_HOME=''
$env:PATH=($env:PATH -split ';'|Where-Object{$_ -notmatch 'jdk-17|jdk-21|jdk26|\\Java\\|nodejs|npm|maven'}) -join ';'
foreach($name in @('SUPPLYMIND_LLM_ENABLED','SUPPLYMIND_LLM_API_KEY','SUPPLYMIND_LLM_BASE_URL','SUPPLYMIND_LLM_MODEL','SUPPLYMIND_LLM_COMPLETIONS_PATH','SUPPLYMIND_LLM_TIMEOUT','SUPPLYMIND_LLM_PROVIDER')){Remove-Item -LiteralPath "env:$name" -ErrorAction SilentlyContinue}
function Get-AppJavaProcs([string]$RootPath){
    Get-Process -Name java -ErrorAction SilentlyContinue|Where-Object{try{$_.Path -like "$RootPath*"}catch{$false}}|ForEach-Object{
        $cmdline=(Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine
        if($cmdline -match [regex]::Escape((Join-Path $RootPath 'app\supplymind-backend.jar'))){[pscustomobject]@{Id=$_.Id;Path=$_.Path;CmdLine=$cmdline}}
    }
}
$report=[ordered]@{phase='second-instance-attack';candidateCommit=(git -C (Join-Path $PSScriptRoot '..\..') rev-parse --short HEAD 2>$null);builtAt=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')}
$urlFile=Join-Path $Root 'logs\backend-url.txt'
$eventFile=Join-Path $Root 'logs\instance-events.jsonl'
Remove-Item $urlFile -Force -ErrorAction SilentlyContinue
Remove-Item $eventFile -Force -ErrorAction SilentlyContinue
$p1=Start-Process -FilePath $exe -PassThru
$port1=$null;$deadline=(Get-Date).AddSeconds(90)
while((Get-Date)-lt $deadline){if(Test-Path $urlFile){$c=(Get-Content $urlFile -Raw).Trim();if($c -match 'http://127\.0\.0\.1:(\d+)/?'){$port1=[int]$Matches[1];break}};Start-Sleep -Seconds 1}
if(-not $port1){throw 'instance1 backend-url never appeared'}
$healthy=$false;$deadline=(Get-Date).AddSeconds(45)
while((Get-Date)-lt $deadline){try{$h1=Invoke-RestMethod -Uri "http://127.0.0.1:$port1/api/health" -TimeoutSec 2;if($h1.status -eq 'UP'){$healthy=$true;break}}catch{Start-Sleep -Milliseconds 500}}
if(-not $healthy){Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue;throw 'instance1 health failed'}
$javaBefore=@(Get-AppJavaProcs $Root)
$firstJavaPid=if($javaBefore.Count -eq 1){$javaBefore[0].Id}else{$null}
$p1.Refresh();$firstHwnd=$p1.MainWindowHandle
$eventCountBefore=if(Test-Path $eventFile){@(Get-Content $eventFile).Count}else{0}
$p2=Start-Process -FilePath $exe -PassThru
$p2.WaitForExit(30000)|Out-Null
$secondExited=$p2.HasExited
$activation=$null;$deadline=(Get-Date).AddSeconds(15)
while((Get-Date)-lt $deadline){
    if(Test-Path $eventFile){$lines=@(Get-Content $eventFile);if($lines.Count -gt $eventCountBefore){try{$activation=$lines[-1]|ConvertFrom-Json}catch{};break}}
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Milliseconds 750
$javaAfter=@(Get-AppJavaProcs $Root)
$secondJavaPids=@($javaAfter|Where-Object{$_.Id -ne $firstJavaPid})
$urlAfter=(Get-Content $urlFile -Raw -ErrorAction SilentlyContinue).Trim()
$portAfter=if($urlAfter -match 'http://127\.0\.0\.1:(\d+)/?'){[int]$Matches[1]}else{$null}
$p1.Refresh();if($p1.MainWindowHandle -ne 0){$firstHwnd=$p1.MainWindowHandle}
$windowExists=$firstHwnd -ne 0 -and [Win32Native]::IsWindow([IntPtr]$firstHwnd)
$foreground=[Win32Native]::GetForegroundWindow()
$windowFocused=$windowExists -and ([Int64]$firstHwnd -eq [Int64]$foreground)
$eventObserved=$null -ne $activation -and $activation.event -eq 'SECOND_INSTANCE_ACTIVATED'
$focusCalled=$eventObserved -and $activation.windowExists -eq $true -and $activation.focusCalled -eq $true
$secondBackendCreated=$secondJavaPids.Count -gt 0
$backendPidUnchanged=$firstJavaPid -ne $null -and $javaAfter.Count -eq 1 -and $javaAfter[0].Id -eq $firstJavaPid
$portUnchanged=$portAfter -eq $port1
$report.instance2Exited=$secondExited
$report.productionActivationEventObserved=$eventObserved
$report.productionFocusCalled=$focusCalled
$report.firstWindowHandle=[Int64]$firstHwnd
$report.foregroundHandle=[Int64]$foreground
$report.windowFocused=$windowFocused
$report.secondBackendCreated=$secondBackendCreated
$report.backendPidUnchanged=$backendPidUnchanged
$report.portUnchanged=$portUnchanged
Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
$p1.WaitForExit(15000)|Out-Null
Start-Sleep -Seconds 8
$residual=@(Get-AppJavaProcs $Root)
$report.residualProcesses=@($residual|ForEach-Object{$_.Id})
$allPass=$secondExited -and $eventObserved -and $focusCalled -and $windowFocused -and (-not $secondBackendCreated) -and $backendPidUnchanged -and $portUnchanged -and $residual.Count -eq 0
$report.result=if($allPass){'PASS'}else{'FAIL'}
$json=$report|ConvertTo-Json -Depth 5
if($EvidenceOut){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $EvidenceOut)|Out-Null;[System.IO.File]::WriteAllText($EvidenceOut,$json+[Environment]::NewLine,[System.Text.UTF8Encoding]::new($false))}
$report|ConvertTo-Json -Depth 5|Out-Host
if(-not $allPass){exit 1}
Write-Host '[f3] PASS: production second-instance handler activated and focused the original window; no second backend'