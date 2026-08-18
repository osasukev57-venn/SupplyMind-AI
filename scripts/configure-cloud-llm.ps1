[CmdletBinding()]
param(
    [string]$Provider = 'openai-compatible',
    [string]$BaseUrl,
    [string]$Model,
    [string]$CompletionsPath = '/v1/chat/completions',
    [string]$Timeout = '30s',
    [switch]$Clear,
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$variableNames = @(
    'SUPPLYMIND_LLM_ENABLED',
    'SUPPLYMIND_LLM_PROVIDER',
    'SUPPLYMIND_LLM_BASE_URL',
    'SUPPLYMIND_LLM_MODEL',
    'SUPPLYMIND_LLM_COMPLETIONS_PATH',
    'SUPPLYMIND_LLM_TIMEOUT',
    'SUPPLYMIND_LLM_API_KEY'
)

if ($Clear) {
    foreach ($name in $variableNames) {
        [Environment]::SetEnvironmentVariable($name, $null, 'User')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Write-Host 'SupplyMind cloud LLM user environment variables were removed.' -ForegroundColor Yellow
    return
}

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = Read-Host 'Cloud API base URL (for OpenAI: https://api.openai.com)'
}
if ([string]::IsNullOrWhiteSpace($Model)) {
    $Model = Read-Host 'Cloud model name'
}
$secureKey = Read-Host 'Cloud API key (input is hidden)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)

try {
    $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw 'API key must not be empty.'
    }

    $values = [ordered]@{
        SUPPLYMIND_LLM_ENABLED = 'true'
        SUPPLYMIND_LLM_PROVIDER = $Provider
        SUPPLYMIND_LLM_BASE_URL = $BaseUrl
        SUPPLYMIND_LLM_MODEL = $Model
        SUPPLYMIND_LLM_COMPLETIONS_PATH = $CompletionsPath
        SUPPLYMIND_LLM_TIMEOUT = $Timeout
        SUPPLYMIND_LLM_API_KEY = $apiKey
    }
    foreach ($entry in $values.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'User')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }

    Write-Host 'SupplyMind cloud LLM user environment variables are configured.' -ForegroundColor Green
    Write-Host 'The API key was not printed. Open a new terminal before launching the application.'

    if ($Verify) {
        $repoRoot = Split-Path -Parent $PSScriptRoot
        Push-Location (Join-Path $repoRoot 'backend')
        try {
            & .\mvnw.cmd '-Dtest=CloudLlmRealApiAcceptanceTest' '-Dsupplymind.llm.real=true' test
            if ($LASTEXITCODE -ne 0) {
                throw "Cloud LLM gated verification failed with Maven exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
    }
}
finally {
    if ($ptr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
    Remove-Variable apiKey -ErrorAction SilentlyContinue
}
