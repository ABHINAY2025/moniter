#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Installs the Employee Monitor agent as a Windows Service (development/manual install).

.DESCRIPTION
    Publishes the agent, copies it to Program Files, registers a Windows Service that starts automatically,
    stores the enrollment token and backend URL as machine environment variables, and starts the service.
    The full signed MSI installer, tray auto-start and per-session helper arrive in Milestone 7.

    Note: a service runs in Session 0 as LocalSystem, so input-idle and lock/unlock detection are limited there
    (presence, enrollment, policy sync and heartbeat work fully). See docs/agent.md. For development you can also
    run the agent in the user session with:  dotnet run --project agent/EmployeeMonitor.Service

.EXAMPLE
    .\scripts\install-service.ps1 -EnrollmentToken enr_xxx -BackendBaseUrl https://monitor.company.com
#>
param(
    [Parameter(Mandatory = $true)][string]$EnrollmentToken,
    [string]$BackendBaseUrl = "http://localhost:8080",
    [string]$InstallDir = "$env:ProgramFiles\EmployeeMonitor\Agent",
    [switch]$SelfContained
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$serviceName = "EmployeeMonitorAgent"
$project = Join-Path $root "agent\EmployeeMonitor.Service\EmployeeMonitor.Service.csproj"

Write-Host "Publishing agent..." -ForegroundColor Cyan
$publishDir = Join-Path $env:TEMP "em-agent-publish"
Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
$scArgs = @("publish", $project, "-c", "Release", "-o", $publishDir, "--nologo", "-v", "q")
if ($SelfContained) { $scArgs += @("-r", "win-x64", "--self-contained", "true") }
& dotnet @scArgs
if ($LASTEXITCODE -ne 0) { throw "Publish failed" }

if (Get-Service $serviceName -ErrorAction SilentlyContinue) {
    Write-Host "Existing service found; stopping and removing it first." -ForegroundColor Yellow
    sc.exe stop $serviceName | Out-Null
    Start-Sleep -Seconds 2
    sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}

Write-Host "Installing to $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item (Join-Path $publishDir "*") $InstallDir -Recurse -Force
$exe = Join-Path $InstallDir "EmployeeMonitorAgent.exe"
if (-not (Test-Path $exe)) { throw "Published exe not found at $exe" }

# Machine environment variables the service inherits at start.
[Environment]::SetEnvironmentVariable("EM_ENROLLMENT_TOKEN", $EnrollmentToken, "Machine")
[Environment]::SetEnvironmentVariable("Agent__BackendBaseUrl", $BackendBaseUrl, "Machine")

# Enable the Windows DNS-Client operational log so domain-level website monitoring has a source (query names only).
# LocalSystem could enable it at runtime too, but doing it here keeps the behaviour identical to the user-session install.
$dnsLog = "Microsoft-Windows-DNS-Client/Operational"
& wevtutil sl $dnsLog /e:true 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host "Enabled $dnsLog (domain-level query names only)." -ForegroundColor Green }
else { Write-Host "WARNING: could not enable $dnsLog; website monitoring will be unavailable." -ForegroundColor Yellow }

Write-Host "Registering service..." -ForegroundColor Cyan
sc.exe create $serviceName binPath= "`"$exe`"" start= auto DisplayName= "Employee Monitor Agent" | Out-Null
sc.exe description $serviceName "Transparent, policy-governed monitoring agent for this company-owned device." | Out-Null
# Restart on failure, but keep it easy for IT to stop/uninstall.
sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/10000 | Out-Null

Write-Host "Starting service..." -ForegroundColor Cyan
sc.exe start $serviceName | Out-Null
Start-Sleep -Seconds 3
$svc = Get-Service $serviceName
Write-Host "Service '$serviceName' is $($svc.Status)." -ForegroundColor Green
Write-Host "Logs: Get-Content (Event Viewer > Application) or run in console for live output." -ForegroundColor DarkGray
Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
