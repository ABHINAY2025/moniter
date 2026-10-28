# Monitoring categories (Milestone 4)

Five monitoring categories, each independently switchable by policy and confined to the privacy boundaries from
[security.md](security.md): application usage, website **domains** (not URLs), USB **events** (metadata only),
network **status** (no traffic inspection), and Zscaler **status** (detected locally, never bypassed).

## Data flow

```
agent collectors ──(policy-gated, idempotent batches)──▶ POST /api/agent/{category}
                                                              │
                                                     PostgreSQL time-series
                                                              │
admin app ──GET /api/employees/{id}/{category}?date=&tz=──────┘   (row-scoped per employee)
heartbeat also carries live currentApplication / network / zscalerStatus ──▶ device presence
```

## Storage (migration V4)

`application_usage`, `website_usage`, `usb_events`, `network_events`, `zscaler_events`. Every row has a
`(device_id, client_event_id)` unique constraint, so retried agent batches are de-duplicated. Indexed on
`(employee_id, time)` and `(device_id, time)`.

## Agent ingest endpoints (device token)

| Endpoint | Body (`{ "events": [ … ] }`) |
|---|---|
| `POST /api/agent/application-usage` | `{ clientEventId, applicationName, processName?, startedAt, endedAt }` |
| `POST /api/agent/website-usage` | `{ clientEventId, domain, browser?, startedAt, endedAt }` |
| `POST /api/agent/usb-events` | `{ clientEventId, eventType(CONNECTED\|REMOVED), deviceClass?, vendorId?, productId?, serialNumber?, description?, occurredAt }` |
| `POST /api/agent/network-events` | `{ clientEventId, eventType(CONNECTED\|DISCONNECTED\|CHANGED), connectionType?(WIFI\|ETHERNET\|VPN\|OTHER), networkName?, ipAddress?, occurredAt }` |
| `POST /api/agent/zscaler-events` | `{ clientEventId, status(CONNECTED\|DISCONNECTED\|ERROR\|UNKNOWN), detail?, occurredAt }` |

All return `{ accepted, duplicates, rejected }`. Usage durations are computed server-side from `startedAt/endedAt`
(reversed intervals are rejected); events more than 5 min in the future or older than 7 days are rejected.

## Admin query endpoints (employee-scoped)

| Endpoint | Returns |
|---|---|
| `GET /api/employees/{id}/applications?date=&tz=` | usage aggregated per application: `{ totalSeconds, items:[{label,totalSeconds,sessions}] }` |
| `GET /api/employees/{id}/websites?date=&tz=` | usage aggregated per domain |
| `GET /api/employees/{id}/usb-events?date=&tz=` | paged USB events |
| `GET /api/employees/{id}/network-events?date=&tz=` | paged network events |
| `GET /api/employees/{id}/zscaler` | `{ currentStatus, since, history[] }` |

The heartbeat response path also surfaces live `currentApplication`, `network` and `zscalerStatus` on the device's
presence (shown on the dashboard and employee detail).

## Agent collectors

Pure change-trackers (`AppUsageTracker`, `NetworkTracker`, `StatusChangeTracker`, `UsbDriveTracker`) decide what is
worth recording; thin Windows providers supply the raw samples. `MonitoringCollector` wires them together, gated by
each policy flag and the work-hours window, and exposes the live snapshot for the heartbeat.

| Category | Windows provider | Notes |
|---|---|---|
| Application | `Win32ForegroundAppProvider` | `GetForegroundWindow` + owning process; process/app name only, never the window title. Idle/locked time is not counted. |
| Network | `WindowsNetworkProvider` | `NetworkInterface`: connection type, adapter name, IPv4. Status only. |
| Zscaler | `WindowsZscalerProvider` | Detects the Zscaler client via its processes / tunnel adapter → CONNECTED, else UNKNOWN. Never bypasses or decrypts. Precise tunnel state needs the authorized Zscaler API integration. |
| USB | `WindowsRemovableDriveProvider` | Removable-drive connect/remove (letter + volume label). Files are never read; an optional admin alert on removable storage is planned. |
| Website | `WindowsDnsVisitProvider` | Subscribes to the Windows **DNS-Client operational log** (`Microsoft-Windows-DNS-Client/Operational`, event 3008 query names) and records the **registrable domain** — never URLs or page history. The channel is off by default and enabled once, elevated (`enable-website-monitoring.cmd`). DNS-over-HTTPS with a non-Windows resolver is not seen. This same feed drives [web-access.md](web-access.md) restriction. |

Collection in a Windows Service shares the Session-0 limitation described in [agent.md](agent.md): foreground-app
detection needs the user session. Run the agent in the user session (as the smoke test does) or use the
per-session helper planned for Milestone 7.

## Admin app

The Employees list opens an **employee detail** page showing the devices (with live app/network/Zscaler), today's
application and website usage, USB and network events, and Zscaler status/history — one screen per the spec's
"Employee Details". The dashboard device table also shows each device's current application.

## Tests

- Backend `MonitoringIntegrationTest`: aggregation, idempotency, reversed-interval rejection, USB/network/Zscaler
  storage and queries, heartbeat→presence live fields, invalid enum → 400, manager scoping.
- Agent `TrackerTests` / `MonitoringCollectorTests`: the pure trackers and the policy-gated collector + upload.
- Live `scripts/agent-smoke.ps1`: confirms network and Zscaler events reach the backend and presence carries them.
