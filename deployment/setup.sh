#!/usr/bin/env bash
#
# Employee Monitor backend — one-shot Ubuntu setup (installs + configures everything).
#
# Flow:
#   git clone https://github.com/ABHINAY2025/moniter.git
#   cd moniter
#   cp deployment/server.env.example deployment/server.env
#   nano deployment/server.env          # fill in the real secrets (DB/Redis/JWT/admin/screenshot)
#   sudo bash deployment/setup.sh
#
# What it does (idempotent — safe to re-run):
#   installs Java 21 + PostgreSQL + Redis, sets the DB & Redis passwords from server.env, creates the DB,
#   builds the jar from source if no prebuilt one is present, installs it as a systemd service, and installs
#   cloudflared. The Cloudflare tunnel login is interactive, so it is the one manual step at the end
#   (or set CLOUDFLARE_TUNNEL_TOKEN to install the connector non-interactively).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$SCRIPT_DIR/server.env"
SERVICE_SRC="$SCRIPT_DIR/employee-monitor.service"
APP_DIR="/opt/employee-monitor"
DATA_DIR="/var/lib/employee-monitor"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root:  sudo bash deployment/setup.sh"

# ---- server.env must exist and be filled in ----
if [ ! -f "$ENV_FILE" ]; then
  cp "$SCRIPT_DIR/server.env.example" "$ENV_FILE"
  die "Created $ENV_FILE from the template. Edit it with your real secrets, then re-run this script."
fi
grep -q "__SET_ON_SERVER__" "$ENV_FILE" && die "$ENV_FILE still has __SET_ON_SERVER__ placeholders. Fill them in and re-run."

# ---- read values literally (passwords can contain special characters) ----
val() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
DB_PASSWORD="$(val DB_PASSWORD)"
REDIS_PASSWORD="$(val REDIS_PASSWORD)"
DB_URL="$(val DB_URL)"
DB_USERNAME="$(val DB_USERNAME)"
DB_NAME="$(printf '%s' "$DB_URL" | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')"
[ -n "$DB_PASSWORD" ] && [ -n "$DB_NAME" ] || die "Could not read DB_PASSWORD / DB_URL from $ENV_FILE"
log "Target database: '$DB_NAME' (user '$DB_USERNAME')"

export DEBIAN_FRONTEND=noninteractive

# ---- 1. base packages ----
log "Installing PostgreSQL, Redis, and base tools"
apt-get update -y
apt-get install -y wget curl gnupg lsb-release ca-certificates postgresql redis-server

# ---- Java 21 (Temurin, fallback to distro openjdk) ----
if ! java -version 2>&1 | grep -q 'version "21'; then
  log "Installing Java 21 (Temurin)"
  install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list
  apt-get update -y
  apt-get install -y temurin-21-jre || apt-get install -y openjdk-21-jre-headless
fi

# ---- 2. build or locate the jar ----
JAR="$(ls -1 "$REPO_ROOT"/backend/employee-monitor-api/target/employee-monitor-api-*.jar "$SCRIPT_DIR"/employee-monitor-api-*.jar 2>/dev/null | grep -v '\.original' | head -1 || true)"
if [ -z "$JAR" ]; then
  log "No prebuilt jar found — building from source (installing Maven + JDK)"
  apt-get install -y maven temurin-21-jdk || apt-get install -y maven openjdk-21-jdk-headless
  ( cd "$REPO_ROOT/backend/employee-monitor-api" && mvn -q -DskipTests clean package )
  JAR="$(ls -1 "$REPO_ROOT"/backend/employee-monitor-api/target/employee-monitor-api-*.jar | grep -v '\.original' | head -1)"
fi
[ -n "$JAR" ] || die "No jar produced"
log "Using jar: $JAR"

# ---- 3. PostgreSQL: set password + create DB (idempotent) ----
log "Configuring PostgreSQL"
systemctl enable --now postgresql
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
ALTER USER postgres WITH PASSWORD '$(printf '%s' "$DB_PASSWORD" | sed "s/'/''/g")';
SQL
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB_NAME\" WITH ENCODING 'UTF8' TEMPLATE template0;"
  log "Created database '$DB_NAME'"
else
  log "Database '$DB_NAME' already exists"
fi

# ---- 4. Redis: password + localhost ----
log "Configuring Redis"
sed -i '/^requirepass /d' /etc/redis/redis.conf
echo "requirepass $REDIS_PASSWORD" >> /etc/redis/redis.conf
systemctl enable redis-server
systemctl restart redis-server

# ---- 5. app user, dirs, artifacts ----
log "Installing application files"
id emmonitor >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin emmonitor
install -d -o emmonitor -g emmonitor "$APP_DIR" "$DATA_DIR/screenshots" "$DATA_DIR/updates"
install -o emmonitor -g emmonitor -m 0644 "$JAR" "$APP_DIR/app.jar"
install -o emmonitor -g emmonitor -m 0600 "$ENV_FILE" "$APP_DIR/server.env"

# ---- 6. systemd service ----
log "Installing + starting the service"
cp "$SERVICE_SRC" /etc/systemd/system/employee-monitor.service
systemctl daemon-reload
systemctl enable --now employee-monitor
sleep 4
if systemctl is-active --quiet employee-monitor; then
  log "Backend is active."
else
  die "Backend failed to start. Check:  journalctl -u employee-monitor -n 60 --no-pager"
fi

# ---- 7. cloudflared ----
if ! command -v cloudflared >/dev/null 2>&1; then
  log "Installing cloudflared"
  mkdir -p /usr/share/keyrings
  curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
  echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" > /etc/apt/sources.list.d/cloudflared.list
  apt-get update -y && apt-get install -y cloudflared
fi
if [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
  log "Installing the Cloudflare tunnel connector from token"
  cloudflared service install "$CLOUDFLARE_TUNNEL_TOKEN"
  echo "In the Zero Trust dashboard, add a public hostname: api.quantumdataleap.ai -> http://localhost:8090"
fi

cat <<DONE

================================================================
 Backend is deployed and running.
   Health:  curl -s http://localhost:8090/actuator/health   # {"status":"UP"}
   Logs:    journalctl -u employee-monitor -f
   Login:   admin / <BOOTSTRAP_ADMIN_PASSWORD from server.env>  (change it)

 Last step — expose it with the Cloudflare tunnel (if not using a token):
   cloudflared tunnel login
   cloudflared tunnel create employee-monitor
   cloudflared tunnel route dns employee-monitor api.quantumdataleap.ai
   # write /etc/cloudflared/config.yml -> service http://localhost:8090, then:
   sudo cloudflared service install

 Then point the Windows EXEs at:  https://api.quantumdataleap.ai
================================================================
DONE
