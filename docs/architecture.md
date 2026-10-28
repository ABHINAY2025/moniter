# Architecture

```text
 Admin app (WinUI 3) ──HTTPS/WSS──┐
                                  ▼
                        ┌───────────────────┐
 Windows agent ─HTTPS──▶│  Spring Boot API  │──▶ PostgreSQL  (system of record)
 (device token)         │                   │──▶ Redis       (presence, rate limits, pub/sub)
                        └───────────────────┘──▶ S3/MinIO    (screenshots, Milestone 5)
```

## Backend modules (package `com.employeemonitor.api`)

| Package | Responsibility |
|---|---|
| `auth` | Login, rotating refresh tokens, logout, password change, bootstrap SUPER_ADMIN |
| `security` | Bearer filter (user JWT vs. device token), security config, HTTPS enforcement |
| `users` | Users, roles, permissions, manager → employee assignments |
| `employees` | Employees, departments, row-level access (`EmployeeAccessService`), self-service |
| `devices` | Enrollment tokens, device registration, device tokens, device admin, agent endpoints |
| `policies` | Monitoring policy (JSONB), default + per-device override, revisioning |
| `presence` | Realtime ACTIVE / IDLE / LOCKED / OFFLINE state in Redis, transition events |
| `dashboard` | Headline counts |
| `audit` | Append-only audit log, query API |

Future milestones add `sessions`, `applications`, `websites`, `usb`, `network`, `zscaler`, `screenshots`, `liveview`, `alerts`, `reports` as sibling packages.

## Two kinds of principals

| Principal | Credential | Authority | Allowed paths |
|---|---|---|---|
| `AuthenticatedUser` | short-lived JWT (15 min) + rotating refresh token (7 days) | `ROLE_USER`, `ROLE_<role>`, one authority per permission | `/api/**` except `/api/agent/**` |
| `DevicePrincipal` | opaque `dvt_…` device token (hash stored; 365 days; revocable) | `ROLE_DEVICE` | `/api/agent/**` only |

User accounts are re-loaded on every request, so disabling a user or changing roles takes effect immediately rather than when the JWT expires.

## Authorization model

Permissions are seeded in `V2__seed_rbac_and_default_policy.sql`:

| Role | Permissions |
|---|---|
| SUPER_ADMIN | all |
| IT_ADMIN | EMPLOYEE_READ_ALL, DEVICE_READ, DEVICE_WRITE, ENROLLMENT_MANAGE, POLICY_READ, POLICY_WRITE, REPORT_READ, SELF_READ |
| HR_ADMIN | EMPLOYEE_READ_ALL, EMPLOYEE_WRITE, DEPARTMENT_WRITE, POLICY_READ, REPORT_READ, SELF_READ |
| MANAGER | EMPLOYEE_READ_ASSIGNED, REPORT_READ, SELF_READ |
| EMPLOYEE | SELF_READ |

Enforcement happens in three layers:

1. **URL rules** in `SecurityConfig`, which run before request bodies are parsed, so unauthorized callers get 403 rather than validation details.
2. **`@PreAuthorize`** on every controller method.
3. **Row-level scoping** in `EmployeeAccessService`. Managers see only employees in `manager_assignments`, and employees see only themselves. Inaccessible records return **404**, so ids cannot be probed. Live view (Milestone 6) will additionally require `manager_assignments.live_view_allowed`.

## Device lifecycle

```text
IT admin ── POST /api/enrollment-tokens ──▶ enr_… (shown once, limited uses, expiry, optional employee)
Installer ─ POST /api/devices/register {enrollmentToken, hostname, hardwareId, …}
          ◀─ {deviceId, deviceCode DEV-10001, deviceToken dvt_…, employee, policy, policyRevision}
Agent ──── POST /api/agent/heartbeat every heartbeatIntervalSeconds {status: ACTIVE|IDLE|LOCKED}
          ◀─ {policyRevision, nextHeartbeatSeconds}  → GET /api/agent/policy when the revision changes
Admin ──── PUT /api/devices/{id}/status DISABLED   → token stops authenticating, presence → OFFLINE
           DELETE /api/devices/{id}                → RETIRED, tokens revoked, history kept
```

Reinstalling on the same machine (same `hardwareId`) re-uses the device record and revokes the previous token.

## Presence model (Redis)

| Key | Content |
|---|---|
| `em:presence:device:{id}` | JSON `Presence`; TTL = `PRESENCE_OFFLINE_TIMEOUT` (90 s), refreshed by every heartbeat |
| `em:presence:online` | set of device ids believed online |
| `em:events` (pub/sub) | `DEVICE_ONLINE`, `DEVICE_STATUS_CHANGED`, `DEVICE_OFFLINE` envelopes |
| `em:ratelimit:*` | fixed-window counters for login / registration |

A device is offline when its key has expired. A sweeper (every 15 s) finds ids in the online set without a key and emits `DEVICE_OFFLINE`. Only the instance whose `SREM` succeeds publishes, so several API instances can run without duplicate events. Milestone 3 bridges `em:events` to the admin app over WebSocket.

## Policy model

`monitoring_policies.settings` is JSONB validated by `PolicySettings`. Every category has its own switch, and there are work-hours, work-days, timezone and retention settings. A device uses its own override when one exists, otherwise the default policy. Each policy carries an optimistic-lock `version`, and agents receive `policyRevision = "{policyId}-{version}"` on every heartbeat, so a change reaches all agents within one heartbeat interval without rebuilding anything.
