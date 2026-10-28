<#
.SYNOPSIS
    Signs an agent package with the private key and publishes it to the backend.

.DESCRIPTION
    Signs the package (SHA-256 + RSA) and POSTs it to /api/agent-versions. The new version is created unapproved;
    approve it in the admin app (or PUT /api/agent-versions/{id}/approval) to roll it out. Agents only install
    approved versions after verifying the signature with their configured public key.

.EXAMPLE
    .\scripts\publish-update.ps1 -Version 1.2.0 -PackagePath .\EmployeeMonitorSetup.exe
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$PackagePath,
    [string]$PrivateKeyPath = ".\keys\agent-update-private.pem",
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminUsername,
    [string]$AdminPassword,
    [switch]$Mandatory
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$tool = Join-Path $root "admin-windows\tools\AdminLiveCheck"

$envFile = Join-Path $root "infrastructure\.env"
if ((-not $AdminUsername -or -not $AdminPassword) -and (Test-Path $envFile)) {
    $vars = @{}
    Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $vars[$Matches[1]] = $Matches[2].Trim() }
    if (-not $AdminUsername) { $AdminUsername = $vars["BOOTSTRAP_ADMIN_USERNAME"] }
    if (-not $AdminPassword) { $AdminPassword = $vars["BOOTSTRAP_ADMIN_PASSWORD"] }
}
if (-not (Test-Path $PrivateKeyPath)) { throw "Private key not found at $PrivateKeyPath (run new-update-keypair.ps1)" }
if (-not (Test-Path $PackagePath)) { throw "Package not found at $PackagePath" }

& dotnet run --project $tool -c Release -- signpublish $BaseUrl $AdminUsername $AdminPassword $Version `
    (Resolve-Path $PrivateKeyPath) (Resolve-Path $PackagePath) ($Mandatory.IsPresent.ToString().ToLower())
