$ErrorActionPreference='Stop'
# Reads an existing credential and passes it only to the desktop child. Never prints or saves it.
$runtimeRoot=$PSScriptRoot
if(!(Test-Path -LiteralPath (Join-Path $runtimeRoot 'SupplyMindAI.exe'))){$runtimeRoot=Split-Path $runtimeRoot -Parent}
$exe=Join-Path $runtimeRoot 'SupplyMindAI.exe'
if(!(Test-Path -LiteralPath $exe)){throw 'Run this script beside SupplyMindAI.exe or inside its demo-data folder.'}
$key=[Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User')
if([string]::IsNullOrWhiteSpace($key)){$key=[Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','Process')}
if([string]::IsNullOrWhiteSpace($key)-or$key.Contains("`r")-or$key.Contains("`n")){throw 'Existing DASHSCOPE_API_KEY missing or invalid. No model request was sent.'}
$info=New-Object System.Diagnostics.ProcessStartInfo
$info.FileName=$exe
$info.WorkingDirectory=$runtimeRoot
$info.UseShellExecute=$false
$info.EnvironmentVariables['SUPPLYMIND_LLM_ENABLED']='true'
$info.EnvironmentVariables['SUPPLYMIND_LLM_PROVIDER']='openai-compatible'
$info.EnvironmentVariables['SUPPLYMIND_LLM_MODEL']='qwen-plus'
$info.EnvironmentVariables['SUPPLYMIND_LLM_BASE_URL']='https://dashscope.aliyuncs.com/compatible-mode/v1'
$info.EnvironmentVariables['SUPPLYMIND_LLM_COMPLETIONS_PATH']='/chat/completions'
$info.EnvironmentVariables['SUPPLYMIND_LLM_API_KEY']=$key
foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){$info.EnvironmentVariables.Remove($name)}
try {
    $child=[Diagnostics.Process]::Start($info)
    if($null-eq$child){throw 'Desktop startup failed.'}
    Write-Output 'Cloud mode started. No analysis submitted. Submitting analysis sends business facts and may incur charges.'
} finally {
    $info.EnvironmentVariables.Remove('SUPPLYMIND_LLM_API_KEY')
    $key=$null
}
