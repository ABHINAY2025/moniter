# Website restriction & access monitoring

Policy-driven, **domain-level** website restriction and observation for company-owned devices. Admins define
allow/block/monitor rules, assign them to employees or devices, and watch blocked attempts live. Enforcement uses
the browsers' **own** enterprise policy — there is no traffic interception.

## Privacy boundaries (by design)

Confined to the boundaries in [security.md](security.md):

- **Domain-level only** — never full URLs, query strings, page contents, form data, cookies, tokens or passwords.
- **No TLS interception, no injected root certificate.** Blocking is done by Chrome/Edge themselves via their
  official `URLBlocklist`/`URLAllowlist` policy; the browser shows its own block page.
- **Never** bypasses, decrypts or disables Zscaler, antivirus, a VPN or Windows security.
- **Server-authoritative labelling.** The agent reports what it *observed*; the backend re-evaluates every reported
  domain and only records `ACCESS_BLOCKED` when a block rule actually applies **and** the device reports healthy
  enforcement — it never invents a page load from a DNS lookup.
- A set of **critical domains** (Windows Update, Microsoft, the Zscaler cloud, etc.) cannot be blocked unless the
  policy explicitly sets `overrideCriticalDomains`.

## Data flow

```
Admin ── POST /api/web-policies (+ /{id}/assignments) ─────────▶ web_policies / rules / assignments
                                                                       │ WebPolicyService.resolve(device)
                                                                       │  → block[] / allow[] / monitor[] + revision (sha256)
Agent ── GET /api/agent/web-policy ───────────────────────────▶ effective policy (by revision)
        on revision change → ChromiumPolicyEnforcement writes
            HKLM\SOFTWARE\Policies\{Google\Chrome,Microsoft\Edge}\URL{Block,Allow}list
        DNS-Client observations → WebAccessManager
            └─ POST /api/agent/web-access-events/batch   (policy-relevant domains only, 2-min dedup)
        enforcement health → POST /api/agent/web-enforcement-status
Server  ingest re-evaluates each domain (authoritative) → labels, dedups by clientEventId,
        publishes BLOCKED ones to em:events as {type:"WEB_EVENT"}
Admin ── GET /api/web-access-events (+ /summary), GET /api/devices/{id}/web-enforcement-status
        realtime WEB_EVENT on /ws/events (per-employee scoped)
```

## Decision precedence

`WebPolicyEvaluator` resolves a host deterministically:

1. **policy priority** (higher wins), then
2. **match specificity** (a longer, more specific domain wins), then
3. **action** — an `ALLOW` exception beats `BLOCK`, which beats `MONITOR`.

No matching rule ⇒ unrestricted. Matching is **subdomain-safe**: `example.com` matches `www.example.com` and
`shop.example.com` but **not** `example.com.attacker.test`. Rule domains are canonicalised (scheme/path/port/
trailing-dot stripped, lower-cased); wildcards, single-label and underscore hosts are rejected. A rule carries a
`domain` **or** a `category` (STREAMING / SOCIAL / GAMING / FILE_SHARING / ADULT, expanded to domain lists).

## Storage (migration V9)

| Table | Holds |
|---|---|
| `web_policies` | name, description, enabled, priority (1–1000), optional valid-from/to, revision |
| `web_policy_rules` | `action` (`ALLOW`\|`BLOCK`\|`MONITOR`) + `domain` **or** `category` (`chk_web_rule_target`) |
| `web_policy_assignments` | `target_type` (`DEVICE`\|`EMPLOYEE`) + `target_id`, unique per `(policy, type, target)` |
| `web_enforcement_status` | per device: mechanism, healthy, applied revision, browsers, last sync/event, last error |
| `web_access_events` | `event_type` ∈ {`NAVIGATION_OBSERVED`,`ACCESS_ATTEMPT`,`ACCESS_ALLOWED`,`ACCESS_BLOCKED`,`POLICY_MISMATCH`,`ENFORCEMENT_ERROR`}, `enforcement_result`, matched policy, `source`; unique `(device_id, client_event_id)` |

Permissions added: `WEB_POLICY_READ` + `WEB_EVENT_READ` (SUPER_ADMIN, IT_ADMIN, HR_ADMIN, MANAGER),
`WEB_POLICY_MANAGE` (SUPER_ADMIN, IT_ADMIN).

## Agent sync endpoints (device token)

| Endpoint | Shape |
|---|---|
| `GET /api/agent/web-policy` | `{ revision, block[], allow[], monitor[], generatedAt }` (always a body, even when unassigned: revision `"0"`, empty lists) |
| `POST /api/agent/web-access-events/batch` | `{ events:[{ clientEventId, domain, browser?, eventType, source, occurredAt }] }` → `{ accepted, duplicates, rejected }` |
| `POST /api/agent/web-enforcement-status` | `{ mechanism, healthy, appliedRevision, browsers, error }` → `204` |

## Admin endpoints

| Endpoint | Permission | Notes |
|---|---|---|
| `GET/POST /api/web-policies`, `GET/PUT/DELETE /api/web-policies/{id}` | READ / MANAGE | create/update body: `{ name, description, enabled, priority, overrideCriticalDomains, rules:[{domain?,category?,action}] }` |
| `GET/POST /api/web-policies/{id}/assignments`, `DELETE …/{assignmentId}` | READ / MANAGE | assign body `{ targetType, targetId }` |
| `GET /api/web-access-events?deviceId&employeeId&eventType&result&domain&from&to&page&size` | WEB_EVENT_READ | employee-scoped — non-all-seeing admins must pass an `employeeId` they may view |
| `GET /api/web-access-events/summary?days=` | WEB_EVENT_READ (+ full visibility) | `{ observed, allowed, blocked, monitored, enforcementErrors, topBlocked[] }` |
| `GET /api/devices/{id}/web-enforcement-status` | WEB_POLICY_READ | per-device enforcement health, incl. `stale` |

## Enforcement mechanism

`ChromiumPolicyEnforcement` (agent, Windows-only) writes the Chrome and Edge enterprise
`URLBlocklist`/`URLAllowlist` values under `HKLM\SOFTWARE\Policies`. Writing machine policy needs administrator
rights:

- Run as the **EmployeeMonitorAgent Windows Service** (LocalSystem) — it already has the rights.
- Run via the un-elevated **`run-agent.cmd`** user-session path — the one-time elevated setup grants that account
  write access (below).

If it cannot write the keys it reports `healthy:false` with a clear error (never a silent failure). Enforcement is
**continuous** (reapplied whenever the policy revision changes), while event observation respects the work-hours
window. Non-Chromium browsers (e.g. Firefox) are not covered by this mechanism. Browsers using DNS-over-HTTPS with
their own resolver bypass the Windows DNS observer, same caveat as in [monitoring.md](monitoring.md).

## Observation

The agent reuses the Windows **DNS-Client operational log** feed (see [monitoring.md](monitoring.md)). One
`GetResolvedHosts()` read per poll feeds both website-usage tracking and `WebAccessManager`, which emits a
`NAVIGATION_OBSERVED` / `DNS_LOG` event only for hosts a rule applies to (subdomain-safe), de-duplicated for 2
minutes. The server is authoritative on the final label.

## Components

- **Agent:** `WebAccessManager` (effective policy + observation queue), `IWebEnforcement` →
  `ChromiumPolicyEnforcement`, and `AgentRunner.WebAccessLoop` (fetch by revision → apply → report health → flush
  events; interval `Agent:WebAccessPollSeconds`, default 20s). `appliedRevision` only advances on a healthy apply,
  so a failed write retries.
- **Backend:** package `com.employeemonitor.api.webaccess` — `DomainMatcher`, `WebPolicyEvaluator`,
  `CriticalDomains`, `WebCategories`, `WebPolicyService`, `WebAccessEventService`, the four controllers, and
  `WebRealtimePublisher` (reuses the `em:events` channel, so existing per-employee WS filtering applies).
- **Admin app:** the **Web access** section — a **Policies** tab (create / edit / assign / delete) and an
  **Activity** tab (summary tiles, recent events, top blocked domains, updated live from `WEB_EVENT`). The domain
  editor preserves any category rules verbatim.

## Operator setup (per employee PC)

1. Copy the `EmployeeMonitorAgent` folder to the PC.
2. Right-click **`enable-website-monitoring.cmd`** → **Run as administrator**, once. It self-elevates and runs
   `setup-web-enforcement.ps1`, which enables the DNS-Client log, pre-creates the Chrome/Edge policy keys, and
   grants the agent account write access to them.
3. Start the agent (`run-agent.cmd`, or install the Windows Service). Blocking takes effect on the next browser
   navigation; the device's enforcement status appears under the policy's assignments.

## Tests

- Backend: `DomainMatcherTest` (canonicalisation + subdomain-safe matching), `WebPolicyEvaluatorTest` (precedence).
- Agent: `WebAccessManagerTests` (relevance, canonicalisation, dedup, drain/revision).
- Admin core: `WebAccessViewModelTests` (rule split, create, activity summary, live blocked event).
