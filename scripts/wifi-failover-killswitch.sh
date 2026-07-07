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
local_state_file="${WIFI_FAILOVER_LOCAL_KILLSWITCH_FILE:-${script_dir}/../.failover-killswitch}"
port="${WIFI_FAILOVER_PORT:-${MAIN_PHONE_BRIDGE_PORT:-38788}}"

pretty_json() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
  fi
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
      printf 'http://%s:%s\n' "${gateway}" "${port}"
    fi
    if [ -n "${MAIN_PHONE_LOCAL_WIFI_IP:-}" ]; then
      printf 'http://%s:%s\n' "${MAIN_PHONE_LOCAL_WIFI_IP}" "${port}"
    fi
    printf 'http://127.0.0.1:%s\n' "${port}"
  } | awk 'NF && !seen[$0]++'
}

first_bridge_url() {
  while IFS= read -r url; do
    if curl -fsS --max-time 2 "${url%/}/health" >/dev/null 2>&1; then
      printf '%s\n' "${url%/}"
      return 0
    fi
  done < <(bridge_url_candidates)
  return 1
}

local_status_json() {
  if [ -f "${local_state_file}" ]; then
    if command -v jq >/dev/null 2>&1; then
      jq -nc --rawfile body "${local_state_file}" '{enabled:true, state_file:$ARGS.named.path, body:$body}' --arg path "${local_state_file}"
    else
      printf '{"enabled":true,"state_file":"%s"}\n' "${local_state_file}"
    fi
  else
    if command -v jq >/dev/null 2>&1; then
      jq -nc --arg path "${local_state_file}" '{enabled:false, state_file:$path}'
    else
      printf '{"enabled":false,"state_file":"%s"}\n' "${local_state_file}"
    fi
  fi
}

worker_request() {
  method="$1"
  body="${2:-}"
  if [ "${method}" = "GET" ]; then
    curl -fsS -H "authorization: Bearer ${token}" "${endpoint}"
  else
    curl -fsS -X POST "${endpoint}" \
      -H "authorization: Bearer ${token}" \
      -H "content-type: application/json" \
      -d "${body}"
  fi
}

combined_status() {
  worker_json="$(worker_request GET)"
  local_json="$(local_status_json)"
  bridge_url="$(first_bridge_url || true)"
  bridge_json='{"reachable":false}'
  if [ -n "${bridge_url}" ]; then
    bridge_body="$(curl -fsS --max-time 2 "${bridge_url}/health" 2>/dev/null || true)"
    if [ -n "${bridge_body}" ] && command -v jq >/dev/null 2>&1; then
      bridge_json="$(jq -nc --arg url "${bridge_url}" --argjson health "${bridge_body}" '{reachable:true, url:$url, health:$health}')"
    elif [ -n "${bridge_body}" ]; then
      bridge_json="{\"reachable\":true,\"url\":\"${bridge_url}\"}"
    fi
  fi

  if command -v jq >/dev/null 2>&1; then
    jq -nc --argjson worker "${worker_json}" --argjson local "${local_json}" --argjson bridge "${bridge_json}" \
      '{ok:true, worker:$worker.killswitch, mac:$local, phone:$bridge}'
  else
    printf '%s\n' "${worker_json}"
  fi
}

set_local_on() {
  mkdir -p "$(dirname "${local_state_file}")"
  {
    printf 'enabled=true\n'
    printf 'updated_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'reason=%s\n' "${reason}"
  } > "${local_state_file}"
}

set_local_off() {
  rm -f "${local_state_file}"
}

cancel_phone_bridge() {
  bridge_url="$(first_bridge_url || true)"
  if [ -n "${bridge_url}" ]; then
    curl -fsS --max-time 2 "${bridge_url}/cancel" >/dev/null 2>&1 || true
  fi
}

case "${command}" in
  status|get)
    combined_status | pretty_json
    ;;
  on|enable|enabled|true)
    set_local_on
    cancel_phone_bridge
    body="$(jq -nc --arg reason "${reason}" '{enabled:true, reason:$reason}')"
    worker_request POST "${body}" >/dev/null
    combined_status | pretty_json
    ;;
  off|disable|disabled|false)
    set_local_off
    body="$(jq -nc --arg reason "${reason}" '{enabled:false, reason:$reason}')"
    worker_request POST "${body}" >/dev/null
    combined_status | pretty_json
    ;;
  *)
    echo "usage: $0 status|on|off [reason]" >&2
    exit 2
    ;;
esac
