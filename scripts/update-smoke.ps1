<#
.SYNOPSIS
    End-to-end test of signed agent auto-update (Milestone 7) against a running stack.

.DESCRIPTION
    Uses the AdminLiveCheck tool to generate an RSA key, sign and publish a package, and approve it; then runs the
    real agent configured with the matching public key and verifies it downloads, signature-verifies and stages the
    update. (Key generation and signing run in .NET because Windows PowerShell 5.1 lacks SPKI export.)
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminUsername,
    [string]$AdminPassword
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$envFile = Join-Path $root "infrastructure\.env"
if ((-not $AdminUsername -or -not $AdminPassword) -and (Test-Path $envFile)) {
    $vars = @{}
    Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $vars[$Matches[1]] = $Matches[2].Trim() }
    if (-not $AdminUsername) { $AdminUsername = $vars["BOOTSTRAP_ADMIN_USERNAME"] }
    if (-not $AdminPassword) { $AdminPassword = $vars["BOOTSTRAP_ADMIN_PASSWORD"] }
}

$script:failures = 0
function Ok($m) { Write-Host "   OK  $m" -ForegroundColor Green }
function Fail($m) { Write-Host "   FAIL $m" -ForegroundColor Red; $script:failures++ }
function Check($c, $m) { if ($c) { Ok $m } else { Fail $m } }
function Api($method, $path, $token, $body) {
    $h = @{}; if ($token) { $h["Authorization"] = "Bearer $token" }
    $p = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $h; ContentType = "application/json" }
    if ($null -ne $body) { $p["Body"] = ($body | ConvertTo-Json -Depth 8) }
    return Invoke-RestMethod @p
}

Write-Host "== Build agent (Release)" -ForegroundColor Cyan
dotnet build (Join-Path $root "agent\EmployeeMonitor.Service\EmployeeMonitor.Service.csproj") -c Release -v q --nologo | Out-Null
$exe = Join-Path $root "agent\EmployeeMonitor.Service\bin\Release\net8.0-windows\EmployeeMonitorAgent.exe"

Write-Host "`n== Publish a signed package (via the .NET tool) and approve it" -ForegroundColor Cyan
$version = "1.5.$(Get-Random -Maximum 999)"
$tool = Join-Path $root "admin-windows\tools\AdminLiveCheck"
$pubOut = & dotnet run --project $tool -c Release -- publishupdate $BaseUrl $AdminUsername $AdminPassword $version 2>&1 | Out-String
$publicKey = ($pubOut.Split("`n") | Where-Object { $_ -match '^PUBLIC_KEY=' } | Select-Object -First 1) -replace 'PUBLIC_KEY=', ''
$publicKey = $publicKey.Trim()
Check ($publicKey.Length -gt 100) "Published and approved signed $version"

Write-Host "`n== Run agent with the matching public key" -ForegroundColor Cyan
$admin = (Api POST "/api/auth/login" $null @{ username = $AdminUsername; password = $AdminPassword }).accessToken
$emp = Api POST "/api/employees" $admin @{ employeeCode = "UPD-$(Get-Random -Max 99999)"; fullName = "Update User"; email = "upd.$(Get-Random)@example.com" }
$enroll = Api POST "/api/enrollment-tokens" $admin @{ employeeId = $emp.id; maxUses = 1; expiresInHours = 1 }
$dataDir = Join-Path $env:TEMP "em-update-smoke-$(Get-Random -Max 99999)"
Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
$env:EM_ENROLLMENT_TOKEN = $enroll.token
$env:Agent__BackendBaseUrl = $BaseUrl
$env:Agent__DataDirectory = $dataDir
$env:Agent__UpdatePublicKey = $publicKey
$log = Join-Path $env:TEMP "em-update-smoke.log"
$proc = Start-Process -FilePath $exe -PassThru -NoNewWindow -RedirectStandardOutput $log
try {
    $staged = Join-Path $dataDir "staged-updates\agent-$version.pkg"
    $deadline = (Get-Date).AddSeconds(30)
    do { Start-Sleep 2 } until ((Test-Path $staged) -or (Get-Date) -gt $deadline)
    Check (Test-Path $staged) "Agent downloaded, verified (SHA-256 + RSA signature) and staged the update"
    Check (Test-Path (Join-Path $dataDir "staged-updates\pending-update.json")) "Pending-update marker written"
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
    Remove-Item Env:EM_ENROLLMENT_TOKEN, Env:Agent__BackendBaseUrl, Env:Agent__DataDirectory, Env:Agent__UpdatePublicKey -ErrorAction SilentlyContinue
    Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $log -Force -ErrorAction SilentlyContinue
}

Write-Host ""
if ($script:failures -eq 0) { Write-Host "UPDATE SMOKE TEST PASSED" -ForegroundColor Green; exit 0 }
else { Write-Host "$($script:failures) CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
