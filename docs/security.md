# Security and privacy

## Privacy boundaries (by design, not configuration)

The platform monitors **company-owned devices transparently**. The following are out of scope and will not be implemented:

- keystroke content capture / keylogging (input is used only to decide idle vs. active)
- password, credential, cookie or token capture; browser password extraction
- hidden screen capture, hidden webcam or microphone access
- reading private messages, page contents or form contents (website monitoring is domain-level only)
- intercepting or decrypting traffic, bypassing Zscaler or antivirus, disabling Windows security
- stealth operation or anti-uninstall persistence (the agent shows a tray indicator and can be uninstalled by IT)

Each monitoring category can be switched off per policy, policies are restricted to configurable work hours and days, and employees can see their own record, devices and active policy via `GET /api/self`.

## Controls implemented in Milestone 1

| Area | Control |
|---|---|
| Passwords | BCrypt (cost 12), minimum 12 characters, no default credentials (bootstrap admin from env vars) |
| Login | Per-IP rate limit (Redis), account lockout after 5 failures for 15 min, identical errors for unknown/locked/wrong, constant-work comparison for unknown users |
| Sessions | 15-min HS256 JWT; opaque refresh tokens stored as SHA-256, rotated on every use; reuse of a rotated token revokes the whole family and is audited; password change/reset or disabling a user revokes all sessions |
| Authorization | Permission-based RBAC; URL rules before body parsing plus `@PreAuthorize` per method; row-level employee scoping; 404 for inaccessible records; managers see nothing until explicitly assigned |
| Devices | Enrollment needs an admin-issued, expiring, limited-use token; device tokens are random 256-bit, stored hashed, revocable; disabled/retired devices stop authenticating immediately; device and user credentials cannot be used for each other's endpoints |
| Transport | `REQUIRE_HTTPS` enforcement, HSTS, CSP `default-src 'self'; frame-ancestors 'none'`; proxy headers trusted only when explicitly enabled |
| Input | Bean Validation on every request DTO; policy semantic validation; problem responses without stack traces or SQL details |
| Secrets | Only via environment variables; `.env` is git-ignored; startup fails if `JWT_SECRET` is missing or short |
| Audit | Append-only `audit_logs` written in an independent transaction (failures are recorded even when the request is rejected); includes actor, action, target, outcome, IP, JSON metadata |
| Infrastructure | Datastores bound to 127.0.0.1, Redis password + dangerous commands disabled, API container runs as non-root |

## Planned in later milestones

- Screenshots: private bucket, server-side encryption, short-lived pre-signed URLs issued only after an authorization check, every view audited
- Live view: explicit permission plus `live_view_allowed` assignment, visible on-device indicator, maximum session length, start/stop audited
- Signed agent updates: code-signed MSI/EXE plus a detached signature verified by the agent before install
- Agent local config encrypted with DPAPI (machine scope)

## Reporting a vulnerability

Report suspected vulnerabilities privately to the platform owners. Do not open a public issue.
