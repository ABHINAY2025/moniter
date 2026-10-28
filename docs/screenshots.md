# Screenshots (Milestone 5)

Optional, policy-governed periodic screenshots. The agent captures the screen on an interval and uploads the image;
the backend stores the bytes in object storage (encrypted at rest) with only metadata in the database; the admin
app lists captures and opens them in a viewer. Images are **never** served publicly — every retrieval is
authenticated, authorized against the employee, and audited.

## Storage choice

Screenshot bytes go to a `ScreenshotStorage`. The default implementation is an **encrypted local filesystem
store** (`FilesystemScreenshotStorage`): AES-256-GCM with a random per-object IV, key from
`SCREENSHOT_ENCRYPTION_KEY` (base64, 32 bytes). With no key set it stores plaintext and logs a warning (dev only).
An S3/MinIO implementation can be dropped in behind the same interface without touching the rest of the module —
MinIO's public container images are no longer pullable here (see M1 notes), so the filesystem backend is the
default and what the tests and live check exercise.

In Docker the bytes live on the `screenshots-data` volume at `/data/screenshots` (app-owned); set
`SCREENSHOT_ENCRYPTION_KEY` in `infrastructure/.env`.

## Flow

```
agent (every screenshotIntervalSeconds, policy + window gated, not while locked)
   └─ capture screen (GDI) → JPEG ─ POST /api/agent/screenshots {clientEventId, capturedAt, contentType, imageBase64}
                                        │ idempotent per (device, clientEventId); bytes ≤ app.screenshots.max-bytes
                                        ▼
                     ScreenshotStorage.put (AES-256-GCM)   +   metadata row (no bytes)
admin app
   ├─ GET /api/employees/{id}/screenshots?date=&tz=   → metadata page (no bytes)
   └─ GET /api/screenshots/{id}/content               → authorized + AUDITED, no-store; bytes streamed
```

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/agent/screenshots` | device token | `{ clientEventId, capturedAt, contentType(image/png\|image/jpeg), width?, height?, imageBase64 }` → `{ id, duplicate }` |
| GET | `/api/employees/{id}/screenshots?date=&tz=&page=&size=` | employee-read (scoped) | metadata only |
| GET | `/api/screenshots/{id}/content` | employee-read (scoped) | returns the image bytes; **audited** as `SCREENSHOT_VIEWED`; `Cache-Control: no-store` |

Scoped users (managers/employees) only see screenshots for employees they may read; others get 404, and device
tokens cannot read screenshots at all.

## Retention

A scheduled job (`app.screenshots.purge-cron`, default 03:30 daily) deletes screenshots — bytes and metadata —
older than the default policy's `screenshotRetentionDays`, and records a `SCREENSHOTS_PURGED` audit entry.

## Agent capture

`GdiScreenCaptureProvider` uses the supported `Graphics.CopyFromScreen` path and encodes JPEG (quality 60). The
`CaptureLoop` checks eligibility in short steps and captures when the interval has elapsed, so enabling screenshots
or opening the work-hours window takes effect promptly. It captures the screen only — never keystrokes, webcam or
microphone — and skips capture while the workstation is locked. Like the other collectors it needs the interactive
user session (the Session-0 helper is planned for Milestone 7).

## Admin app

The employee detail page shows a "Screenshots (today)" grid; clicking a capture opens it in a secure in-app viewer
(the bytes are fetched through the authenticated, audited endpoint and rendered from memory — never a public URL).

## Tests

- `FilesystemScreenshotStorageTest`: AES-GCM round-trip, on-disk ciphertext differs from plaintext, plaintext mode,
  bad key rejected.
- `ScreenshotIntegrationTest`: upload → list → view (bytes match, `SCREENSHOT_VIEWED` audited), idempotency,
  invalid content-type → 400, manager/device cannot list or view.
- Agent `AgentRunnerTests.CapturesAndUploadsScreenshotsWhenEnabled`; live `scripts/agent-smoke.ps1` captures a real
  screenshot, retrieves it via the secure endpoint and confirms the audit entry.
