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
APPLICATION="$APP" PG_DB_NAME="collections" PG_DB_USER="collections" PG_DB_SCHEMA_PERMS="true" setup_postgresql_db
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
# existing Ollama server on your network (or set up Gemini via app.vision.endpoints, which isn't
# env-var driven — see backend/src/main/resources/application-local.yml in the repo for the
# multi-endpoint YAML shape) then: systemctl restart bocollections-backend
#OLLAMA_BASE_URL=http://192.168.1.x:11434
#VISION_MODEL=llava-phi3
EOF
chmod 640 /etc/bocollections/bocollections.env
chown root:bocollections /etc/bocollections/bocollections.env
chown -R bocollections:bocollections /opt/bocollections
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
