<#
.SYNOPSIS
    End-to-end test of the Windows agent (Milestone 2) against a running backend.

.DESCRIPTION
    Creates an employee and an enrollment token, runs the real EmployeeMonitorAgent in console mode with a
    throwaway data directory (so it enrolls fresh), then verifies via the admin API that the device enrolled,
    reports ACTIVE presence from heartbeats, and that AGENT_START/ACTIVE session events reached the timeline.

.EXAMPLE
    .\scripts\agent-smoke.ps1
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
Check (Test-Path $exe) "Agent built: $exe"

Write-Host "`n== Prepare enrollment" -ForegroundColor Cyan
$admin = (Api POST "/api/auth/login" $null @{ username = $AdminUsername; password = $AdminPassword }).accessToken
$suffix = (Get-Random -Maximum 99999).ToString("00000")
$emp = Api POST "/api/employees" $admin @{ employeeCode = "AGT-$suffix"; fullName = "Agent Test $suffix"; email = "agt.$suffix@example.com" }
$enroll = Api POST "/api/enrollment-tokens" $admin @{ description = "agent-smoke"; employeeId = $emp.id; maxUses = 1; expiresInHours = 1 }
Ok "Employee $($emp.employeeCode) and enrollment token created"

Write-Host "`n== Run agent (console mode, fresh data dir)" -ForegroundColor Cyan
$dataDir = Join-Path $env:TEMP "em-agent-smoke-$suffix"
$logFile = Join-Path $env:TEMP "em-agent-smoke-$suffix.log"
$env:EM_ENROLLMENT_TOKEN = $enroll.token
$env:Agent__BackendBaseUrl = $BaseUrl
$env:Agent__DataDirectory = $dataDir
$env:Agent__EventFlushSeconds = "3"
$env:Agent__ActivityPollSeconds = "2"
$env:Agent__DefaultHeartbeatSeconds = "5"

$proc = Start-Process -FilePath $exe -PassThru -NoNewWindow -RedirectStandardOutput $logFile
try {
    Start-Sleep -Seconds 10
    $hostname = $env:COMPUTERNAME
    $device = (Api GET "/api/devices?q=$hostname" $admin).content | Where-Object { $_.employee.id -eq $emp.id } | Select-Object -First 1
    Check ($null -ne $device) "Device enrolled for this host ($hostname)"
    if (-not $device) { throw "Device did not enroll" }
    Check ($device.deviceCode -match '^DEV-\d{5}$') "Device code $($device.deviceCode)"
    # Heartbeats update realtime presence; input-idle reports IDLE in a non-interactive shell, so accept either.
    Check ($device.presence.online -eq $true) "Presence is online from heartbeats (status $($device.presence.status))"
    Check ($null -ne $device.lastSeenAt) "lastSeenAt recorded"

    # Give the device a 24/7 window so activity/session capture is not suppressed by the default work hours.
    $settings = Api GET "/api/policies/$($device.id)" $admin
    $s = $settings.settings
    $s.monitoringStart = "00:00"; $s.monitoringEnd = "23:59"
    $s.workDays = @("MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY")
    $s.heartbeatIntervalSeconds = 10   # so a heartbeat carries the live snapshot within the test window
    $s.screenshots = $true             # Milestone 5: enable periodic screenshots
    $s.screenshotIntervalSeconds = 300
    $s.liveView = $true                # Milestone 6: allow authorized live view
    Api PUT "/api/policies/$($device.id)" $admin $s | Out-Null
    Ok "Applied 24/7 window to device (agent will re-sync on next heartbeat)"
    Start-Sleep -Seconds 22   # let the agent re-sync, poll collectors, and heartbeat the live snapshot

    $today = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
    $timeline = Api GET "/api/employees/$($emp.id)/timeline?date=$today&tz=UTC" $admin
    $types = @($timeline.entries | ForEach-Object { $_.type })
    Check ($types -contains "AGENT_START") "Timeline has AGENT_START"
    Check (($types -contains "ACTIVE") -or ($types -contains "IDLE")) "Timeline has an activity event ($($types -join ','))"
    $summary = Api GET "/api/employees/$($emp.id)/session-summary?date=$today&tz=UTC" $admin
    Check ($summary.currentlyOnline -eq $true) "Session summary shows currently online"

    # Milestone 4 monitoring: network + Zscaler status are produced by the agent's collectors.
    $net = Api GET "/api/employees/$($emp.id)/network-events?date=$today&tz=UTC" $admin
    Check ($net.totalElements -ge 1) "Network events collected ($($net.totalElements))"
    $z = Api GET "/api/employees/$($emp.id)/zscaler" $admin
    Check ($null -ne $z.currentStatus -and $z.history.Count -ge 1) "Zscaler status reported ($($z.currentStatus))"
    $dev = Api GET "/api/devices/$($device.id)" $admin
    Check ($null -ne $dev.presence.network -or $null -ne $dev.presence.zscalerStatus) "Live presence carries network/zscaler"

    # Milestone 5 screenshots: the agent captures and uploads at least one; verify it is retrievable.
    $shots = Api GET "/api/employees/$($emp.id)/screenshots?date=$today&tz=UTC" $admin
    Check ($shots.totalElements -ge 1) "Screenshot captured and uploaded ($($shots.totalElements))"
    if ($shots.totalElements -ge 1) {
        $sid = $shots.content[0].id
        $img = Invoke-WebRequest -Uri "$BaseUrl/api/screenshots/$sid/content" -Headers @{ Authorization = "Bearer $admin" } -UseBasicParsing
        Check ($img.StatusCode -eq 200 -and $img.Headers["Content-Type"] -like "image/*" -and $img.RawContentLength -gt 0) `
            "Screenshot image retrievable via secure endpoint ($($img.Headers['Content-Type']))"
        $za = Api GET "/api/audit-logs?action=SCREENSHOT_VIEWED&targetType=SCREENSHOT&targetId=$sid" $admin
        Check ($za.totalElements -ge 1) "Screenshot view was audited"
    }

    # Milestone 6 live view: drive an authorized session against the running agent and verify frames + audit.
    Write-Host "`n== Live view (end-to-end)" -ForegroundColor Cyan
    $lvProject = Join-Path $root "admin-windows\tools\AdminLiveCheck"
    $lvOut = & dotnet run --project $lvProject -c Release -- liveview $BaseUrl $AdminUsername $AdminPassword $device.id 2>&1 | Out-String
    $lvOut.Split("`n") | Where-Object { $_ -match 'OK |FAIL' } | ForEach-Object { Write-Host "  $($_.Trim())" }
    Check ($lvOut -match "LIVE VIEW CHECK PASSED") "Live view streamed frames and was audited"
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
    Remove-Item Env:EM_ENROLLMENT_TOKEN, Env:Agent__BackendBaseUrl, Env:Agent__DataDirectory, `
        Env:Agent__EventFlushSeconds, Env:Agent__ActivityPollSeconds, Env:Agent__DefaultHeartbeatSeconds -ErrorAction SilentlyContinue
    Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "`n--- agent log (tail) ---" -ForegroundColor DarkGray
if (Test-Path $logFile) { Get-Content $logFile -Tail 12 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }; Remove-Item $logFile -Force -ErrorAction SilentlyContinue }

Write-Host ""
if ($script:failures -eq 0) { Write-Host "AGENT SMOKE TEST PASSED" -ForegroundColor Green; exit 0 }
else { Write-Host "$($script:failures) CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
