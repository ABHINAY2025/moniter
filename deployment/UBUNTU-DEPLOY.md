# Deploying the backend on an Ubuntu server

Target: one Ubuntu 22.04/24.04 server running the backend + PostgreSQL + Redis together (that's why `server.env`
uses `localhost`). PostgreSQL and Redis stay bound to localhost — never exposed to the internet. The backend is
published through a **Cloudflare Tunnel** — no nginx, no public IP, and **no inbound ports open at all** (cloudflared
makes outbound connections only; TLS is terminated at Cloudflare's edge). Secrets live in `server.env` (never in
git); this guide references them by name, it does not print their values.

Artifacts you copy to the server (from `monitering\` on your PC):
`employee-monitor-api-0.1.0.jar`, `server.env`, and `deployment/employee-monitor.service`.

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

sudo apt install -y postgresql redis-server
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

> Note: `server.env` sets `REQUIRE_HTTPS=true` + `FORWARD_HEADERS_STRATEGY=framework`, so the app trusts the
> `X-Forwarded-Proto: https` that Cloudflare adds (step 6) and treats tunneled requests as secure. A direct plain-HTTP
> curl *from outside* would be rejected — that's by design. (If anything is rejected unexpectedly while everything
> goes through the tunnel, set `REQUIRE_HTTPS=false` — the tunnel is the only ingress and is already HTTPS at the edge.)

## 6. Expose it with a Cloudflare Tunnel (no nginx, no inbound ports)

Install cloudflared on the server:
```bash
sudo mkdir -p /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt update && sudo apt install -y cloudflared
```

Create and route the tunnel (opens a browser login to your Cloudflare account):
```bash
cloudflared tunnel login
cloudflared tunnel create employee-monitor
cloudflared tunnel route dns employee-monitor api.quantumdataleap.ai   # creates the DNS record automatically
```

Write `/etc/cloudflared/config.yml` (use the tunnel UUID + credentials path that `create` printed):
```yaml
tunnel: <TUNNEL-UUID>
credentials-file: /root/.cloudflared/<TUNNEL-UUID>.json
ingress:
  - hostname: api.quantumdataleap.ai
    service: http://localhost:8090
  - service: http_status:404
```
Run it as a service:
```bash
sudo cloudflared service install
sudo systemctl enable --now cloudflared
```
WebSockets (`/ws/events`, `/ws/live-view`) pass through automatically — no extra config. Cloudflare terminates TLS at
the edge and sends `X-Forwarded-Proto: https`, which the backend trusts (step 5). In the Cloudflare dashboard set the
SSL/TLS mode to **Full**, and raise the upload size limit if screenshots exceed the plan default.

## 7. Firewall

With a tunnel there is **nothing to open inbound** — cloudflared dials out. Lock everything down except SSH:
```bash
sudo ufw allow OpenSSH
sudo ufw --force enable
```
5432 / 6379 / 8090 stay bound to localhost and are never reachable from outside.

## 8. Point the Windows apps at the server

Install the EXEs on their machines and enter the backend URL **`https://api.quantumdataleap.ai`**:
- `EmployeeMonitorSetup.exe` on each employee PC (enrollment token + that URL).
- `AdminSetup.exe` on admin PCs (that URL). First login: `admin` / your `BOOTSTRAP_ADMIN_PASSWORD` — change it.

## Upgrades & ops

- **New backend version:** `scp` the new jar over the old one, `sudo systemctl restart employee-monitor`. Flyway
  applies any new migrations on start.
- **Logs:** `journalctl -u employee-monitor -f`.
- **DB backup:** `sudo -u postgres pg_dump employee | gzip > employee-$(date +%F).sql.gz` (cron it; store off-box).
- **Restart policy:** the unit restarts on failure and starts on boot (`enable`d).
