#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Stops and removes the Employee Monitor agent Windows Service.

.DESCRIPTION
    IT administrators can always uninstall the agent (there are no anti-removal mechanisms). Optionally removes
    the installed files and the stored device credential.

.EXAMPLE
    .\scripts\uninstall-service.ps1 -RemoveData
#>
param(
    [string]$InstallDir = "$env:ProgramFiles\EmployeeMonitor\Agent",
    [switch]$RemoveData
)

$ErrorActionPreference = "SilentlyContinue"
$serviceName = "EmployeeMonitorAgent"

if (Get-Service $serviceName -ErrorAction SilentlyContinue) {
    Write-Host "Stopping and deleting service..." -ForegroundColor Cyan
    sc.exe stop $serviceName | Out-Null
    Start-Sleep -Seconds 2
    sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
} else {
    Write-Host "Service not installed." -ForegroundColor Yellow
}

[Environment]::SetEnvironmentVariable("EM_ENROLLMENT_TOKEN", $null, "Machine")
[Environment]::SetEnvironmentVariable("Agent__BackendBaseUrl", $null, "Machine")

if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "Removed $InstallDir" -ForegroundColor Green
}

if ($RemoveData) {
    $dataDir = Join-Path $env:ProgramData "EmployeeMonitor"
    if (Test-Path $dataDir) {
        Remove-Item $dataDir -Recurse -Force
        Write-Host "Removed device credential at $dataDir" -ForegroundColor Green
    }
}

Write-Host "Uninstalled. The device remains enrolled on the server; retire it there with DELETE /api/devices/{id} if desired." -ForegroundColor DarkGray
