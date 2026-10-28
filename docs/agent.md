# Windows agent (Milestone 2)

A transparent .NET 8 monitoring agent for company-owned Windows devices. It enrolls once, then reports presence
(active/idle/locked) via heartbeats and a timeline of session events (login, logout, lock, unlock, sleep, resume,
shutdown). Everything it does is governed by the server policy and visible in the tray.

## Projects (`agent/`)

| Project | TFM | Role |
|---|---|---|
| `EmployeeMonitor.Agent` | net8.0 | Core, platform-independent logic: API client/DTOs, policy, `ActivityTracker`, `SessionEventQueue`, `MonitoringSchedule`, and the `AgentRunner` orchestrator. Fully unit-tested. |
| `EmployeeMonitor.Sessions` | net8.0-windows | Windows implementations: `Win32InputMonitor` (idle), `WindowsSessionEventSource` (lock/unlock/logon/logoff/power/shutdown), `DpapiSecureStore` (DPAPI), `WindowsSystemInfo`. |
| `EmployeeMonitor.Service` | net8.0-windows | Generic-host worker that runs as a Windows Service or a console app. |
| `EmployeeMonitor.Tray` | net8.0-windows | Visible "Company Monitoring Active" tray indicator. |
| `EmployeeMonitor.Agent.Tests` | net8.0 | xUnit unit tests for the core logic. |

Other projects from the master layout (`Capture`, `Network`, `USB`, `Applications`, `Zscaler`, `Installer`) are
added in Milestones 4–7.

## What it monitors — and what it never does

Reports: work session events, and active/idle derived **only from the time since last input**. Planned for later
milestones (each policy-gated): application usage, website domains, USB, network, Zscaler status, screenshots, live
view.

Never: keystroke content/keylogging, passwords or credentials, clipboard, file contents, private messages, webcam
or microphone. Idle detection uses `GetLastInputInfo`, which exposes only the timestamp of the last input, not what
was typed. The agent is not stealthy and does not resist uninstallation.

## How it works

```
enroll (once) ──▶ POST /api/devices/register ──▶ device token stored via DPAPI (machine scope)
loop:
  heartbeat  every policy.heartbeatIntervalSeconds ──▶ POST /api/agent/heartbeat {status}
             response carries policyRevision; on change ──▶ GET /api/agent/policy (re-baseline activity)
  activity   sample GetLastInputInfo every ActivityPollSeconds ──▶ ACTIVE/IDLE transitions
  session    OS signals (lock/unlock/logon/logoff/sleep/resume/shutdown) ──▶ events
  upload     flush queued session events every EventFlushSeconds ──▶ POST /api/agent/session-events
shutdown ──▶ AGENT_STOP + final flush
```

- **Idempotent events:** every session event has a client-generated id; retried batches are de-duplicated server-side.
- **Policy-gated:** session/activity capture honours `sessionMonitoring` and the work-hours/work-days window
  (`MonitoringSchedule`); heartbeats always run so presence stays accurate. On a policy change that opens the window,
  the agent re-emits the current state so the timeline is never left guessing.
- **Resilient:** heartbeat/upload failures are retried; a rejected device token (disabled/retired) stops monitoring
  cleanly and logs that re-enrollment is required.

## Run it in the user session (development)

This is what `scripts/agent-smoke.ps1` exercises end-to-end.

```powershell
$env:EM_ENROLLMENT_TOKEN = "enr_..."         # from POST /api/enrollment-tokens
$env:Agent__BackendBaseUrl = "http://localhost:8080"
dotnet run --project agent/EmployeeMonitor.Service
# In another terminal, launch the visible tray:
dotnet run --project agent/EmployeeMonitor.Tray
```

The device credential is written to `%ProgramData%\EmployeeMonitor\device.dat` (DPAPI, machine scope). Delete it to
force re-enrollment.

## Install as a Windows Service (manual)

```powershell
# Elevated PowerShell
.\scripts\install-service.ps1 -EnrollmentToken enr_... -BackendBaseUrl https://monitor.company.com
.\scripts\uninstall-service.ps1 -RemoveData
```

### Session 0 limitation (important)

A Windows Service runs in **Session 0** as LocalSystem. From there, `GetLastInputInfo` and the lock/unlock session
notifications observe Session 0, not the interactive user — so **enrollment, policy sync, heartbeat and presence
work, but input-idle and lock/unlock are not accurate**. The correct production design (Milestone 7) keeps the
Session-0 service for always-on tasks and runs a lightweight **per-session helper** (launched at logon in the user's
session) that reports input-idle and session-switch signals to the service. The code is already structured for this:
the idle and session-event sources sit behind `IInputMonitor` / `ISystemEventSource`, so the helper simply hosts the
same implementations in the user session. For development and the smoke test we run the agent in the user session,
where everything works.

## Configuration (`appsettings.json` / environment)

| Key | Env | Default | Meaning |
|---|---|---|---|
| `Agent:BackendBaseUrl` | `Agent__BackendBaseUrl` | `http://localhost:8080` | Backend URL (HTTPS in production) |
| `Agent:EnrollmentToken` | `EM_ENROLLMENT_TOKEN` | — | First-run enrollment token |
| `Agent:DataDirectory` | `Agent__DataDirectory` | `%ProgramData%\EmployeeMonitor` | Credential location |
| `Agent:ActivityPollSeconds` | `Agent__ActivityPollSeconds` | 5 | Idle sampling interval |
| `Agent:EventFlushSeconds` | `Agent__EventFlushSeconds` | 15 | Session-event upload interval |

Heartbeat interval and idle threshold come from the server policy (with safe fallbacks until the first sync).

## Tests

```powershell
cd agent
dotnet test                                   # 25 unit tests (ActivityTracker, queue, schedule, AgentRunner)
cd ..
powershell -ExecutionPolicy Bypass -File .\scripts\agent-smoke.ps1   # live end-to-end against the backend
```

The smoke test enrolls a fresh device, confirms online presence from heartbeats, applies a 24/7 window, and verifies
`AGENT_START` and an activity event reach `GET /api/employees/{id}/timeline`, with `session-summary` showing online.
