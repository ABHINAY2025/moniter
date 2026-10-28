<#
.SYNOPSIS
    Builds EmployeeMonitorSetup.exe: self-contained-publishes the agent + tray, then compiles the Inno Setup script.

.DESCRIPTION
    Produces a self-contained (no .NET runtime prerequisite) build of the agent and tray, then runs the Inno Setup
    compiler (ISCC) against agent/EmployeeMonitor.Installer/EmployeeMonitor.iss. If ISCC is not installed, the
    publish step still completes and the single command to compile the installer is printed.

.EXAMPLE
    .\scripts\build-installer.ps1 -Version 1.0.0
#>
param(
    [string]$Version = "1.0.0",
    [string]$Runtime = "win-x64"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$installerDir = Join-Path $root "agent\EmployeeMonitor.Installer"
$publishDir = Join-Path $installerDir "publish"

Write-Host "== Publishing self-contained agent + tray ($Runtime)" -ForegroundColor Cyan
Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
foreach ($proj in @("EmployeeMonitor.Service", "EmployeeMonitor.Tray")) {
    & dotnet publish (Join-Path $root "agent\$proj\$proj.csproj") -c Release -r $Runtime --self-contained true `
        -p:Version=$Version -o $publishDir --nologo -v q
    if ($LASTEXITCODE -ne 0) { throw "Publish failed for $proj" }
}
Write-Host "  Published to $publishDir" -ForegroundColor Green

$iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue)
if (-not $iscc) {
    foreach ($candidate in @("$env:ProgramFiles\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe")) {
        if (Test-Path $candidate) { $iscc = Get-Item $candidate; break }
    }
}
if ($iscc) {
    Write-Host "== Compiling installer with $($iscc.Source)" -ForegroundColor Cyan
    & $iscc.Source "/DAgentVersion=$Version" (Join-Path $installerDir "EmployeeMonitor.iss")
    if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }
    Write-Host "  Built $(Join-Path $installerDir 'Output\EmployeeMonitorSetup.exe')" -ForegroundColor Green
} else {
    Write-Host "Inno Setup (ISCC.exe) not found. Install it from https://jrsoftware.org/isdl.php, then run:" -ForegroundColor Yellow
    Write-Host "  ISCC /DAgentVersion=$Version `"$(Join-Path $installerDir 'EmployeeMonitor.iss')`"" -ForegroundColor Yellow
    Write-Host "to produce agent\EmployeeMonitor.Installer\Output\EmployeeMonitorSetup.exe" -ForegroundColor Yellow
}
