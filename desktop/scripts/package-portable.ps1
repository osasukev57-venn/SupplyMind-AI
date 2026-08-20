# D9 Final Attack F1: package-portable is superseded by the deterministic clean pipeline.
# It now delegates to package-clean.ps1 (fresh GUID staging, no reused portable root, no stale
# data/logs/runtime state, deterministic ZIP + artifact manifest + verify).
# Retained for backward compatibility only.
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI'),
    [switch]$SkipJreBuild
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'package-clean.ps1') -SkipJreBuild:([bool]$SkipJreBuild)
exit $LASTEXITCODE
