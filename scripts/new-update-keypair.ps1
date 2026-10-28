<#
.SYNOPSIS
    Generates the RSA key pair used to sign agent updates.

.DESCRIPTION
    Writes the public key (base64 SubjectPublicKeyInfo) that the agent is configured with (Agent:UpdatePublicKey),
    and the private key (PKCS#8 PEM) kept OFFLINE by whoever publishes updates. Keep the private key secret; anyone
    with it can publish updates the fleet will trust.

.EXAMPLE
    .\scripts\new-update-keypair.ps1 -OutDir .\keys
#>
param([string]$OutDir = ".\keys")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$tool = Join-Path $root "admin-windows\tools\AdminLiveCheck"

$out = (& dotnet run --project $tool -c Release -- keygen 2>&1 | Out-String)
$publicKey = (($out -split "`n") | Where-Object { $_ -match '^PUBLIC_KEY=' } | Select-Object -First 1) -replace 'PUBLIC_KEY=', ''
$pemMatch = [regex]::Match($out, '(?s)-----BEGIN PRIVATE KEY-----.*?-----END PRIVATE KEY-----')
if (-not $publicKey -or -not $pemMatch.Success) { throw "Key generation failed:`n$out" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$pubPath = Join-Path $OutDir "agent-update-public.key"
$pemPath = Join-Path $OutDir "agent-update-private.pem"
Set-Content -Path $pubPath -Value $publicKey.Trim() -Encoding ascii
Set-Content -Path $pemPath -Value $pemMatch.Value -Encoding ascii

Write-Host "Public key  -> $pubPath" -ForegroundColor Green
Write-Host "  Configure the agent with Agent:UpdatePublicKey = (contents of this file)." -ForegroundColor DarkGray
Write-Host "Private key -> $pemPath" -ForegroundColor Yellow
Write-Host "  KEEP THIS OFFLINE AND SECRET. Use it with publish-update.ps1 to sign releases." -ForegroundColor DarkGray
