#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Installs the Employee Monitor agent to run in the interactive user session (recommended, full fidelity).

.DESCRIPTION
    The agent needs the user's desktop session to detect idle/lock, the foreground app and to capture the screen
    (the Session-0 limitation). This installer therefore runs the agent and its visible tray in the user session via
    logon scheduled tasks, rather than as a Session-0 service. For an always-on, presence-only deployment with that
    limitation, use install-service.ps1 instead.

    Steps: check Windows version, publish/copy the binaries, store configuration, show the monitoring notice, create
    the logon tasks (agent + tray), start them for the current session, and verify backend connectivity.

.EXAMPLE
    .\scripts\install-agent.ps1 -EnrollmentToken enr_xxx -BackendBaseUrl https://monitor.company.com `
        -UpdatePublicKeyPath .\keys\agent-update-public.key
#>
param(
    [Parameter(Mandatory = $true)][string]$EnrollmentToken,
    [string]$BackendBaseUrl = "http://localhost:8080",
    [string]$UpdatePublicKeyPath,
    [string]$InstallDir = "$env:ProgramFiles\EmployeeMonitor\Agent",
    [switch]$SelfContained
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

if ([Environment]::OSVersion.Version.Major -lt 10) { throw "Windows 10 or later is required." }

Write-Host "== Publishing agent and tray" -ForegroundColor Cyan
$publishDir = Join-Path $env:TEMP "em-agent-install"
Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
foreach ($proj in @("EmployeeMonitor.Service", "EmployeeMonitor.Tray")) {
    $args = @("publish", (Join-Path $root "agent\$proj\$proj.csproj"), "-c", "Release", "-o", $publishDir, "--nologo", "-v", "q")
    if ($SelfContained) { $args += @("-r", "win-x64", "--self-contained", "true") }
    & dotnet @args
    if ($LASTEXITCODE -ne 0) { throw "Publish failed for $proj" }
}

Write-Host "== Installing to $InstallDir" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item (Join-Path $publishDir "*") $InstallDir -Recurse -Force
$agentExe = Join-Path $InstallDir "EmployeeMonitorAgent.exe"
$trayExe = Join-Path $InstallDir "EmployeeMonitorTray.exe"
if (-not (Test-Path $agentExe)) { throw "Agent exe missing at $agentExe" }

# Machine-wide configuration (inherited by the logon tasks).
[Environment]::SetEnvironmentVariable("EM_ENROLLMENT_TOKEN", $EnrollmentToken, "Machine")
[Environment]::SetEnvironmentVariable("Agent__BackendBaseUrl", $BackendBaseUrl, "Machine")
if ($UpdatePublicKeyPath -and (Test-Path $UpdatePublicKeyPath)) {
    [Environment]::SetEnvironmentVariable("Agent__UpdatePublicKey", (Get-Content $UpdatePublicKeyPath -Raw).Trim(), "Machine")
}

# Domain-level website monitoring reads query NAMES from the Windows DNS-Client operational log. That channel is
# off by default and enabling it needs admin rights, which the agent (running un-elevated in the user session)
# does not have - so the installer enables it once here. This records resolved domains only, never URLs or content.
Write-Host "== Enabling domain-level website capture (DNS-Client log)" -ForegroundColor Cyan
$dnsLog = "Microsoft-Windows-DNS-Client/Operational"
& wevtutil sl $dnsLog /e:true 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  $dnsLog enabled (domain-level query names only)." -ForegroundColor Green
} else {
    Write-Host "  WARNING: could not enable $dnsLog; website monitoring will be unavailable." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  COMPANY MONITORING NOTICE" -ForegroundColor Yellow
Write-Host "  This company-owned device is monitored per company policy. Monitoring is transparent: a tray"
Write-Host "  indicator shows it is active and exactly what is monitored. It never captures keystrokes, passwords,"
Write-Host "  private messages, webcam or microphone. Contact IT with any questions."
Write-Host ""

Write-Host "== Registering logon tasks (user session)" -ForegroundColor Cyan
$users = New-ScheduledTaskPrincipal -GroupId "S-1-5-32-545" -RunLevel Limited   # BUILTIN\Users
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName "EmployeeMonitor Agent" -Force -Principal $users -Trigger $trigger `
    -Settings $settings -Action (New-ScheduledTaskAction -Execute $agentExe) | Out-Null
Register-ScheduledTask -TaskName "EmployeeMonitor Tray" -Force -Principal $users -Trigger $trigger `
    -Settings $settings -Action (New-ScheduledTaskAction -Execute $trayExe) | Out-Null

Write-Host "== Verifying backend connectivity" -ForegroundColor Cyan
try {
    $health = Invoke-RestMethod "$BackendBaseUrl/actuator/health" -TimeoutSec 10
    Write-Host "  Backend health: $($health.status)" -ForegroundColor Green
} catch {
    Write-Host "  WARNING: could not reach $BackendBaseUrl ($($_.Exception.Message))" -ForegroundColor Yellow
}

Write-Host "`nInstalled. The agent and tray start at each user logon." -ForegroundColor Green
Write-Host "To start now for the current session, run:`n  Start-Process '$agentExe'`n  Start-Process '$trayExe'" -ForegroundColor DarkGray
Remove-Item $publishDir -Recurse -Force -ErrorAction SilentlyContinue
