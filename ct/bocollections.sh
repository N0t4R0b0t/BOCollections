#!/usr/bin/env bash

# BOCollections — smart collection management app (books, magazines, CDs, vinyl, DVDs, games).
# Repo: https://github.com/N0t4R0b0t/BOCollections
#
# This script (and install/bocollections-install.sh) live in the BOCollections repo itself, not
# in the ProxmoxVED fork below — that fork only supplies the shared community-scripts framework
# (setup_java, setup_postgresql, msg_info, build_container, …). Everything under misc/ resolves
# there; install/bocollections-install.sh resolves from BOCollections instead, via the local-
# checkout preseed below (build.func always prefers a local file over its curl fallback).
export COMMUNITY_SCRIPTS_URL="https://raw.githubusercontent.com/N0t4R0b0t/ProxmoxVED/main"
BOCOLLECTIONS_REPO_URL="https://raw.githubusercontent.com/N0t4R0b0t/BOCollections/main"

_bocollections_local_root="$(mktemp -d)"
mkdir -p "${_bocollections_local_root}/misc" "${_bocollections_local_root}/install"
curl -fsSL "${BOCOLLECTIONS_REPO_URL}/install/bocollections-install.sh" \
  -o "${_bocollections_local_root}/install/bocollections-install.sh"
export COMMUNITY_SCRIPTS_DIR="${_bocollections_local_root}/misc"
export COMMUNITY_SCRIPTS_ROOT="${_bocollections_local_root}"
unset _bocollections_local_root

source <(curl -fsSL "${COMMUNITY_SCRIPTS_URL}/misc/build.func")

# Copyright (c) 2021-2026 community-scripts ORG (framework) / N0t4R0b0t (this app script)
# Author: N0t4R0b0t
# License: MIT | https://github.com/community-scripts/ProxmoxVED/raw/main/LICENSE
# Source: https://github.com/N0t4R0b0t/BOCollections

APP="BOCollections"
var_tags="${var_tags:-media;collections;self-hosted}"
var_cpu="${var_cpu:-4}"
var_ram="${var_ram:-4096}"
var_disk="${var_disk:-12}"
var_os="${var_os:-debian}"
var_version="${var_version:-13}"
var_arm64="${var_arm64:-no}"
var_unprivileged="${var_unprivileged:-1}"

header_info "$APP"
variables
color
catch_errors

# BOCollections' own release-artifact fetch/deploy — not part of the shared ProxmoxVE framework
# (which only knows how to fetch GitHub/Codeberg releases). Mirrors that framework's own
# check_for_gh_release()/fetch_and_deploy_gh_release() shape (marker file at $HOME/.<app>,
# msg_ok "Update available: X → Y" phrasing) so it reads the same way, just pointed at R2.
R2_RELEASES_URL="https://pub-73de8ecb4a9644fc8072f4e6bb9c700a.r2.dev/bocollections/releases"

bocollections_latest_version() {
  curl -fsSL "${R2_RELEASES_URL}/latest.txt" | tr -d '[:space:]'
}

# Downloads and swaps in the backend jar + frontend bundle for $1 (a version string). Does not
# touch systemd units, nginx config, or the database — callers stop/start services around this.
bocollections_deploy_release() {
  local version="$1"
  local tmp_jar tmp_frontend
  tmp_jar="$(mktemp)"
  tmp_frontend="$(mktemp)"

  msg_info "Downloading BOCollections ${version}"
  curl -fsSL "${R2_RELEASES_URL}/${version}/bocollections-backend.jar" -o "$tmp_jar" || {
    msg_error "Failed to download backend jar for ${version}"
    rm -f "$tmp_jar" "$tmp_frontend"
    return 1
  }
  curl -fsSL "${R2_RELEASES_URL}/${version}/bocollections-frontend.tar.gz" -o "$tmp_frontend" || {
    msg_error "Failed to download frontend bundle for ${version}"
    rm -f "$tmp_jar" "$tmp_frontend"
    return 1
  }
  msg_ok "Downloaded BOCollections ${version}"

  msg_info "Deploying BOCollections ${version}"
  mv "$tmp_jar" /opt/bocollections/backend.jar
  rm -rf /opt/bocollections/frontend
  mkdir -p /opt/bocollections/frontend
  tar -xzf "$tmp_frontend" -C /opt/bocollections/frontend
  rm -f "$tmp_frontend"
  chown -R bocollections:bocollections /opt/bocollections
  echo "$version" >"$HOME/.bocollections"
  msg_ok "Deployed BOCollections ${version}"
}

function update_script() {
  header_info
  check_container_storage
  check_container_resources

  if [[ ! -f /etc/systemd/system/bocollections-backend.service ]]; then
    msg_error "No ${APP} Installation Found!"
    exit
  fi

  local current_version latest_version
  current_version="$(cat "$HOME/.bocollections" 2>/dev/null || echo "")"
  latest_version="$(bocollections_latest_version)"

  if [[ -z "$latest_version" ]]; then
    msg_error "Could not reach R2 to check for updates"
    exit
  fi

  if [[ "$current_version" == "$latest_version" ]]; then
    msg_ok "No update available: ${APP} (${current_version})"
    exit
  fi
  msg_ok "Update available: ${APP} ${current_version:-not installed} → ${latest_version}"

  msg_info "Stopping Service"
  systemctl stop bocollections-backend
  msg_ok "Stopped Service"

  bocollections_deploy_release "$latest_version" || {
    msg_error "Deploy failed — restarting the previous version"
    systemctl start bocollections-backend
    exit 1
  }

  msg_info "Starting Service"
  systemctl start bocollections-backend
  msg_ok "Started Service"
  msg_ok "Updated successfully!"
  exit
}

start
build_container
description

msg_ok "Completed Successfully!\n"
echo -e "${CREATING}${GN}${APP} setup has been successfully initialized!${CL}"
echo -e "${INFO}${YW}Access it using the following URL:${CL}"
echo -e "${GATEWAY}${BGN}http://${IP}${CL}"
echo -e "${INFO}${YW}Vision AI (scan/thrift photo recognition) is disabled by default — point it at an${CL}"
echo -e "${YW}existing Ollama server or Gemini API key by editing /etc/bocollections/bocollections.env${CL}"
echo -e "${YW}and restarting: systemctl restart bocollections-backend${CL}"
