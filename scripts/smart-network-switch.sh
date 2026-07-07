#!/usr/bin/env bash
set -euo pipefail

HOTSPOT_SSID="${WIFI_FAILOVER_HOTSPOT_SSID:-}"
if [ -z "${HOTSPOT_SSID}" ]; then
  HOTSPOT_SSID="Dhruv's Phone"
fi
WIFI_DEVICE="${WIFI_FAILOVER_WIFI_DEVICE:-en0}"
SETTLE_SECONDS="${WIFI_FAILOVER_HOTSPOT_SETTLE_SECONDS:-8}"
CHECK_HOST="${WIFI_FAILOVER_CHECK_HOST:-1.1.1.1}"

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

json_result() {
  ok="$1"
  action="$2"
  internet_ok="$3"
  ssid="$4"
  detail="$5"
  printf '{"ok":%s,"action":"%s","internet_ok":%s,"ssid":"%s","detail":"%s"}\n' \
    "${ok}" \
    "$(json_escape "${action}")" \
    "${internet_ok}" \
    "$(json_escape "${ssid}")" \
    "$(json_escape "${detail}")"
}

current_network() {
  summary="$(ipconfig getsummary "${WIFI_DEVICE}" 2>/dev/null || true)"
  ssid="$(printf '%s\n' "${summary}" | awk -F' : ' '/^[[:space:]]*SSID[[:space:]]*:/ { print $2; exit }')"
  if [ -n "${ssid}" ]; then
    printf '%s\n' "${ssid}"
    return 0
  fi

  networksetup -getairportnetwork "${WIFI_DEVICE}" 2>/dev/null |
    awk -F': ' 'NF > 1 { print $2; exit }'
}

internet_ok() {
  ping -c 1 -W 2000 "${CHECK_HOST}" >/dev/null 2>&1 &&
    curl -fsS --max-time 4 https://www.cloudflare.com/cdn-cgi/trace >/dev/null 2>&1
}

known_wifi_network() {
  networksetup -listpreferredwirelessnetworks "${WIFI_DEVICE}" 2>/dev/null |
    awk -v ssid="${HOTSPOT_SSID}" 'NR > 1 && $0 ~ "^[[:space:]]*" ssid "$" { found = 1 } END { exit(found ? 0 : 1) }'
}

join_hotspot() {
  if password="$(security find-generic-password -wa "${HOTSPOT_SSID}" 2>/dev/null)" && [ -n "${password}" ]; then
    networksetup -setairportnetwork "${WIFI_DEVICE}" "${HOTSPOT_SSID}" "${password}" >/dev/null
  else
    networksetup -setairportnetwork "${WIFI_DEVICE}" "${HOTSPOT_SSID}" >/dev/null
  fi
}

before_ssid="$(current_network | head -n 1)"
if internet_ok; then
  json_result true "none" true "${before_ssid}" "current network has internet"
  exit 0
fi

if ! known_wifi_network; then
  json_result false "none" false "${before_ssid}" "fallback network is not saved"
  exit 1
fi

if [ "${before_ssid}" = "${HOTSPOT_SSID}" ]; then
  json_result false "none" false "${before_ssid}" "already on fallback network but internet is still down"
  exit 1
fi

if ! join_hotspot; then
  json_result false "switch_failed" false "${before_ssid}" "networksetup could not join fallback network"
  exit 1
fi

sleep "${SETTLE_SECONDS}"
after_ssid="$(current_network | head -n 1)"
if internet_ok; then
  json_result true "switched" true "${after_ssid}" "fallback network has internet"
  exit 0
fi

json_result false "switched" false "${after_ssid}" "fallback network joined but internet probe failed"
exit 1
