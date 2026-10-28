<#
.SYNOPSIS
    End-to-end smoke test of the Milestone 1 backend against a running stack.

.DESCRIPTION
    Logs in as the bootstrap admin, creates a department and employee, issues an enrollment token,
    registers a simulated device, sends heartbeats with the device token, and checks presence,
    the dashboard summary, RBAC and the audit log.

.EXAMPLE
    .\scripts\smoke-test.ps1
    .\scripts\smoke-test.ps1 -BaseUrl http://localhost:8080 -AdminPassword 'your-password'
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminUsername,
    [string]$AdminPassword
)

$ErrorActionPreference = "Stop"

# Fall back to the bootstrap credentials in infrastructure/.env.
$envFile = Join-Path $PSScriptRoot "..\infrastructure\.env"
if ((-not $AdminUsername -or -not $AdminPassword) -and (Test-Path $envFile)) {
    $vars = @{}
    Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object {
        $vars[$Matches[1]] = $Matches[2].Trim()
    }
    if (-not $AdminUsername) { $AdminUsername = $vars["BOOTSTRAP_ADMIN_USERNAME"] }
    if (-not $AdminPassword) { $AdminPassword = $vars["BOOTSTRAP_ADMIN_PASSWORD"] }
}
if (-not $AdminUsername -or -not $AdminPassword) { throw "Provide -AdminUsername/-AdminPassword or infrastructure/.env" }

$script:failures = 0
function Step($name) { Write-Host "`n== $name" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "   OK  $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "   FAIL $msg" -ForegroundColor Red; $script:failures++ }
function Check($cond, $msg) { if ($cond) { Ok $msg } else { Fail $msg } }

function Api($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $params = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $headers; ContentType = "application/json" }
    if ($null -ne $body) { $params["Body"] = ($body | ConvertTo-Json -Depth 10) }
    return Invoke-RestMethod @params
}

function StatusOf($method, $path, $token, $body) {
    try { Api $method $path $token $body | Out-Null; return 200 }
    catch { return [int]$_.Exception.Response.StatusCode }
}

$suffix = (Get-Random -Maximum 99999).ToString("00000")

Step "Health"
$health = Invoke-RestMethod "$BaseUrl/actuator/health"
Check ($health.status -eq "UP") "API health is UP"

Step "Admin login"
$login = Api POST "/api/auth/login" $null @{ username = $AdminUsername; password = $AdminPassword }
$admin = $login.accessToken
Check ($login.user.roles -contains "SUPER_ADMIN") "Logged in as $AdminUsername (SUPER_ADMIN)"

Step "Department and employee"
$dept = Api POST "/api/departments" $admin @{ name = "Engineering-$suffix" }
$emp = Api POST "/api/employees" $admin @{
    employeeCode = "EMP-$suffix"; fullName = "Rahul Kumar"; email = "rahul.$suffix@example.com"
    departmentId = $dept.id; jobTitle = "Software Engineer"
}
Check ($emp.id -gt 0) "Employee $($emp.employeeCode) created in $($dept.name)"

Step "Enrollment token and device registration"
$enroll = Api POST "/api/enrollment-tokens" $admin @{ description = "Smoke test"; employeeId = $emp.id; maxUses = 1; expiresInHours = 1 }
Check ($enroll.token -like "enr_*") "Enrollment token issued (shown once)"
$reg = Api POST "/api/devices/register" $null @{
    enrollmentToken = $enroll.token; hostname = "RAHUL-PC-$suffix"; osVersion = "Windows 11 Pro 23H2"
    agentVersion = "1.0.0"; macAddress = "00-15-5D-01-02-03"; hardwareId = [guid]::NewGuid().ToString()
}
Check ($reg.deviceCode -match '^DEV-\d{5}$') "Device registered as $($reg.deviceCode)"
Check ($reg.employee.id -eq $emp.id) "Device auto-assigned to $($reg.employee.fullName)"
Check ($reg.policy.idleThresholdSeconds -gt 0) "Policy delivered (revision $($reg.policyRevision))"
Check ((StatusOf POST "/api/devices/register" $null @{ enrollmentToken = $enroll.token; hostname = "X"; agentVersion = "1.0.0" }) -eq 401) "Single-use enrollment token cannot be replayed"

Step "Heartbeat and presence"
$device = $reg.deviceToken
$before = Api GET "/api/devices/$($reg.deviceId)" $admin
Check ($before.presence.status -eq "OFFLINE") "Device OFFLINE before first heartbeat"
$hb = Api POST "/api/agent/heartbeat" $device @{ status = "ACTIVE" }
Check ($hb.nextHeartbeatSeconds -eq 30) "Heartbeat accepted (next in $($hb.nextHeartbeatSeconds)s)"
$after = Api GET "/api/devices/$($reg.deviceId)" $admin
Check ($after.presence.status -eq "ACTIVE") "Device now ACTIVE"
Api POST "/api/agent/heartbeat" $device @{ status = "IDLE" } | Out-Null
$detail = Api GET "/api/employees/$($emp.id)" $admin
Check ($detail.devices[0].presence.status -eq "IDLE") "Employee detail shows IDLE"

Step "Dashboard"
$summary = Api GET "/api/dashboard/summary" $admin
Write-Host ("   total={0} online={1} idle={2} locked={3} offline={4} activeDevices={5}" -f `
    $summary.totalEmployees, $summary.online, $summary.idle, $summary.locked, $summary.offline, $summary.activeDevices)
Check ($summary.idle -ge 1) "Dashboard counts the idle employee"

Step "RBAC"
$mgrName = "manager$suffix"
Api POST "/api/users" $admin @{ username = $mgrName; email = "$mgrName@example.com"; fullName = "Test Manager"
    password = "Manager-Pass-$suffix!"; roles = @("MANAGER") } | Out-Null
$mgr = (Api POST "/api/auth/login" $null @{ username = $mgrName; password = "Manager-Pass-$suffix!" }).accessToken
Check ((Api GET "/api/employees" $mgr).totalElements -eq 0) "Manager sees no employees until assigned"
Check ((StatusOf GET "/api/audit-logs" $mgr) -eq 403) "Manager cannot read audit logs"
Check ((StatusOf GET "/api/employees" $device) -eq 403) "Device token cannot call admin APIs"
Check ((StatusOf POST "/api/agent/heartbeat" $admin @{ status = "ACTIVE" }) -eq 403) "User token cannot call agent APIs"

Step "Audit log"
$audit = Api GET "/api/audit-logs?targetType=DEVICE&targetId=$($reg.deviceId)" $admin
Check (@($audit.content | Where-Object action -eq "DEVICE_REGISTERED").Count -eq 1) "DEVICE_REGISTERED recorded"

Write-Host ""
if ($script:failures -eq 0) { Write-Host "ALL CHECKS PASSED" -ForegroundColor Green; exit 0 }
else { Write-Host "$($script:failures) CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
