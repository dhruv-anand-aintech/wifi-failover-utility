#!/usr/bin/env bash
set -euo pipefail

command="${1:-status}"
reason="${2:-manual ${command}}"

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

base_url="${WIFI_FAILOVER_WORKER_BASE_URL:-}"
if [ -z "${base_url}" ] && [ -n "${MAIN_PHONE_WAKE_WEBHOOK_URL:-}" ]; then
  base_url="${MAIN_PHONE_WAKE_WEBHOOK_URL%/phone-wake/request}"
fi
base_url="${base_url:-https://updates.ainorthstar.tech}"
token="${WIFI_FAILOVER_WAKE_WEBHOOK_TOKEN:-${MAIN_PHONE_WAKE_WEBHOOK_TOKEN:-${PHONE_USAGE_INGEST_TOKEN:-}}}"

if [ -z "${token}" ]; then
  echo "missing token: set WIFI_FAILOVER_WAKE_WEBHOOK_TOKEN, MAIN_PHONE_WAKE_WEBHOOK_TOKEN, or PHONE_USAGE_INGEST_TOKEN" >&2
  exit 1
fi

endpoint="${base_url%/}/wifi-failover/killswitch"

pretty_json() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
  fi
}

case "${command}" in
  status|get)
    curl -fsS -H "authorization: Bearer ${token}" "${endpoint}" | pretty_json
    ;;
  on|enable|enabled|true)
    body="$(jq -nc --arg reason "${reason}" '{enabled:true, reason:$reason}')"
    curl -fsS -X POST "${endpoint}" \
      -H "authorization: Bearer ${token}" \
      -H "content-type: application/json" \
      -d "${body}" | pretty_json
    ;;
  off|disable|disabled|false)
    body="$(jq -nc --arg reason "${reason}" '{enabled:false, reason:$reason}')"
    curl -fsS -X POST "${endpoint}" \
      -H "authorization: Bearer ${token}" \
      -H "content-type: application/json" \
      -d "${body}" | pretty_json
    ;;
  *)
    echo "usage: $0 status|on|off [reason]" >&2
    exit 2
    ;;
esac
