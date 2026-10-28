# Admin Windows application (Milestone 3)

A WinUI 3 (Windows App SDK) desktop app for administrators: sign in, a realtime dashboard, and the employee and
device lists. It follows MVVM with the entire logic layer in a platform-independent library so it can be unit- and
integration-tested without a UI.

## Projects (`admin-windows/`)

| Project | TFM | Role |
|---|---|---|
| `EmployeeMonitor.Admin.Core` | net8.0 | API client, token/session, realtime client, and all view models (MVVM). No UI dependency. |
| `EmployeeMonitor.Admin` | net8.0-windows10.0.19041 | WinUI 3 views (XAML) + DI, navigation, converters. Thin layer over the view models. |
| `EmployeeMonitor.Admin.Core.Tests` | net8.0 | xUnit tests for view models and the API client (incl. refresh-on-401). |
| `tools/AdminLiveCheck` | net8.0 | Headless end-to-end check of the client stack against a running backend. |

## Backend realtime (added this milestone)

A WebSocket endpoint streams presence deltas so the dashboard updates without polling.

- **Endpoint:** `GET ws(s)://<host>/ws/events`
- **Auth:** the handshake requires a user JWT (via `Authorization: Bearer` header or `?access_token=` query) and the
  `REPORT_READ` permission. Unauthenticated handshakes are rejected with 401.
- **Filtering:** each connection only receives events for employees it may see. Admin/IT/HR (`EMPLOYEE_READ_ALL`)
  see all; managers see their assigned employees; an employee sees their own. A Redis `em:device-employee` map lets
  even expiry-driven `DEVICE_OFFLINE` events carry the employee for filtering.
- **Transport:** the API subscribes to the Redis `em:events` channel, so events published by any API instance reach
  clients connected to every instance.
- **Messages:** `{ "type": "DEVICE_ONLINE" | "DEVICE_STATUS_CHANGED" | "DEVICE_OFFLINE",
  "payload": { deviceId, employeeId, status, previousStatus, at } }`. Initial state comes from REST; the socket
  carries deltas.

## Client architecture

```
LoginWindow ── LoginViewModel ── AdminApiClient ──REST──▶ backend
                                    │ (tokens) 
MainWindow (NavigationView) ── ShellViewModel ── RealtimeClient ──WSS──▶ /ws/events
   ├─ DashboardPage  ── DashboardViewModel ─┐
   ├─ EmployeesPage  ── EmployeesViewModel   │ consume presence deltas,
   └─ DevicesPage    ── DevicesViewModel ────┘ update rows + counts live
```

- **Auth & refresh:** `AdminApiClient` attaches the access token and, on a 401, transparently refreshes once using
  the rotating refresh token before retrying. A failed refresh clears the session.
- **Realtime:** `RealtimeClient` connects with the access token, auto-reconnects with capped backoff, and refetches
  the dashboard snapshot on each reconnect to close any gap. Callbacks are marshalled to the UI thread via
  `IUiDispatcher` (so view models stay UI-framework-agnostic and testable).
- **Dashboard counts** mirror the server exactly: an employee's state is the best status among their devices; a delta
  updates the affected device row and recomputes the headline counts.
- **Scoping** is enforced server-side (REST results and WS events are already filtered), so the UI simply renders
  what it receives.

## Run it

Prerequisites: the backend stack running (`infrastructure/docker compose up -d`) and the **Windows App SDK runtime**
installed on the machine (the app is unpackaged). Build requires the .NET 8 SDK with the Windows 10 SDK targeting
pack (restored automatically here).

```powershell
cd admin-windows
dotnet build EmployeeMonitor.Admin\EmployeeMonitor.Admin.csproj -c Debug -p:Platform=x64
# Run the built app:
.\EmployeeMonitor.Admin\bin\x64\Debug\net8.0-windows10.0.19041.0\EmployeeMonitorAdmin.exe
```

Sign in with an admin account (e.g. the bootstrap `admin`). The backend URL defaults to `http://localhost:8080`;
override with `EM_ADMIN_BACKEND_URL` or `admin-windows/EmployeeMonitor.Admin/appsettings.json`.

## Tests

```powershell
cd admin-windows
dotnet test EmployeeMonitor.Admin.Core.Tests\EmployeeMonitor.Admin.Core.Tests.csproj   # expect Passed: 11

# Headless end-to-end of login + REST + realtime against the running backend:
$p = (Get-Content ..\infrastructure\.env | Where-Object { $_ -match '^BOOTSTRAP_ADMIN_PASSWORD=(.*)' } | ForEach-Object { $Matches[1] })
dotnet run --project tools\AdminLiveCheck -c Release -- http://localhost:8080 admin $p   # expect ADMIN LIVE CHECK PASSED
```

`AdminLiveCheck` logs in through the real `AdminApiClient`, loads the dashboard and devices, connects the
`RealtimeClient`, enrolls a throwaway device and heartbeats it, and asserts the resulting presence event arrives over
the WebSocket.

## Note on UI verification

The view models, API client, realtime client and the backend WebSocket are all covered by automated tests that run
headlessly. The XAML views are verified by a successful WinUI build (x:Bind is compile-checked), since interacting
with a desktop GUI cannot be automated in this environment. Run the app as above to see it live.
