# Bridge Failover Monitor

Mac-side connectivity watcher for Bridge.

This repo no longer builds or ships a standalone Android app. The phone-side
hotspot and Tailscale controls live in the existing Bridge app package:

```text
tech.ainorthstar.usagepush
```

Bridge exposes a local HTTP control server on the phone at port `38788`. This
repo only keeps the Mac helper that forwards that port over ADB and calls
`/enable-hotspot` after repeated connectivity failures, then switches the Mac to
the saved hotspot SSID `Dhruv's Phone`.

## Requirements

- Bridge installed and running on the Android phone.
- ADB connected to the phone over USB or wireless debugging.
- Bridge Accessibility service enabled for hotspot UI automation.

## Run

For the main phone:

```bash
export WIFI_FAILOVER_PHONE_SERIAL=192.168.0.110:5555
scripts/local-failover-monitor.sh
```

The script:

1. Forwards `127.0.0.1:38788` to Bridge on the phone.
2. Runs `adb connect` for host:port serials before forwarding, so a known
   Wi-Fi ADB endpoint is actively attached instead of only assumed present.
3. If Bridge `/health` is unreachable and a wake webhook is configured, POSTs
   `enable_tailscale` to the updates Worker for the configured phone ID.
4. Refreshes Bridge `/health` while internet is still healthy, so the local
   ADB path is already warm before an outage.
5. Sends a token-authenticated heartbeat to
   `https://updates.ainorthstar.tech/wifi-failover/heartbeat` while healthy.
   The Worker can synthesize `enable_hotspot` from stale heartbeats when Bridge
   polls over phone data.
6. Pings `8.8.8.8` every 5 seconds.
7. Calls `http://127.0.0.1:38788/enable-hotspot` after 3 failures.
8. Connects the Mac to the saved Wi-Fi network `Dhruv's Phone` if it exists,
   even if the Bridge request fails.

This works when upstream internet is down but the Mac can still reach the phone
locally over ADB, for example on the same Wi-Fi LAN or USB. If Wi-Fi/LAN itself
is gone, this Mac cannot trigger the phone; Bridge needs an autonomous
phone-side failover path or a live USB transport.

## Cloud Command Path

Bridge already polls the updates Worker wake queue. A remote command can enqueue
hotspot enablement while the phone still has cellular data:

```bash
curl -fsS -X POST https://updates.ainorthstar.tech/phone-wake/request \
  -H "authorization: Bearer $PHONE_USAGE_INGEST_TOKEN" \
  -H "content-type: application/json" \
  -d '{"phone_id":"main","action":"enable_hotspot"}'
```

This is useful from another connected device. The deployed Worker also supports
the autonomous stale-heartbeat path: the Mac monitor POSTs heartbeat state while
healthy, Bridge polls `/phone-wake/poll?phone_id=<phone_id>`, and the Worker
returns a synthetic `enable_hotspot` command if the Mac heartbeat is stale or
reports `internet_ok=false`. Bridge ACKs consumed commands through
`/phone-wake/ack`.

Emergency killswitch:

```bash
scripts/wifi-failover-killswitch.sh on "overtriggering"
```

Reset it with `scripts/wifi-failover-killswitch.sh off`, and check it with
`scripts/wifi-failover-killswitch.sh status`. When enabled, the Worker
suppresses queued and synthetic phone wake commands, and the Mac monitor skips
local hotspot/Tailscale wake actions after it sees the heartbeat response.

## LaunchAgent

The repo ships a user LaunchAgent at:

```text
launchd/tech.ainorthstar.wifi-failover-monitor.plist
```

It runs the same monitor from the moved `phone-debug` location and writes logs
under `wifi-failover-utility/logs/`.

## Configuration

```bash
WIFI_FAILOVER_PHONE_SERIAL=192.168.0.110:5555
WIFI_FAILOVER_PORT=38788
WIFI_FAILOVER_CHECK_HOST=8.8.8.8
WIFI_FAILOVER_CHECK_INTERVAL=5
WIFI_FAILOVER_FAILURE_THRESHOLD=3
WIFI_FAILOVER_HOTSPOT_SETTLE_SECONDS=8
WIFI_FAILOVER_WAKE_RETRY_SECONDS=120
WIFI_FAILOVER_HEARTBEAT_INTERVAL=10
WIFI_FAILOVER_MAC_ID=dhruvs-macbook-pro-2
```

The wake webhook is sourced from the parent repo's `.env` and
`.main-phone-remote.env` by default:

```bash
MAIN_PHONE_WAKE_WEBHOOK_URL=https://updates.ainorthstar.tech/phone-wake/request
MAIN_PHONE_WAKE_WEBHOOK_TOKEN=...
MAIN_PHONE_WAKE_PHONE_ID=cph2573
```

If `WIFI_FAILOVER_HEARTBEAT_URL` is unset, the monitor derives it from
`MAIN_PHONE_WAKE_WEBHOOK_URL` by replacing `/phone-wake/request` with
`/wifi-failover/heartbeat`.

## Manual Checks

```bash
adb -s 192.168.0.110:5555 forward tcp:38788 tcp:38788
curl http://127.0.0.1:38788/health
curl http://127.0.0.1:38788/enable-hotspot
```

Expected health response:

```json
{"ok":true,"app":"Bridge","mode":"local","port":38788}
```

## Removed

The old standalone `com.wififailover.app`, Cloudflare Worker heartbeat, Python
package, launchd daemon templates, APK build outputs, and Android project were
removed. Do not rebuild phone-side functionality here; add it to Bridge instead.
