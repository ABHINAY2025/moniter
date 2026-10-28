#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Removes the user-session agent installed by install-agent.ps1. IT can always uninstall the agent.
#>
param(
    [string]$InstallDir = "$env:ProgramFiles\EmployeeMonitor\Agent",
    [switch]$RemoveData
)

$ErrorActionPreference = "SilentlyContinue"

foreach ($task in @("EmployeeMonitor Agent", "EmployeeMonitor Tray")) {
    Unregister-ScheduledTask -TaskName $task -Confirm:$false
}
Get-Process EmployeeMonitorAgent, EmployeeMonitorTray -ErrorAction SilentlyContinue | Stop-Process -Force

[Environment]::SetEnvironmentVariable("EM_ENROLLMENT_TOKEN", $null, "Machine")
[Environment]::SetEnvironmentVariable("Agent__BackendBaseUrl", $null, "Machine")
[Environment]::SetEnvironmentVariable("Agent__UpdatePublicKey", $null, "Machine")

if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
if ($RemoveData) {
    $dataDir = Join-Path $env:ProgramData "EmployeeMonitor"
    if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
}
Write-Host "Uninstalled. Retire the device on the server (DELETE /api/devices/{id}) if desired." -ForegroundColor Green
