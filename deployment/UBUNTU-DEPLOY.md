# Deploying the backend on an Ubuntu server

Target: one Ubuntu 22.04/24.04 server running the backend + PostgreSQL + Redis together (that's why `server.env`
uses `localhost`). PostgreSQL and Redis stay bound to localhost — never exposed to the internet. Only 80/443 are
public, fronted by nginx (TLS) and optionally Cloudflare. Secrets live in `server.env` (never in git); this guide
references them by name, it does not print their values.

Artifacts you copy to the server (from `monitering\` on your PC):
`employee-monitor-api-0.1.0.jar`, `server.env`, and `deployment/employee-monitor.service` + the nginx block below.

---

## 1. Install packages

```bash
sudo apt update
# Java 21 (Temurin is the reliable source across Ubuntu versions):
sudo apt install -y wget apt-transport-https gnupg
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-21-jre
#   (Ubuntu 24.04 alternative: sudo apt install -y openjdk-21-jre-headless)

sudo apt install -y postgresql redis-server nginx
java -version   # should print 21
```

## 2. PostgreSQL — create the DB (bound to localhost)

```bash
sudo -u postgres psql
```
```sql
-- set the postgres password to your DB_PASSWORD from server.env:
ALTER USER postgres WITH PASSWORD 'YOUR_DB_PASSWORD';
-- empty DB; Flyway builds all tables on first boot:
CREATE DATABASE employee WITH ENCODING 'UTF8' TEMPLATE template0;
\q
```
PostgreSQL listens on `127.0.0.1:5432` by default on Ubuntu — leave it that way (do not touch `listen_addresses`).
Confirm it is not public: `sudo ss -tlnp | grep 5432` should show only `127.0.0.1`.

## 3. Redis — set a password (bound to localhost)

Edit `/etc/redis/redis.conf`: ensure `bind 127.0.0.1 ::1`, set `requirepass YOUR_REDIS_PASSWORD` (the
`REDIS_PASSWORD` from server.env), then:
```bash
sudo systemctl restart redis-server
```

## 4. App user, directories, and artifacts

```bash
sudo useradd --system --home /opt/employee-monitor --shell /usr/sbin/nologin emmonitor
sudo mkdir -p /opt/employee-monitor /var/lib/employee-monitor/{screenshots,updates}

# from your PC: scp employee-monitor-api-0.1.0.jar server.env you@server:/tmp/
sudo mv /tmp/employee-monitor-api-0.1.0.jar /opt/employee-monitor/
sudo mv /tmp/server.env /opt/employee-monitor/
sudo chown -R emmonitor:emmonitor /opt/employee-monitor /var/lib/employee-monitor
sudo chmod 600 /opt/employee-monitor/server.env
```

## 5. Run it as a service

```bash
sudo cp deployment/employee-monitor.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now employee-monitor
journalctl -u employee-monitor -f
```
On first boot watch for: Flyway running migrations `V1..V9`, then `Bootstrap SUPER_ADMIN 'admin' created`.
Quick local check: `curl -s http://localhost:8090/actuator/health` → `{"status":"UP"}`.

> Note: `server.env` sets `REQUIRE_HTTPS=true` + `FORWARD_HEADERS_STRATEGY=framework`, so the app expects to sit
> behind the TLS proxy in step 6. If you curl it over plain HTTP from outside, it will reject — that's by design.

## 6. nginx reverse proxy + TLS for `api.quantumdataleap.ai`

Add the WebSocket upgrade map once, in the `http {}` context — e.g. `/etc/nginx/conf.d/upgrade.conf`:
```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
```
Create `/etc/nginx/sites-available/api.quantumdataleap.ai`:
```nginx
server {
    listen 80;
    server_name api.quantumdataleap.ai;
    location / { return 301 https://$host$request_uri; }
}
server {
    listen 443 ssl http2;
    server_name api.quantumdataleap.ai;

    ssl_certificate     /etc/letsencrypt/live/api.quantumdataleap.ai/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.quantumdataleap.ai/privkey.pem;

    client_max_body_size 16m;   # screenshot uploads

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;      # WebSocket (/ws/events, /ws/live-view)
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
    }
}
```
```bash
sudo ln -s /etc/nginx/sites-available/api.quantumdataleap.ai /etc/nginx/sites-enabled/
# TLS cert (public server with DNS already pointing here):
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.quantumdataleap.ai
sudo nginx -t && sudo systemctl reload nginx
```

## 7. DNS (Cloudflare)

Add an **A record**: `api` → your server's public IP. Two ways to serve TLS:
- **Public VPS:** point DNS at the IP; certbot (above) issues a Let's Encrypt cert. (If Cloudflare proxy/orange-cloud
  is on, use a Cloudflare **Origin Certificate** on nginx instead of certbot, and set SSL mode to Full (strict).)
- **Home server / no public IP:** use a **Cloudflare Tunnel** — no open ports, no port-forwarding:
  ```bash
  # install cloudflared, then:
  cloudflared tunnel login
  cloudflared tunnel create employee-monitor
  # route api.quantumdataleap.ai -> http://localhost:8090 in the tunnel config, then run it as a service:
  sudo cloudflared service install
  ```

## 8. Firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'     # 80 + 443
sudo ufw enable
```
Do **not** open 5432 / 6379 / 8090 — they stay internal.

## 9. Point the Windows apps at the server

Install the EXEs on their machines and enter the backend URL **`https://api.quantumdataleap.ai`**:
- `EmployeeMonitorSetup.exe` on each employee PC (enrollment token + that URL).
- `AdminSetup.exe` on admin PCs (that URL). First login: `admin` / your `BOOTSTRAP_ADMIN_PASSWORD` — change it.

## Upgrades & ops

- **New backend version:** `scp` the new jar over the old one, `sudo systemctl restart employee-monitor`. Flyway
  applies any new migrations on start.
- **Logs:** `journalctl -u employee-monitor -f`.
- **DB backup:** `sudo -u postgres pg_dump employee | gzip > employee-$(date +%F).sql.gz` (cron it; store off-box).
- **Restart policy:** the unit restarts on failure and starts on boot (`enable`d).
