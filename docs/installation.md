# Installation (local development on Windows)

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker Desktop | 4.x, Linux containers | running the stack, integration tests |
| JDK | 21 (e.g. Eclipse Temurin) | building/testing the backend |
| Maven | 3.9+ | building/testing the backend |
| .NET SDK | 8 or newer | agent and admin app (Milestones 2–3) |

## Start the backend stack

```powershell
cd employee-monitoring-platform\infrastructure
copy .env.example .env
notepad .env
docker compose up -d --build
docker compose ps
```

Fill in `.env`:

| Variable | Notes |
|---|---|
| `DB_PASSWORD`, `REDIS_PASSWORD` | any long random strings |
| `JWT_SECRET` | ≥ 32 bytes, random. PowerShell: `[Convert]::ToBase64String((1..48 \| % { Get-Random -Max 256 }))` |
| `BOOTSTRAP_ADMIN_*` | creates the first SUPER_ADMIN when the database has no users; password ≥ 12 chars |
| `POSTGRES_HOST_PORT`, `REDIS_HOST_PORT` | change if PostgreSQL/Redis are already installed natively |

Expected `docker compose ps`:

```text
SERVICE    STATUS
api        Up …
postgres   Up … (healthy)
redis      Up … (healthy)
```

Then:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health   # status = UP
powershell -ExecutionPolicy Bypass -File ..\scripts\smoke-test.ps1
```

Object storage for screenshots is opt-in (`docker compose --profile storage up -d`) and is not needed before Milestone 5. MinIO no longer publishes public container images, so set `MINIO_IMAGE` to an image your organisation mirrors.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Ports are not available … 6379` or `5432` | A native Redis/PostgreSQL is running. Set `REDIS_HOST_PORT` / `POSTGRES_HOST_PORT` in `.env` |
| API exits with `JWT_SECRET must be set and at least 32 bytes long` | Set a longer `JWT_SECRET` |
| Log says `No users exist. Set BOOTSTRAP_ADMIN_USERNAME…` | Set the `BOOTSTRAP_ADMIN_*` variables and `docker compose up -d api` |
| `mvn test` fails with `Could not find a valid Docker environment` | Start Docker Desktop and wait until the engine is running |
| Forgot the admin password | Another SUPER_ADMIN can reset it via `PATCH /api/users/{id}` with `newPassword`. For a fresh dev database, run `docker compose down -v` to wipe the volumes |

## Reset everything (dev only)

```powershell
docker compose down -v   # deletes the database, Redis and storage volumes
```
