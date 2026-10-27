@echo off
setlocal
set "SUPPLYMIND_AGENT_ACCEPTANCE_DIAGNOSTICS_DIR=%~dp0logs\agent-acceptance"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-cloud-agent.ps1"
if errorlevel 1 pause
endlocal
