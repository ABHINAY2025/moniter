# Spec vs. built — gap analysis

Comparison of the **Quantum Data Leap** project brief against what exists in this repository, as of this commit.
Evidence is the actual code under `backend/`, `agent/`, `admin-windows/`, `docs/`, and `infrastructure/`.

## Summary

The **backend, the employee Windows agent, and the admin Windows application are built, tested, and working
end-to-end** (an admin can enroll a device, the agent heartbeats, data persists in PostgreSQL, both the admin EXE
shows live status, policies/alerts/audit work, and signed agent updates work). The **main missing pieces are the
browser Admin Web UI, production deployment/HTTPS/domains, trusted code-signing, and CI** — the items that turn a
working local system into a hosted product.

## A. Backend API — ✅ substantially complete

| Brief item | Status | Where |
|---|---|---|
| Auth, sessions/tokens (JWT + rotating refresh) | ✅ | `security/`, `AuthIntegrationTest` |
| RBAC (permissions, roles, `@PreAuthorize`, row scoping) | ✅ | `users/`, `RbacIntegrationTest` |
| Employees / departments / manager assignment | ✅ | `employees/` |
| Device registration, one-time enrollment tokens, revocation | ✅ | `devices/`, `DeviceIntegrationTest` |
| Heartbeats, online/offline, stale threshold | ✅ | `presence/` (Redis), `PRESENCE_OFFLINE_TIMEOUT` |
| Attendance sessions / presence timeline | ✅ | session-events, `/timeline` |
| App usage (no window titles), website **domains** (no URLs) | ✅ | `monitoring/` |
| USB events (metadata), network status, Zscaler status | ✅ | `monitoring/` |
| Policy management + working-hour schedules | ✅ | `policies/` |
| Alerts (USB, flagged domain, offline-in-hours) | ✅ | `alerts/` |
| **Website restriction + access monitoring** (allow/block/monitor) | ✅ | `webaccess/` (V9) |
| WebSocket live status | ✅ | `/ws/events`, `/ws/live-view` |
| Append-only audit incl. denied actions | ✅ | `audit_logs` |
| Version reporting + signed update metadata | ✅ | `updates/`, `/api/agent/update` |
| Health, Swagger, rate limits, central error handling, validation | ✅ | actuator, springdoc |
| Flyway migrations, indexes, retention | ✅ | `db/migration/V1..V9` |

**Naming/shape differences (functionally equivalent, not gaps):** the brief lists idealized table names
(`activity_events`, `application_usage_summaries`, `admin_commands`, `application_releases`, …). The build uses its
own names (`session_events`, `application_usage`, `website_usage`, `enrollment_tokens`, `agent_versions`, …). Most
PKs are `BIGINT` with **non-guessable tokens** for enrollment/device auth rather than UUID PKs throughout.

## B. Employee Windows agent — ✅ complete

.NET 8 Worker + WinUI tray. Enrollment, device auth (DPAPI-stored token), visible **"Company Monitoring Active"**
tray indicator, heartbeats + reconnect, online/idle/locked/disconnected, login/logout/lock events, foreground app
usage, domain-level website activity (DNS-Client log), USB, network, Zscaler status, policy work-hours gating,
offline queue with de-dup, signed update client, Eco-QoS low power. Runs in the interactive user session. Does **not**
keylog, capture credentials/cookies/messages/content, or use webcam/mic. Remote support is **consent-gated** live
input over the live-view WS with an on-screen prompt.

## C. Admin Windows application — ✅ complete

WinUI 3. Sign-in, live dashboard, employees/devices, device detail + last-seen, timelines, app/domain usage, USB +
configured alerts (live feed + ack), policy management, **Web access** section, live view, consent-based remote
support, in-app device enrollment. All privileged ops authorized by the backend (UI checks are not trusted).

## D. Admin Web UI (React/TypeScript) — ❌ NOT built  ← biggest gap

There is **no `admin-web/` application**. The brief (§3.D) wants a browser dashboard served at
`admin.quantumdataleap.ai` with overview metrics, employee/device tables (search/filter/pagination), detail pages,
alert feed, policies, RBAC management, audit logs, update status, enrollment, and proper loading/empty/error states.
The backend already exposes every REST + WS endpoint this needs — it's a frontend build, not backend work.

## E. Employee self-service web portal — ❌ not built (optional in the brief)

## Cross-cutting gaps

| Area | Status | Notes |
|---|---|---|
| **Domains / HTTPS / production deploy** (§7) | ⚠️ partial | Dev `docker-compose.yml` exists; `deployment/` now has `server.env` + nginx/DNS guide. **You** set up `api/admin/updates.quantumdataleap.ai`, TLS, and Cloudflare. |
| **Trusted code-signing + single installer** (§6) | ⚠️ partial | Update packages are SHA-256 + **RSA**-signed and verified before install, but not signed with a **trusted code-signing cert**, and no single `EmployeeMonitorSetup.exe` is produced here (Inno Setup/ISCC not installed; `build-installer.ps1` is ready for when it is). Velopack not adopted. |
| **CI/CD** (§9) | ❌ none | No `.github/workflows/`. Brief wants build+test+lint+type-check and a Windows build runner. |
| **Formal admin_commands queue** (§5) | ⚠️ partial | Remote control is consent-gated live input over WS; there is no separate command table with IDs/expiry/ack/replay protection. |
| **Beta update channel** (§6) | ⚠️ partial | Stable channel works; a distinct beta channel is not wired. |

## Recommended order to close the gaps

1. **Deploy what exists** — create the DB (done: see `deployment/README.md`), stand up `api.quantumdataleap.ai`
   behind nginx/TLS, point both EXEs at the public hostname. This alone satisfies most of the "definition of done".
2. **Admin Web UI** (`admin-web/`, React+TS) against the existing API — the largest remaining build.
3. **CI** (`.github/workflows/`) — backend build+test, admin/agent build on a Windows runner.
4. **Trusted code-signing** + single installer (`EmployeeMonitorSetup.exe` via Inno Setup, signed with a real cert).
5. Optional: formal `admin_commands` queue, beta channel, employee self-service portal.

## Test status (actually run)

Backend ~69 integration/unit + 8 web-access logic; agent 50; admin core 20. Backend integration tests need Docker
(Testcontainers). No CI runs these automatically yet.
