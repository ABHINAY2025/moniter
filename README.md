# Employee Monitoring & Endpoint Management Platform

Transparent, policy-governed monitoring of **company-owned Windows devices**, with a Spring Boot backend, a Windows agent, and a WinUI 3 admin application.

The platform is built for authorized, visible monitoring. It does **not** and will not implement keylogging, password/credential capture, hidden screen capture, webcam/microphone access, private-message collection, cookie/token extraction, Zscaler/antivirus bypass, or anti-uninstall persistence. See [docs/security.md](docs/security.md).

## Status

| Milestone | Scope | Status |
|---|---|---|
| 1 | Backend: Spring Boot, PostgreSQL, Redis, auth, RBAC, employees, devices, enrollment, policies, presence, audit | **Done** |
| 2 | Windows agent (.NET 8): enrollment, heartbeat/presence, policy sync, login/logout/lock, active/idle, session timeline, tray indicator | **Done** |
| 3 | Admin app (WinUI 3): login, realtime dashboard, employees, devices; backend presence WebSocket | **Done** |
| 4 | Monitoring: application usage, website domains, USB, network, Zscaler status (agent + backend + admin) | **Done** |
| 5 | Screenshots: periodic capture, encrypted object storage, audited secure viewer, retention | **Done** |
| 6 | Live view: authorized signaling + session, screen streaming, on-device indicator, time limit, full audit | **Done** |
| 7 | Deployment: `EmployeeMonitorSetup.exe` installer, user-session execution (resolves Session-0), signed auto-update | **Done** |
| + | Website restriction & access monitoring: domain allow/block/monitor policies, Chrome/Edge enforcement, live activity (agent + backend + admin) | **Done** |

## Repository layout

```text
employee-monitoring-platform/
├── backend/employee-monitor-api/   Spring Boot 3.5 / Java 21 API  (Milestone 1)
│   └── src/main/resources/db/migration/   Flyway migrations (source of truth)
├── database/                       Notes on schema, partitioning and retention
├── infrastructure/                 docker-compose.yml, Redis config, .env.example
├── scripts/smoke-test.ps1          End-to-end check against a running stack
├── docs/                           architecture, api, installation, deployment, security
├── agent/                          Windows agent (Milestone 2)
└── admin-windows/                  WinUI 3 admin app (Milestone 3): Admin.Core (MVVM) + Admin (WinUI) + tools/AdminLiveCheck
```

## Quick start on Windows

Prerequisites: **Docker Desktop** (running), and for development/tests **JDK 21** and **Maven 3.9+**.

```powershell
cd employee-monitoring-platform\infrastructure
copy .env.example .env
notepad .env          # replace every change-me value; see notes in the file
docker compose up -d --build
```

If PostgreSQL or Redis already run natively on your PC, set `POSTGRES_HOST_PORT` / `REDIS_HOST_PORT` in `.env` (e.g. `55432` / `56379`) to avoid the port clash.

Check it is up (first start takes ~1 minute while the image builds):

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health      # -> status: UP
docker compose logs api | Select-String "Bootstrap SUPER_ADMIN"
```

Expected log line: `Bootstrap SUPER_ADMIN 'admin' created. Change the password after first login.`

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

### Run the end-to-end smoke test

```powershell
cd employee-monitoring-platform
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

It logs in as the bootstrap admin, creates a department and employee, issues an enrollment token, registers a simulated device, sends `ACTIVE`/`IDLE` heartbeats, and verifies presence, the dashboard, RBAC separation and the audit log. Expected ending:

```text
== Audit log
   OK  DEVICE_REGISTERED recorded

ALL CHECKS PASSED
```

### Run the Windows agent (Milestone 2)

Enroll and run the agent in your user session against the running backend (full guide: [docs/agent.md](docs/agent.md)):

```powershell
cd employee-monitoring-platform
powershell -ExecutionPolicy Bypass -File .\scripts\agent-smoke.ps1   # live end-to-end: expect AGENT SMOKE TEST PASSED
```

Or manually:

```powershell
$env:EM_ENROLLMENT_TOKEN = "enr_..."        # from POST /api/enrollment-tokens (via the admin API / Swagger)
dotnet run --project agent\EmployeeMonitor.Service   # heartbeats + session events
dotnet run --project agent\EmployeeMonitor.Tray      # visible "Company Monitoring Active" indicator
```

Install as a Windows Service with `scripts\install-service.ps1` (elevated). Note the Session-0 limitation for idle/lock detection described in [docs/agent.md](docs/agent.md).

For production, build and use the installer (full user-session fidelity + signed auto-update) — see [docs/deployment-agent.md](docs/deployment-agent.md):

```powershell
.\scripts\build-installer.ps1 -Version 1.0.0      # -> EmployeeMonitorSetup.exe (needs Inno Setup for the final step)
# or install directly from source (elevated):
.\scripts\install-agent.ps1 -EnrollmentToken enr_... -BackendBaseUrl https://monitor.company.com
```

### Run the admin app (Milestone 3)

Needs the backend running and the Windows App SDK runtime installed. Full guide: [docs/admin-app.md](docs/admin-app.md).

```powershell
cd employee-monitoring-platform\admin-windows
dotnet build EmployeeMonitor.Admin\EmployeeMonitor.Admin.csproj -c Debug -p:Platform=x64
.\EmployeeMonitor.Admin\bin\x64\Debug\net8.0-windows10.0.19041.0\EmployeeMonitorAdmin.exe   # sign in as admin
```

Headless end-to-end check of the admin client stack (login + REST + realtime WebSocket):

```powershell
$p = (Get-Content ..\infrastructure\.env | ? { $_ -match '^BOOTSTRAP_ADMIN_PASSWORD=(.*)' } | % { $Matches[1] })
dotnet run --project tools\AdminLiveCheck -c Release -- http://localhost:8080 admin $p   # expect ADMIN LIVE CHECK PASSED
```

### Run the automated tests

Docker must be running (backend integration tests start throwaway PostgreSQL 16 and Redis 7 containers via Testcontainers).

```powershell
cd employee-monitoring-platform\backend\employee-monitor-api
mvn test                     # backend: expect Tests run: 69, Failures: 0, Errors: 0

cd ..\..\agent
dotnet test                  # agent: expect Passed: 43

cd ..\admin-windows
dotnet test EmployeeMonitor.Admin.Core.Tests\EmployeeMonitor.Admin.Core.Tests.csproj   # admin: expect Passed: 16
```

| Suite | Covers |
|---|---|
| `AuthIntegrationTest` | login, generic errors for unknown user / bad password, refresh rotation, refresh-token reuse detection, logout, lockout, disabled users, validation |
| `RbacIntegrationTest` | HR vs IT permissions, manager sees only assigned employees, employee sees only self, restricted admin areas, "viewed employee" audit |
| `DeviceIntegrationTest` | enrollment validation, single-use tokens, registration + policy, heartbeat → Redis presence → dashboard, credential separation, retire/disable, reinstall token rotation, device assignment |
| `PolicyIntegrationTest` | per-device overrides, revision changes seen by the agent, validation, audit of policy changes |
| `JwtServiceTest`, `PolicySettingsValidationTest` | token signing/expiry/tampering, policy validation rules |

### Run the API outside Docker (for development)

```powershell
cd infrastructure; docker compose up -d postgres redis; cd ..\backend\employee-monitor-api
$env:DB_URL="jdbc:postgresql://localhost:55432/employee_monitor"   # use your POSTGRES_HOST_PORT
$env:DB_PASSWORD="<DB_PASSWORD from .env>"
$env:REDIS_PORT="56379"; $env:REDIS_PASSWORD="<REDIS_PASSWORD from .env>"
$env:JWT_SECRET="<JWT_SECRET from .env>"
mvn spring-boot:run
```

## Documentation

- [docs/architecture.md](docs/architecture.md) — components, data flow, presence model
- [docs/agent.md](docs/agent.md) — Windows agent (Milestone 2)
- [docs/admin-app.md](docs/admin-app.md) — WinUI 3 admin app & realtime WebSocket (Milestone 3)
- [docs/monitoring.md](docs/monitoring.md) — application/website/USB/network/Zscaler monitoring (Milestone 4)
- [docs/web-access.md](docs/web-access.md) — website restriction & access monitoring: policies, Chrome/Edge enforcement, activity
- [docs/screenshots.md](docs/screenshots.md) — screenshots: capture, encrypted storage, audited viewer (Milestone 5)
- [docs/live-view.md](docs/live-view.md) — authorized live screen view: signaling, indicator, audit (Milestone 6)
- [docs/deployment-agent.md](docs/deployment-agent.md) — installer, user-session deployment, signed auto-update (Milestone 7)
- [docs/api.md](docs/api.md) — endpoints with request/response examples
- [docs/installation.md](docs/installation.md) — local setup and troubleshooting
- [docs/deployment.md](docs/deployment.md) — production deployment
- [docs/security.md](docs/security.md) — security controls and privacy boundaries
