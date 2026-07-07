#!/usr/bin/env bash
set -euo pipefail

PORT="${WIFI_FAILOVER_PORT:-38788}"
PHONE_SERIAL="${WIFI_FAILOVER_PHONE_SERIAL:-}"
CHECK_HOST="${WIFI_FAILOVER_CHECK_HOST:-8.8.8.8}"
CHECK_INTERVAL="${WIFI_FAILOVER_CHECK_INTERVAL:-5}"
FAILURE_THRESHOLD="${WIFI_FAILOVER_FAILURE_THRESHOLD:-3}"
RECOVERY_THRESHOLD="${WIFI_FAILOVER_RECOVERY_THRESHOLD:-6}"
ACTION_COOLDOWN_SECONDS="${WIFI_FAILOVER_ACTION_COOLDOWN_SECONDS:-900}"
HOTSPOT_SSID="Dhruv's Phone"
HOTSPOT_SETTLE_SECONDS="${WIFI_FAILOVER_HOTSPOT_SETTLE_SECONDS:-8}"
WAKE_RETRY_SECONDS="${WIFI_FAILOVER_WAKE_RETRY_SECONDS:-120}"
HEARTBEAT_INTERVAL="${WIFI_FAILOVER_HEARTBEAT_INTERVAL:-10}"

failures=0
successes=0
triggered=0
last_failover_action_at=0
last_wake_at=0
last_heartbeat_at=0
failover_disabled=0
active_phone_serial=""
active_bridge_base_url=""

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
SMART_NETWORK_SWITCH_SCRIPT="${script_dir}/smart-network-switch.sh"
LOCAL_KILLSWITCH_FILE="${WIFI_FAILOVER_LOCAL_KILLSWITCH_FILE:-${script_dir}/../.failover-killswitch}"

if [ -f "${repo_root}/.env" ]; then
  # shellcheck disable=SC1090
  source "${repo_root}/.env"
fi

if [ -f "${repo_root}/.main-phone-remote.env" ]; then
  # shellcheck disable=SC1090
  source "${repo_root}/.main-phone-remote.env"
fi

timestamp_stream() {
  while IFS= read -r line; do
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "${line}"
  done
}

if [ "${WIFI_FAILOVER_LOG_TIMESTAMPS:-1}" != "0" ]; then
  exec > >(timestamp_stream) 2> >(timestamp_stream >&2)
fi

WAKE_WEBHOOK_URL="${WIFI_FAILOVER_WAKE_WEBHOOK_URL:-${MAIN_PHONE_WAKE_WEBHOOK_URL:-}}"
WAKE_WEBHOOK_TOKEN="${WIFI_FAILOVER_WAKE_WEBHOOK_TOKEN:-${MAIN_PHONE_WAKE_WEBHOOK_TOKEN:-}}"
WAKE_PHONE_ID="${WIFI_FAILOVER_WAKE_PHONE_ID:-${MAIN_PHONE_WAKE_PHONE_ID:-main}}"
HEARTBEAT_URL="${WIFI_FAILOVER_HEARTBEAT_URL:-${MAIN_PHONE_WIFI_FAILOVER_HEARTBEAT_URL:-}}"
if [ -z "${HEARTBEAT_URL}" ] && [[ "${WAKE_WEBHOOK_URL}" == */phone-wake/request ]]; then
  HEARTBEAT_URL="${WAKE_WEBHOOK_URL%/phone-wake/request}/wifi-failover/heartbeat"
fi
MAC_ID="${WIFI_FAILOVER_MAC_ID:-${MAIN_PHONE_WIFI_FAILOVER_MAC_ID:-}}"
if [ -z "${MAC_ID}" ]; then
  MAC_ID="$(hostname -s 2>/dev/null || echo mac)"
fi
MAC_ID="$(printf '%s' "${MAC_ID}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-' | cut -c1-64)"
if [ -z "${MAC_ID}" ]; then
  MAC_ID="mac"
fi

adb_command() {
  serial="$1"
  shift
  if [ -n "${serial}" ]; then
    adb -s "${serial}" "$@"
  else
    adb "$@"
  fi
}

phone_serial_candidates() {
  {
    if [ -n "${PHONE_SERIAL}" ]; then
      printf '%s\n' "${PHONE_SERIAL}"
    fi
    if [ -n "${MAIN_PHONE_ADB_SERIAL:-}" ]; then
      printf '%s\n' "${MAIN_PHONE_ADB_SERIAL}"
    fi
    if [ -n "${MAIN_PHONE_LOCAL_ADB_PORT:-}" ]; then
      printf '127.0.0.1:%s\n' "${MAIN_PHONE_LOCAL_ADB_PORT}"
    fi
    if [ -n "${MAIN_PHONE_LOCAL_WIFI_IP:-}" ]; then
      printf '%s:%s\n' "${MAIN_PHONE_LOCAL_WIFI_IP}" "${MAIN_PHONE_WIRELESS_ADB_PORT:-5555}"
    fi
  } | awk 'NF && !seen[$0]++'
}

default_gateway() {
  route -n get default 2>/dev/null | awk '/gateway:/ { print $2; exit }'
}

bridge_url_candidates() {
  {
    if [ -n "${WIFI_FAILOVER_BRIDGE_BASE_URL:-}" ]; then
      printf '%s\n' "${WIFI_FAILOVER_BRIDGE_BASE_URL%/}"
    fi
    gateway="$(default_gateway)"
    if [ -n "${gateway}" ]; then
      printf 'http://%s:%s\n' "${gateway}" "${MAIN_PHONE_BRIDGE_PORT:-${PORT}}"
    fi
    if [ -n "${MAIN_PHONE_LOCAL_WIFI_IP:-}" ]; then
      printf 'http://%s:%s\n' "${MAIN_PHONE_LOCAL_WIFI_IP}" "${MAIN_PHONE_BRIDGE_PORT:-${PORT}}"
    fi
    printf 'http://127.0.0.1:%s\n' "${PORT}"
  } | awk 'NF && !seen[$0]++'
}

bridge_url_reachable() {
  url="$1"
  host_port="${url#http://}"
  host_port="${host_port#https://}"
  host_port="${host_port%%/*}"
  host="${host_port%:*}"
  port="${host_port##*:}"
  if [ -n "${host}" ] && [ -n "${port}" ] && [ "${host}" != "${port}" ] && command -v nc >/dev/null 2>&1; then
    nc -G 1 -z "${host}" "${port}" >/dev/null 2>&1 || return 1
  fi
  curl -fsS --max-time 2 "${url%/}/health" >/dev/null
}

direct_bridge_health() {
  candidates="$(bridge_url_candidates)"
  while IFS= read -r url; do
    if bridge_url_reachable "${url}"; then
      active_bridge_base_url="${url%/}"
      if [ "${active_bridge_base_url}" != "http://127.0.0.1:${PORT}" ]; then
        echo "Bridge direct control is reachable at ${active_bridge_base_url}"
      fi
      return 0
    fi
  done <<< "${candidates}"
  return 1
}

connect_serial_if_needed() {
  serial="$1"
  if [ -n "${serial}" ] && [[ "${serial}" == *:* ]]; then
    host="${serial%:*}"
    port="${serial##*:}"
    if command -v nc >/dev/null 2>&1 && ! nc -G 1 -z "${host}" "${port}" >/dev/null 2>&1; then
      return 1
    fi
    adb connect "${serial}" >/dev/null 2>&1 || true
  fi
}

forward_bridge_port_for_serial() {
  serial="$1"
  connect_serial_if_needed "${serial}" || return 1
  adb_command "${serial}" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null
}

forward_bridge_port() {
  forward_bridge_port_for_serial "${active_phone_serial}"
}

reset_bridge_forward() {
  serial="$1"
  adb_command "${serial}" forward --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  forward_bridge_port_for_serial "${serial}"
}

tailscale_peer_online() {
  target="${MAIN_PHONE_TAILSCALE_IP:-}"
  if [ -z "${target}" ] || ! command -v tailscale >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    return 1
  fi

  socket="${repo_root}/.tailscaled.sock"
  if [ -S "${socket}" ]; then
    tailscale --socket="${socket}" status --json 2>/dev/null |
      jq -e --arg ip "${target}" '.Peer | to_entries[] | select((.value.TailscaleIPs // []) | index($ip)) | select(.value.Online == true)' >/dev/null
  else
    tailscale status --json 2>/dev/null |
      jq -e --arg ip "${target}" '.Peer | to_entries[] | select((.value.TailscaleIPs // []) | index($ip)) | select(.value.Online == true)' >/dev/null
  fi
}

trigger_tailscale_wake() {
  if [ "${failover_disabled}" -eq 1 ]; then
    echo "Wi-Fi failover killswitch is enabled; skipping Tailscale wake request"
    return 1
  fi
  if [ -z "${WAKE_WEBHOOK_URL}" ] || [ -z "${WAKE_WEBHOOK_TOKEN}" ]; then
    return 1
  fi
  if tailscale_peer_online; then
    echo "Main phone Tailscale peer is already online; skipping Tailscale wake request"
    return 1
  fi

  now="$(date +%s)"
  if [ $((now - last_wake_at)) -lt "${WAKE_RETRY_SECONDS}" ]; then
    return 1
  fi
  last_wake_at="${now}"

  echo "Bridge local control unavailable; requesting Tailscale wake for ${WAKE_PHONE_ID}"
  curl -fsS --max-time 10 \
    -X POST "${WAKE_WEBHOOK_URL}" \
    -H "authorization: Bearer ${WAKE_WEBHOOK_TOKEN}" \
    -H "content-type: application/json" \
    -d "{\"phone_id\":\"${WAKE_PHONE_ID}\",\"action\":\"enable_tailscale\"}" \
    >/dev/null
}

bridge_health_for_serial() {
  serial="$1"
  if forward_bridge_port_for_serial "${serial}" && curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/health" >/dev/null; then
    active_phone_serial="${serial}"
    active_bridge_base_url="http://127.0.0.1:${PORT}"
    return 0
  fi

  if reset_bridge_forward "${serial}" && curl -fsS --max-time 4 "http://127.0.0.1:${PORT}/health" >/dev/null; then
    active_phone_serial="${serial}"
    active_bridge_base_url="http://127.0.0.1:${PORT}"
    if [ -n "${serial}" ]; then
      echo "Bridge local control recovered after ADB forward refresh via ${serial}"
    else
      echo "Bridge local control recovered after ADB forward refresh"
    fi
    return 0
  fi

  return 1
}

bridge_health() {
  if direct_bridge_health; then
    return 0
  fi

  candidates="$(phone_serial_candidates)"
  if [ -z "${candidates}" ]; then
    bridge_health_for_serial ""
    return $?
  fi

  while IFS= read -r serial; do
    if bridge_health_for_serial "${serial}"; then
      return 0
    fi
  done <<< "${candidates}"

  return 1
}

post_heartbeat() {
  internet_ok="$1"
  bridge_ok="$2"
  now="$(date +%s)"
  if [ -z "${HEARTBEAT_URL}" ] || [ -z "${WAKE_WEBHOOK_TOKEN}" ]; then
    return 1
  fi
  if [ $((now - last_heartbeat_at)) -lt "${HEARTBEAT_INTERVAL}" ]; then
    return 1
  fi
  last_heartbeat_at="${now}"

  curl -fsS --max-time 4 \
    -o /tmp/wifi-failover-heartbeat-response.json \
    -X POST "${HEARTBEAT_URL}" \
    -H "authorization: Bearer ${WAKE_WEBHOOK_TOKEN}" \
    -H "content-type: application/json" \
    -d "{\"mac_id\":\"${MAC_ID}\",\"phone_id\":\"${WAKE_PHONE_ID}\",\"armed\":true,\"internet_ok\":${internet_ok},\"bridge_ok\":${bridge_ok},\"hotspot_ssid\":\"${HOTSPOT_SSID}\"}"
  if grep -q '"failover_disabled":true' /tmp/wifi-failover-heartbeat-response.json 2>/dev/null; then
    if [ "${failover_disabled}" -eq 0 ]; then
      echo "Wi-Fi failover killswitch is enabled"
    fi
    failover_disabled=1
  else
    if [ "${failover_disabled}" -eq 1 ]; then
      echo "Wi-Fi failover killswitch is disabled"
    fi
    failover_disabled=0
  fi
}

local_killswitch_enabled() {
  [ -f "${LOCAL_KILLSWITCH_FILE}" ]
}

echo "Bridge failover monitor: checking ${CHECK_HOST}, Bridge endpoint http://127.0.0.1:${PORT}"
if [ -n "${PHONE_SERIAL}" ]; then
  echo "Primary ADB target: ${PHONE_SERIAL}"
fi
echo "ADB target candidates:"
phone_serial_candidates | sed 's/^/  - /'
echo "Bridge URL candidates:"
bridge_url_candidates | sed 's/^/  - /'
echo "Hotspot SSID: ${HOTSPOT_SSID}"
if [ -n "${WAKE_WEBHOOK_URL}" ]; then
  echo "Tailscale wake webhook configured for phone ID ${WAKE_PHONE_ID}"
fi
if [ -n "${HEARTBEAT_URL}" ]; then
  echo "Wi-Fi failover heartbeat configured for Mac ${MAC_ID}, phone ID ${WAKE_PHONE_ID}"
fi
if bridge_health; then
  echo "Bridge local control is reachable"
else
  echo "Bridge local control is not reachable yet; failover will still report unhealthy heartbeats"
fi

while true; do
  if local_killswitch_enabled; then
    if [ "${failover_disabled}" -eq 0 ]; then
      echo "Wi-Fi failover local killswitch is enabled; monitor actions are disabled"
    fi
    failover_disabled=1
    failures=0
    successes=0
    triggered=1
    sleep "${CHECK_INTERVAL}"
    continue
  elif [ "${failover_disabled}" -eq 1 ]; then
    echo "Wi-Fi failover local killswitch is disabled; monitor actions may resume"
    failover_disabled=0
    triggered=0
  fi

  if ping -c 1 -W 2000 "${CHECK_HOST}" >/dev/null 2>&1; then
    failures=0
    successes=$((successes + 1))
    if [ "${triggered}" -eq 1 ] && [ "${successes}" -ge "${RECOVERY_THRESHOLD}" ]; then
      echo "connectivity recovered after ${successes} consecutive successful checks; re-arming failover"
      triggered=0
    fi
    if bridge_health >/dev/null 2>&1; then
      post_heartbeat true true || true
    else
      post_heartbeat true false || true
    fi
  else
    successes=0
    failures=$((failures + 1))
    echo "connectivity check failed (${failures}/${FAILURE_THRESHOLD})"
    bridge_ok=false
    if bridge_health >/dev/null 2>&1; then
      bridge_ok=true
    fi
    if [ "${failures}" -lt "${FAILURE_THRESHOLD}" ]; then
      post_heartbeat true "${bridge_ok}" || true
    else
      last_heartbeat_at=0
      post_heartbeat false "${bridge_ok}" || true
    fi

    if [ "${failures}" -ge "${FAILURE_THRESHOLD}" ] && [ "${triggered}" -eq 0 ]; then
      now="$(date +%s)"
      if [ $((now - last_failover_action_at)) -lt "${ACTION_COOLDOWN_SECONDS}" ]; then
        echo "failover action cooldown active; skipping repeated trigger"
        triggered=1
        sleep "${CHECK_INTERVAL}"
        continue
      fi
      if [ "${failover_disabled}" -eq 1 ]; then
        echo "Wi-Fi failover killswitch is enabled; skipping network failover action"
        triggered=1
        sleep "${CHECK_INTERVAL}"
        continue
      fi
      echo "reporting unhealthy heartbeat; Bridge will decide phone-side actions"
      echo "choosing a Mac Wi-Fi network with working internet"
      if [ -x "${SMART_NETWORK_SWITCH_SCRIPT}" ]; then
        "${SMART_NETWORK_SWITCH_SCRIPT}" || true
      else
        echo "smart network switch script missing: ${SMART_NETWORK_SWITCH_SCRIPT}"
      fi
      last_failover_action_at="${now}"
      triggered=1
    fi
  fi

  sleep "${CHECK_INTERVAL}"
done
