#!/usr/bin/env bash
set -euo pipefail

PORT="${WIFI_FAILOVER_PORT:-38788}"
CHECK_HOST="${WIFI_FAILOVER_CHECK_HOST:-8.8.8.8}"
CHECK_INTERVAL="${WIFI_FAILOVER_CHECK_INTERVAL:-5}"
FAILURE_THRESHOLD="${WIFI_FAILOVER_FAILURE_THRESHOLD:-3}"

failures=0
triggered=0

adb forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null

echo "wifi-failover local monitor: checking ${CHECK_HOST}, phone endpoint http://127.0.0.1:${PORT}"

while true; do
  if ping -c 1 -W 2000 "${CHECK_HOST}" >/dev/null 2>&1; then
    failures=0
    triggered=0
  else
    failures=$((failures + 1))
    echo "connectivity check failed (${failures}/${FAILURE_THRESHOLD})"

    if [ "${failures}" -ge "${FAILURE_THRESHOLD}" ] && [ "${triggered}" -eq 0 ]; then
      echo "requesting phone hotspot enable"
      curl -fsS "http://127.0.0.1:${PORT}/enable-hotspot" || true
      echo
      triggered=1
    fi
  fi

  sleep "${CHECK_INTERVAL}"
done
