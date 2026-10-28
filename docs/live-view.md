# Live view (Milestone 6)

Authorized, time-limited, fully audited live screen viewing. The backend is the signaling and authorization
authority; media flows per session only between the authorized admin and the target device. There is no hidden
remote access — the employee's device shows a "Live screen: Active" indicator for the whole session.

## Authorization (who may watch whom)

Starting a session requires the `LIVE_VIEW` permission (granted to SUPER_ADMIN and MANAGER) **and**:

- the caller can read the target employee (row-level scoping), and
- for a scoped role (manager), the manager must have `liveViewAllowed = true` on their assignment for that employee
  — a manager does **not** get live view just by being assigned someone, and
- the device's effective policy has `liveView = true`, and
- the device is `ACTIVE` and currently online.

At most one live session per device (enforced by a partial unique index).

## Session lifecycle & transport

```
admin ─ POST /api/live-view/sessions {deviceId} ─▶ authorize → create PENDING session (audit LIVE_VIEW_STARTED)
admin ─ WS /ws/live-view?sessionId=…  (JWT) ──┐
agent ─ WS /ws/live-view (device token) ──────┤ backend relays only between this admin and this device
                                              ▼
   backend → agent: START {sessionId}         agent captures → FRAME {sessionId, data} → backend → admin
   (WebRTC OFFER/ANSWER/ICE flow over the same channel when that transport is used)
stop: admin DELETE / WS close / agent disconnect / timeout
   → backend ends session (audit LIVE_VIEW_STOPPED with duration + reason), sends STOP to both ends
```

- **Signaling** (`/ws/live-view`): the handshake authenticates an **agent** (device token, no session id → registered
  as its device's endpoint) or an **admin** (JWT + `?sessionId=`, must own the session or hold EMPLOYEE_READ_ALL).
  The backend relays messages for a session only between its authorized admin and the target device.
- **Transport**: the default is a **frame-relay** transport — the agent streams JPEG frames (gated by the live-view
  policy flag; never while locked) which the backend relays to the admin. WebRTC is the production path: its
  OFFER/ANSWER/ICE messages flow over the very same signaling channel, so switching transport does not change the
  authorization, session or audit model. (WebRTC media is peer-to-peer and cannot be exercised headlessly here.)
- **Time limit**: a sweep ends any session older than the device policy's `liveViewMaxMinutes` (reason `TIMEOUT`).
- **Teardown reasons** recorded: `STOPPED_BY_ADMIN`, `ADMIN_DISCONNECTED`, `DEVICE_DISCONNECTED`, `TIMEOUT`,
  `DEVICE_NOT_CONNECTED`.

The in-memory connection registry is single-instance; multi-instance deployments need sticky routing or a Redis
relay (noted for later). Session state and audit are in the database and already shared.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/live-view/sessions` | `LIVE_VIEW` | `{ deviceId }` → `{ sessionId, deviceCode, employee, maxDurationSeconds, signalingPath }`; audited |
| DELETE | `/api/live-view/sessions/{sessionId}` | `LIVE_VIEW` (owner or EMPLOYEE_READ_ALL) | ends the session; audited |
| GET | `/api/live-view/sessions` | `LIVE_VIEW` | session history (admin, device, employee, duration, end reason) |
| WS | `/ws/live-view` | device token or JWT+sessionId | signaling relay |
| GET | `/api/agent/live-view/status` | device token | `{ active, sessionId }` for the on-device indicator |

## On-device indicator

The tray polls `/api/agent/live-view/status`; while a session is active its panel shows
**"Live screen: ● ACTIVE — an authorized administrator is viewing this screen now"**. Capture only runs while a
backend-authorized session exists, so the indicator and the actual viewing cannot diverge.

## Admin app

A device's "Live view" button (on the employee detail page) opens the Live View page: a red **LIVE** badge, the
streamed screen, status/frame count, and a **Stop live view** button. The page starts the session, connects the
signaling channel, renders frames, and stops the session on close.

## Tests

- Backend `LiveViewIntegrationTest` (real server + WS clients): signaling relays both ways, the agent is told to
  START, the admin receives a FRAME, the session becomes ACTIVE, the indicator reports active, admin stop tells the
  agent to STOP, and start+stop are audited; plus policy-disabled → 403, offline device → 409, unauthenticated
  handshake rejected.
- Agent `LiveViewClientTests`: START/STOP control handling and frame gating (policy off / locked / no capture).
- Admin `LiveViewViewModelTests`: start/connect/render frames, server stop, stop command, policy-forbidden error.
- Live `scripts/agent-smoke.ps1` runs the real agent and drives a session end-to-end: real JPEG frames stream
  admin↔agent through the backend and start/stop are audited.
