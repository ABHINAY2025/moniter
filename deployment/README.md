# Server deployment

The backend is the only thing that runs on the server; the admin and employee apps are Windows EXEs that connect
to it over HTTPS/WSS. PostgreSQL stays private (never exposed to the internet).

## 1. Create the database (run this yourself — the deploy host owns Postgres)

Connect as the `postgres` superuser and create an **empty** database. Flyway creates every table on first boot
(`spring.jpa.hibernate.ddl-auto=validate` + the `db/migration` scripts V1–V9), so no schema SQL is needed.

```sql
-- psql -U postgres -h localhost
CREATE DATABASE employee WITH ENCODING 'UTF8' TEMPLATE template0;
```

That's it — the `postgres` user (whose password lives only in the git-ignored `deployment/server.env`, never in
the repo) already owns it. The backend connects with
`jdbc:postgresql://localhost:5432/employee?options=-c%20TimeZone=Asia/Kolkata` (see `deployment/server.env`). Timestamps are stored in UTC; the `Asia/Kolkata` option only sets the session TZ.

> Least-privilege option (recommended later): create a dedicated role that owns just this DB instead of using the
> `postgres` superuser — `CREATE ROLE em LOGIN PASSWORD '…'; CREATE DATABASE employee OWNER em;` — and point
> `DB_USERNAME`/`DB_PASSWORD` at it.

## 2. Supply the environment

`deployment/server.env` (git-ignored) holds the real values — the DB credentials you gave plus freshly generated
JWT / Redis / screenshot secrets and the first-run admin password. Copy it to the server and load it before
starting the backend. `server.env.example` is the committed template.

Redis must be running and reachable at `REDIS_HOST:REDIS_PORT` with `REDIS_PASSWORD`.

## 3. Run the backend

**Native (jar):**
```bash
cd backend/employee-monitor-api
mvn -q clean package -DskipTests        # or run tests
set -a; . /path/to/server.env; set +a
java -jar target/employee-monitor-api-*.jar
```

**As a service (keep it always up — it's the hub both EXEs depend on):** install the prebuilt
`employee-monitor-api-0.1.0.jar` with the systemd unit in `deployment/employee-monitor.service` (its header has the
copy-paste install steps + `journalctl` to watch the first-boot migration and bootstrap admin).

**Docker:** build the image from `backend/employee-monitor-api/Dockerfile` and pass `server.env` via `--env-file`.
Note: if the backend runs in a container and Postgres/Redis run on the host, `localhost` inside the container is the
container, not the host — use `host.docker.internal` (or the host's private IP / a shared Docker network) in
`DB_URL`/`REDIS_HOST`.

On first boot it runs the migrations and creates the bootstrap SUPER_ADMIN:
**username `admin`, password = `BOOTSTRAP_ADMIN_PASSWORD`** from `server.env` (change it after first login).
This is a *fresh* database, so the local dev `admin/admin` shortcut does **not** apply here.

## 4. HTTPS, reverse proxy, and domains (you set these up in Cloudflare/nginx)

The backend serves plain HTTP on `SERVER_PORT` (8090). Put nginx in front to terminate TLS and set
`X-Forwarded-*` (the backend already trusts them because `FORWARD_HEADERS_STRATEGY=framework`).

| Hostname | Points at | Purpose |
|---|---|---|
| `api.quantumdataleap.ai` | nginx → backend `:8090` (REST + `/ws/events`, `/ws/live-view`) | what both EXEs and the web UI call |
| `admin.quantumdataleap.ai` | the Admin Web UI (React) — **not built yet, see the gap analysis** | browser dashboard |
| `updates.quantumdataleap.ai` | the signed update endpoints (`/api/agent/update`, package serving) | auto-update |

Minimal nginx server block for the API (WebSocket upgrade included):

```nginx
server {
    listen 443 ssl http2;
    server_name api.quantumdataleap.ai;
    # ssl_certificate / ssl_certificate_key via certbot or Cloudflare origin cert

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # WebSocket upgrade for /ws/*
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
    }
}
```

After the domains resolve to the server and TLS works, point the EXEs at the API:

- **Admin app:** `admin-windows/.../publish-selfcontained/appsettings.json` → `"BaseUrl": "https://api.quantumdataleap.ai"`
- **Employee agent:** `run-agent.cmd` → `set Agent__BackendBaseUrl=https://api.quantumdataleap.ai`
  (once this is a stable public hostname, the LAN-IP churn problem goes away entirely).

For a home-hosted dev server, a **Cloudflare Tunnel** (`cloudflared`) can expose `api.quantumdataleap.ai` without a
public IP or inbound port-forwarding, keeping Postgres private.
