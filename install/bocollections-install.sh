#!/usr/bin/env bash

# Copyright (c) 2021-2026 community-scripts ORG (framework) / N0t4R0b0t (this app script)
# Author: N0t4R0b0t
# License: MIT | https://github.com/community-scripts/ProxmoxVED/raw/main/LICENSE
# Source: https://github.com/N0t4R0b0t/BOCollections

source /dev/stdin <<<"$FUNCTIONS_FILE_PATH"
color
verb_ip6
catch_errors
setting_up_container
network_check
update_os

msg_info "Installing Dependencies"
$STD apt install -y \
  nginx \
  redis-server \
  rabbitmq-server
msg_ok "Installed Dependencies"

JAVA_VERSION="21" setup_java

PG_VERSION="15" setup_postgresql
# APPLICATION is already exported into the container by the framework's build_container
# (as $APP from ct/bocollections.sh) — don't re-derive it from a bare $APP here, that variable
# was never exported and trips `set -u` with "APP: unbound variable".
PG_DB_NAME="collections" PG_DB_USER="collections" PG_DB_SCHEMA_PERMS="true" setup_postgresql_db
# setup_postgresql_db defaults to native Postgres' own port (5432) — the app's own default of
# 5433 only exists to match the docker-compose port *mapping* used in local dev, not relevant here.
DB_PORT="5432"

msg_info "Creating bocollections system user"
useradd --system --create-home --home-dir /opt/bocollections --shell /usr/sbin/nologin bocollections
msg_ok "Created bocollections system user"

msg_info "Configuring RabbitMQ"
RABBITMQ_PASSWORD=$(openssl rand -base64 18 | tr -dc 'a-zA-Z0-9' | head -c13)
$STD systemctl enable --now rabbitmq-server
# rabbitmq-server can take a few seconds to bring up its management Erlang node on first boot —
# rabbitmqctl fails outright if it isn't listening yet.
for _ in $(seq 1 15); do
  rabbitmqctl status >/dev/null 2>&1 && break
  sleep 2
done
$STD rabbitmqctl add_user collections "$RABBITMQ_PASSWORD"
$STD rabbitmqctl set_permissions -p / collections ".*" ".*" ".*"
$STD rabbitmqctl set_user_tags collections management
msg_ok "Configured RabbitMQ"

msg_info "Configuring Redis"
$STD systemctl enable --now redis-server
msg_ok "Configured Redis"

# --- Fetch and deploy the release (backend jar + frontend bundle) from R2 ---
# Kept inline rather than as a shared function: this install script runs as its own bash process
# (lxc-attach), separate from ct/bocollections.sh's process where the equivalent update-path
# helpers are defined — see that file's comment on bocollections_deploy_release for the sibling
# implementation used by `update_script()`.
R2_RELEASES_URL="https://pub-73de8ecb4a9644fc8072f4e6bb9c700a.r2.dev/bocollections/releases"

msg_info "Fetching latest BOCollections release"
BOCOLLECTIONS_VERSION="$(curl -fsSL "${R2_RELEASES_URL}/latest.txt" | tr -d '[:space:]')"
if [[ -z "$BOCOLLECTIONS_VERSION" ]]; then
  msg_error "Could not determine the latest BOCollections version from R2 — aborting"
  exit 1
fi
msg_ok "Latest version: ${BOCOLLECTIONS_VERSION}"

msg_info "Downloading BOCollections ${BOCOLLECTIONS_VERSION}"
mkdir -p /opt/bocollections
curl -fsSL "${R2_RELEASES_URL}/${BOCOLLECTIONS_VERSION}/bocollections-backend.jar" -o /opt/bocollections/backend.jar
curl -fsSL "${R2_RELEASES_URL}/${BOCOLLECTIONS_VERSION}/bocollections-frontend.tar.gz" -o /tmp/bocollections-frontend.tar.gz
mkdir -p /opt/bocollections/frontend
tar -xzf /tmp/bocollections-frontend.tar.gz -C /opt/bocollections/frontend
rm -f /tmp/bocollections-frontend.tar.gz
echo "$BOCOLLECTIONS_VERSION" >"$HOME/.bocollections"
msg_ok "Downloaded BOCollections ${BOCOLLECTIONS_VERSION}"

msg_info "Configuring BOCollections"
mkdir -p /etc/bocollections /opt/bocollections/data/scan-photos /opt/bocollections/data/backups
JWT_SECRET="$(openssl rand -hex 32)"
cat <<EOF >/etc/bocollections/bocollections.env
DB_HOST=127.0.0.1
DB_PORT=${DB_PORT}
DB_NAME=${PG_DB_NAME}
DB_USERNAME=${PG_DB_USER}
DB_PASSWORD=${PG_DB_PASS}
RABBITMQ_HOST=127.0.0.1
RABBITMQ_USERNAME=collections
RABBITMQ_PASSWORD=${RABBITMQ_PASSWORD}
REDIS_HOST=127.0.0.1
JWT_SECRET=${JWT_SECRET}
# Almost certainly wrong for your setup — this must exactly match the origin(s) (protocol +
# host + port) your browser actually loads the app from, e.g. http://${IP} or a reverse-proxied
# domain like https://boc.example.com. A mismatch fails silently as a 403 on every POST/PUT/PATCH/
# DELETE (register, login, everything) with nothing in the app's own logs pointing at CORS —
# comma-separated, no spaces, no trailing slash; see the SecurityConfiguration.allowedOrigins
# quirk in CLAUDE.md. Using the native Android app? Also add https://localhost — Capacitor's
# WebView always loads from that fixed origin, independent of the server URL entered on its
# Connect screen. Edit this and `systemctl restart bocollections-backend` after install.
CORS_ALLOWED_ORIGINS=http://localhost
STORAGE_LOCAL_PATH=/opt/bocollections/data/scan-photos
# Daily backup: a self-contained JSON file per collection (photos embedded as base64) written to
# disk, skipped when nothing's changed since the last one — see ScheduledCollectionExportTask.
# Point cron/rsync/whatever your own off-box backup story is at this directory; disable by setting
# EXPORT_SCHEDULE_ENABLED=false and restarting the service.
EXPORT_SCHEDULE_ENABLED=true
EXPORT_SCHEDULE_INTERVAL_MS=86400000
EXPORT_SCHEDULE_DIRECTORY=/opt/bocollections/data/backups
# Vision AI (scan/thrift photo recognition) is disabled by default — point OLLAMA_BASE_URL at an
# existing Ollama server on your network, or set up a Gemini (or additional Ollama) endpoint via
# the APP_VISION_ENDPOINTS_<n>_<FIELD> indexed env vars — see docs/deployment.md#vision-endpoints
# in the repo — then: systemctl restart bocollections-backend
#OLLAMA_BASE_URL=http://192.168.1.x:11434
#VISION_MODEL=llava-phi3
# Metadata lookup — all optional, barcode scanning works without any of them (Open Library for
# books and MusicBrainz for music need no key at all). VIDEO/GAME barcodes are the one real gap:
# UPCitemdb resolves them to a bare title for free, but TMDB/IGDB is what turns that into actual
# metadata — without one of those, a VIDEO/GAME scan resolves the barcode and then dead-ends.
# Uncomment whichever you want, then: systemctl restart bocollections-backend
#DISCOGS_TOKEN=                 # https://www.discogs.com/settings/developers — improves AUDIO lookup, MusicBrainz covers it either way
#TMDB_API_KEY=                  # https://developer.themoviedb.org — required for VIDEO metadata beyond a bare title
#IGDB_CLIENT_ID=                # https://dev.twitch.tv/console/apps — required (with IGDB_CLIENT_SECRET) for GAME metadata beyond a bare title
#IGDB_CLIENT_SECRET=
#EBAY_CLIENT_ID=                # https://developer.ebay.com/my/keys — real listing photos for VIDEO/GAME, needs a *production* keyset
#EBAY_CLIENT_SECRET=
#THEGAMESDB_API_KEY=            # https://forums.thegamesdb.net/viewforum.php?f=10 — GAME front+back box art, manual approval required
EOF
chmod 640 /etc/bocollections/bocollections.env
chown root:bocollections /etc/bocollections/bocollections.env
chown -R bocollections:bocollections /opt/bocollections
# useradd --create-home leaves /opt/bocollections at 0750 (owner rwx, group rx, other none) since
# it's the bocollections system user's home dir — that blocks nginx (running as www-data, not in
# the bocollections group) from even stat()'ing into frontend/, producing a 500 on every request
# ("Permission denied" in the nginx error log, not an application error). Open traversal on the
# home dir itself and read+traverse on the served frontend bundle; leave data/ (scan photos,
# backups) untouched — nginx never serves that directly, only the backend does, authenticated.
chmod 755 /opt/bocollections
chmod -R a+rX /opt/bocollections/frontend
msg_ok "Configured BOCollections"

msg_info "Configuring Nginx"
cat <<EOF >/etc/nginx/sites-available/bocollections
server {
    listen 80 default_server;
    server_name _;

    root /opt/bocollections/frontend;
    index index.html;

    # base64 photo uploads (see server.tomcat.max-http-form-post-size on the backend side)
    client_max_body_size 50m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    location / {
        try_files \$uri /index.html;
    }
}
EOF
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/bocollections /etc/nginx/sites-enabled/bocollections
$STD systemctl reload nginx 2>/dev/null || $STD systemctl restart nginx
msg_ok "Configured Nginx"

msg_info "Creating Service"
cat <<EOF >/etc/systemd/system/bocollections-backend.service
[Unit]
Description=BOCollections Backend
After=network.target postgresql.service redis-server.service rabbitmq-server.service
Requires=postgresql.service redis-server.service rabbitmq-server.service

[Service]
Type=simple
User=bocollections
Group=bocollections
WorkingDirectory=/opt/bocollections
EnvironmentFile=/etc/bocollections/bocollections.env
ExecStart=/usr/bin/java -jar /opt/bocollections/backend.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl enable -q --now bocollections-backend
msg_ok "Created Service"

motd_ssh
customize

# customize() (above) auto-generates /usr/bin/update from $COMMUNITY_SCRIPTS_URL, which
# ct/bocollections.sh deliberately points at the ProxmoxVED framework fork for its shared
# misc/*.func — not at BOCollections' own ct/bocollections.sh. Point the installed `update`
# command at the right place explicitly.
echo "bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/N0t4R0b0t/BOCollections/main/ct/bocollections.sh)\"" >/usr/bin/update
chmod +x /usr/bin/update

cleanup_lxc
