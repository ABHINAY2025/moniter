# Deployment

This page describes production deployment of the backend. The installer, enrollment and auto-update steps are added in Milestone 7.

## Topology

```text
Agents / Admin app ──HTTPS 443──▶ nginx (TLS termination, HSTS) ──HTTP──▶ API (1..n instances)
                                                                         ├─▶ PostgreSQL (managed or HA)
                                                                         └─▶ Redis (password, private network)
```

## Required settings

| Variable | Production value |
|---|---|
| `REQUIRE_HTTPS` | `true`: plain-HTTP requests are rejected (health probes excepted) |
| `FORWARD_HEADERS_STRATEGY` | `framework`, **only** behind a proxy you control that overwrites `X-Forwarded-For/Proto` |
| `JWT_SECRET` | ≥ 32 random bytes from a secret manager; rotating it signs everyone out |
| `DB_*`, `REDIS_*` | from a secret manager; TLS to the database where supported |
| `BOOTSTRAP_ADMIN_*` | set for the first start only, then remove and change the password |
| `CORS_ALLOWED_ORIGINS` | empty unless a browser front-end is added |
| `ACCESS_TOKEN_TTL` / `REFRESH_TOKEN_TTL` | defaults `PT15M` / `P7D` |
| `PRESENCE_OFFLINE_TIMEOUT` | default `PT90S` (3 missed 30 s heartbeats) |

Swagger UI can be disabled in production with `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false`.

## Database

- Migrations run automatically on startup (Flyway). For controlled rollouts, run them from a single instance or a pipeline step first.
- Back up PostgreSQL daily, with point-in-time recovery. Redis holds only reconstructable state.
- High-volume event tables arrive from Milestone 4 and will be range-partitioned by month, with retention driven by policy `retentionDays` (see `database/README.md`).

## Scaling

The API is stateless; run several instances behind nginx. Rate limits and presence live in Redis, so they hold across instances, and the presence sweeper's events are de-duplicated with `SREM`.

## Health

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`, which includes database and Redis connectivity
