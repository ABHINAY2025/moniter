# API reference (Milestone 1)

The live, complete schema for every DTO is at **`/swagger-ui.html`** (OpenAPI JSON at `/v3/api-docs`). This page summarises each endpoint with examples.

Conventions:
- JSON everywhere; timestamps are ISO-8601 UTC.
- Errors are RFC 9457 problem documents with a stable `code`:
  ```json
  { "status": 400, "title": "Bad Request", "detail": "Request validation failed",
    "code": "VALIDATION_FAILED", "errors": { "email": "must be a well-formed email address" } }
  ```
- Paged responses: `{ "content": [...], "page": 0, "size": 50, "totalElements": 1, "totalPages": 1 }` (`?page=&size=`, max size 200).
- User endpoints take `Authorization: Bearer <accessToken>`. Agent endpoints take `Authorization: Bearer dvt_…`.

## Authentication

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | none | rate-limited per IP; 5 failures lock the account for 15 min |
| POST | `/api/auth/refresh` | none | rotates the refresh token; reusing an old one revokes the session family |
| POST | `/api/auth/logout` | none | revokes the refresh token's family |
| GET | `/api/auth/me` | user | |
| POST | `/api/auth/change-password` | user | revokes all of the user's refresh tokens |

```http
POST /api/auth/login
{ "username": "admin", "password": "…" }

200
{ "tokenType": "Bearer",
  "accessToken": "eyJ…", "accessTokenExpiresAt": "2026-10-06T18:05:00Z",
  "refreshToken": "rt_…", "refreshTokenExpiresAt": "2026-10-13T17:50:00Z",
  "user": { "id": 1, "username": "admin", "fullName": "Administrator", "email": "…",
            "employeeId": null, "roles": ["SUPER_ADMIN"], "permissions": ["AUDIT_READ", "…"] } }
```

Invalid credentials, unknown users, disabled and locked accounts all return the same `401 INVALID_CREDENTIALS`.

## Users and manager assignments (`USER_MANAGE`)

| Method | Path | Body |
|---|---|---|
| GET | `/api/users?q=` | |
| GET | `/api/users/{id}` | |
| POST | `/api/users` | `{ username, email, fullName, password (≥12), roles: ["MANAGER"], employeeId? }` |
| PATCH | `/api/users/{id}` | any of `{ email, fullName, enabled, roles, employeeId, clearEmployee, newPassword }` |
| GET | `/api/users/{id}/assigned-employees` | |
| PUT | `/api/users/{id}/assigned-employees/{employeeId}` | `{ "liveViewAllowed": false }` (user must have MANAGER) |
| DELETE | `/api/users/{id}/assigned-employees/{employeeId}` | |

## Employees and departments

| Method | Path | Permission |
|---|---|---|
| GET | `/api/employees?q=&departmentId=&status=` | READ_ALL / READ_ASSIGNED / SELF_READ (results scoped) |
| GET | `/api/employees/{id}` | same; audited as `EMPLOYEE_VIEWED` |
| POST | `/api/employees` | EMPLOYEE_WRITE |
| PATCH | `/api/employees/{id}` | EMPLOYEE_WRITE |
| GET | `/api/departments` | any employee-read or REPORT_READ |
| POST / PUT | `/api/departments[/{id}]` | DEPARTMENT_WRITE |
| GET | `/api/self` | SELF_READ: own employee record, devices and the policy applied to each |

```http
POST /api/employees
{ "employeeCode": "EMP-1029", "fullName": "Rahul Kumar", "email": "rahul@company.com",
  "departmentId": 1, "jobTitle": "Software Engineer" }

GET /api/employees/3   →
{ "employee": { "id": 3, "employeeCode": "EMP-1029", "fullName": "Rahul Kumar", "email": "…",
                "department": { "id": 1, "name": "Engineering" }, "jobTitle": "…", "status": "ACTIVE", … },
  "devices": [ { "id": 1, "deviceCode": "DEV-10001", "hostname": "RAHUL-PC", "osVersion": "Windows 11 Pro 23H2",
                 "agentVersion": "1.0.0", "macAddress": "…", "ipAddress": "10.0.4.17", "status": "ACTIVE",
                 "employee": { "id": 3, "employeeCode": "EMP-1029", "fullName": "Rahul Kumar", "department": "Engineering" },
                 "registeredAt": "…", "lastSeenAt": "…",
                 "presence": { "status": "ACTIVE", "online": true, "lastHeartbeatAt": "…" } } ] }
```

## Enrollment and devices

| Method | Path | Permission |
|---|---|---|
| POST | `/api/enrollment-tokens` | ENROLLMENT_MANAGE: `{ description?, employeeId?, maxUses? (1), expiresInHours? (72) }` → token shown **once** |
| GET | `/api/enrollment-tokens` | ENROLLMENT_MANAGE (no plaintext tokens) |
| DELETE | `/api/enrollment-tokens/{id}` | ENROLLMENT_MANAGE (revoke) |
| POST | `/api/devices/register` | public; requires a valid enrollment token |
| GET | `/api/devices?q=&status=&employeeId=&unassigned=` | DEVICE_READ |
| GET | `/api/devices/{id}` | DEVICE_READ |
| PUT | `/api/devices/{id}/assignment` | DEVICE_WRITE: `{ "employeeId": 3 }` or `null` to unassign |
| PUT | `/api/devices/{id}/status` | DEVICE_WRITE: `{ "status": "ACTIVE" \| "DISABLED" \| "RETIRED" }` |
| DELETE | `/api/devices/{id}` | DEVICE_WRITE (retire + revoke tokens) |

```http
POST /api/devices/register
{ "enrollmentToken": "enr_…", "hostname": "RAHUL-PC", "osVersion": "Windows 11 Pro 23H2",
  "agentVersion": "1.0.0", "macAddress": "00-15-5D-01-02-03", "ipAddress": "10.0.4.17",
  "hardwareId": "<MachineGuid>" }

201
{ "deviceId": 1, "deviceCode": "DEV-10001", "deviceToken": "dvt_…", "deviceTokenExpiresAt": "2027-10-06T…",
  "employee": { "id": 3, "employeeCode": "EMP-1029", "fullName": "Rahul Kumar", "department": "Engineering" },
  "policyRevision": "1-1", "policy": { …PolicySettings… } }
```

## Agent endpoints (device token)

| Method | Path | Body / response |
|---|---|---|
| POST | `/api/agent/heartbeat` | `{ "status": "ACTIVE" \| "IDLE" \| "LOCKED", "timestamp"?, "agentVersion"?, "ipAddress"? }` → `{ serverTime, policyRevision, nextHeartbeatSeconds }` |
| GET | `/api/agent/policy` | effective policy (re-fetch when `policyRevision` changes) |
| GET | `/api/agent/me` | device identity and assigned employee (for the tray panel) |
| POST | `/api/agent/session-events` | `{ "events": [ { "clientEventId": "<uuid>", "type": "LOGIN", "occurredAt": "<iso>" } ] }` → `{ accepted, duplicates, rejected }` |

Event `type` is one of `LOGIN, LOGOUT, LOCK, UNLOCK, SHUTDOWN, SLEEP, RESUME, ACTIVE, IDLE, AGENT_START, AGENT_STOP`. Ingest is idempotent per `(device, clientEventId)`; events older than 7 days or more than 5 minutes in the future are rejected.

### Session timeline and summary (admin)

| Method | Path | Permission |
|---|---|---|
| GET | `/api/employees/{id}/timeline?date=YYYY-MM-DD&tz=Asia/Kolkata` | employee-read (scoped) |
| GET | `/api/employees/{id}/session-summary?date=YYYY-MM-DD&tz=Asia/Kolkata` | employee-read (scoped) |

`date`/`tz` default to today in UTC. The summary derives durations from the ordered event stream:

```json
{ "employeeId": 3, "date": "2026-10-06", "timezone": "Asia/Kolkata",
  "sessionStart": "...", "sessionEnd": "...", "sessionSeconds": 14400,
  "activeSeconds": 10800, "idleSeconds": 1800, "lockedSeconds": 1800, "currentlyOnline": false }
```

## Policies

| Method | Path | Permission |
|---|---|---|
| GET | `/api/policies` | POLICY_READ: shared policies |
| GET / PUT | `/api/policies/default` | POLICY_READ / POLICY_WRITE |
| GET | `/api/policies/{deviceId}` | POLICY_READ: effective policy for a device |
| PUT | `/api/policies/{deviceId}` | POLICY_WRITE: create/update the device override |
| DELETE | `/api/policies/{deviceId}` | POLICY_WRITE: drop the override (back to default) |

`PolicySettings` (all fields required):

```json
{ "sessionMonitoring": true, "idleThresholdSeconds": 300,
  "applicationMonitoring": true, "websiteMonitoring": false, "usbMonitoring": true,
  "networkMonitoring": true, "zscalerMonitoring": true,
  "screenshots": false, "screenshotIntervalSeconds": 900,
  "liveView": false, "liveViewMaxMinutes": 30,
  "heartbeatIntervalSeconds": 30, "policySyncIntervalSeconds": 300,
  "monitoringStart": "09:00", "monitoringEnd": "18:00",
  "workDays": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"], "timezone": "Asia/Kolkata",
  "retentionDays": 90, "screenshotRetentionDays": 30 }
```

Rules: `idleThresholdSeconds` 60–3600; `screenshotIntervalSeconds` ∈ {300, 600, 900, 1800}; `heartbeatIntervalSeconds` 10–300; `monitoringEnd` after `monitoringStart`; `timezone` must be a valid IANA id. Every change is audited as `POLICY_CHANGED` with before/after values.

## Dashboard and audit

| Method | Path | Permission |
|---|---|---|
| GET | `/api/dashboard/summary` | REPORT_READ (scoped to visible employees) |
| GET | `/api/audit-logs?actorUserId=&action=&targetType=&targetId=&from=&to=` | AUDIT_READ |

```json
{ "totalEmployees": 120, "online": 94, "idle": 18, "locked": 0, "offline": 8,
  "activeDevices": 108, "onlineDevices": 101, "unassignedDevices": 2, "generatedAt": "…" }
```

Audited actions include: `AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILURE`, `AUTH_LOGOUT`, `AUTH_REFRESH_TOKEN_REUSE`, `AUTH_PASSWORD_CHANGED`, `USER_CREATED`, `USER_UPDATED`, `MANAGER_ASSIGNMENT_SET`, `MANAGER_ASSIGNMENT_REMOVED`, `EMPLOYEE_VIEWED`, `EMPLOYEE_CREATED`, `EMPLOYEE_UPDATED`, `DEPARTMENT_CREATED`, `DEPARTMENT_UPDATED`, `ENROLLMENT_TOKEN_CREATED`, `ENROLLMENT_TOKEN_REVOKED`, `DEVICE_REGISTERED`, `DEVICE_REGISTRATION_FAILED`, `DEVICE_ASSIGNED`, `DEVICE_STATUS_CHANGED`, `DEVICE_REMOVED`, `POLICY_CHANGED`.
