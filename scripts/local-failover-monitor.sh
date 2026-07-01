#!/usr/bin/env bash
set -euo pipefail

PORT="${WIFI_FAILOVER_PORT:-38788}"
PHONE_SERIAL="${WIFI_FAILOVER_PHONE_SERIAL:-}"
CHECK_HOST="${WIFI_FAILOVER_CHECK_HOST:-8.8.8.8}"
CHECK_INTERVAL="${WIFI_FAILOVER_CHECK_INTERVAL:-5}"
FAILURE_THRESHOLD="${WIFI_FAILOVER_FAILURE_THRESHOLD:-3}"
HOTSPOT_SSID="Dhruv's Phone"
HOTSPOT_SETTLE_SECONDS="${WIFI_FAILOVER_HOTSPOT_SETTLE_SECONDS:-8}"
WAKE_RETRY_SECONDS="${WIFI_FAILOVER_WAKE_RETRY_SECONDS:-120}"
HEARTBEAT_INTERVAL="${WIFI_FAILOVER_HEARTBEAT_INTERVAL:-10}"

failures=0
triggered=0
last_wake_at=0
last_heartbeat_at=0
failover_disabled=0

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"

if [ -f "${repo_root}/.env" ]; then
  # shellcheck disable=SC1090
  source "${repo_root}/.env"
fi

if [ -f "${repo_root}/.main-phone-remote.env" ]; then
  # shellcheck disable=SC1090
  source "${repo_root}/.main-phone-remote.env"
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

ADB=(adb)
if [ -n "${PHONE_SERIAL}" ]; then
  ADB+=(-s "${PHONE_SERIAL}")
fi

forward_bridge_port() {
  if [ -n "${PHONE_SERIAL}" ] && [[ "${PHONE_SERIAL}" == *:* ]]; then
    "${ADB[@]}" connect "${PHONE_SERIAL}" >/dev/null 2>&1 || true
  fi
  "${ADB[@]}" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null
}

reset_bridge_forward() {
  "${ADB[@]}" forward --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  forward_bridge_port
}

trigger_tailscale_wake() {
  if [ "${failover_disabled}" -eq 1 ]; then
    echo "Wi-Fi failover killswitch is enabled; skipping Tailscale wake request"
    return 1
  fi
  if [ -z "${WAKE_WEBHOOK_URL}" ] || [ -z "${WAKE_WEBHOOK_TOKEN}" ]; then
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

bridge_health() {
  if forward_bridge_port && curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/health" >/dev/null; then
    return 0
  fi

  echo "Bridge health failed through existing ADB forward; refreshing tcp:${PORT}"
  if reset_bridge_forward && curl -fsS --max-time 4 "http://127.0.0.1:${PORT}/health" >/dev/null; then
    echo "Bridge local control recovered after ADB forward refresh"
    return 0
  fi

  trigger_tailscale_wake || true
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

known_wifi_network() {
  networksetup -listpreferredwirelessnetworks en0 2>/dev/null |
    awk -v ssid="${HOTSPOT_SSID}" 'NR > 1 && $0 ~ "^[[:space:]]*" ssid "$" { found = 1 } END { exit(found ? 0 : 1) }'
}

connect_to_hotspot() {
  if ! known_wifi_network; then
    echo "hotspot network '${HOTSPOT_SSID}' is not saved on en0; skipping Mac Wi-Fi switch"
    return 1
  fi

  echo "attempting Mac Wi-Fi switch to '${HOTSPOT_SSID}'"
  if password="$(security find-generic-password -wa "${HOTSPOT_SSID}" 2>/dev/null)" && [ -n "${password}" ]; then
    networksetup -setairportnetwork en0 "${HOTSPOT_SSID}" "${password}"
  else
    networksetup -setairportnetwork en0 "${HOTSPOT_SSID}"
  fi
}

echo "Bridge failover monitor: checking ${CHECK_HOST}, Bridge endpoint http://127.0.0.1:${PORT}"
if [ -n "${PHONE_SERIAL}" ]; then
  echo "ADB target: ${PHONE_SERIAL}"
fi
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
  echo "Bridge local control is not reachable yet; wake and hotspot switch will still be attempted after failures"
fi

while true; do
  if ping -c 1 -W 2000 "${CHECK_HOST}" >/dev/null 2>&1; then
    failures=0
    triggered=0
    if bridge_health >/dev/null 2>&1; then
      post_heartbeat true true || true
    else
      post_heartbeat true false || true
    fi
  else
    failures=$((failures + 1))
    echo "connectivity check failed (${failures}/${FAILURE_THRESHOLD})"
    if [ "${failures}" -ge "${FAILURE_THRESHOLD}" ]; then
      post_heartbeat false false || true
    else
      post_heartbeat true false || true
    fi

    if [ "${failures}" -ge "${FAILURE_THRESHOLD}" ] && [ "${triggered}" -eq 0 ]; then
      if [ "${failover_disabled}" -eq 1 ]; then
        echo "Wi-Fi failover killswitch is enabled; skipping hotspot trigger"
        triggered=1
        sleep "${CHECK_INTERVAL}"
        continue
      fi
      echo "requesting Bridge hotspot enable"
      forward_bridge_port || true
      if curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/enable-hotspot"; then
        echo
      else
        echo "Bridge hotspot request failed; trying Mac hotspot switch anyway"
      fi
      sleep "${HOTSPOT_SETTLE_SECONDS}"
      connect_to_hotspot || true
      triggered=1
    fi
  fi

  sleep "${CHECK_INTERVAL}"
done
