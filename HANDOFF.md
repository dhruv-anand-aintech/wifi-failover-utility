# Bridge Failover Handoff

## Current State

The standalone WiFi Failover Android app is retired. Phone-side behavior now
belongs to Bridge, installed as:

```text
tech.ainorthstar.usagepush
```

The deleted standalone package was:

```text
com.wififailover.app
```

This repo now only contains the Mac-side local monitor:

```bash
scripts/local-failover-monitor.sh
```

The user LaunchAgent for the moved repo is:

```text
~/Library/LaunchAgents/tech.ainorthstar.wifi-failover-monitor.plist
```

## Active Path

Bridge hosts a foreground local HTTP control server on the phone at port
`38788`:

- `GET /health`
- `GET /enable-hotspot`
- `GET /enable-tailscale`
- `GET /cancel`

The Mac helper connects known host:port ADB serials, forwards that port over
ADB, requests `enable_tailscale` through the configured updates Worker wake
webhook when Bridge `/health` is unavailable, refreshes Bridge `/health` while
internet is still healthy, triggers `/enable-hotspot` after repeated ping
failures, then switches macOS Wi-Fi to the saved SSID `Dhruv's Phone`. There is
no Cloudflare Worker, Android build, Python daemon, or launchd installer in this
repo now.

This flow only works if the Mac can still reach Bridge locally after internet
loss. It covers upstream/WAN failure while LAN/ADB remains available. It does
not cover total Wi-Fi/LAN loss unless the phone enables hotspot autonomously or
ADB is available over USB. The Mac hotspot switch still runs when Bridge trigger
fails, so a phone-side autonomous hotspot can recover the Mac.

Cloudflare can be used as an out-of-band command mailbox, but only from a
network that still works. Bridge already polls:

```text
https://updates.ainorthstar.tech/phone-wake/poll?phone_id=<phone_id>
```

and accepts `enable_hotspot` commands queued through:

```text
POST https://updates.ainorthstar.tech/phone-wake/request
```

The Worker-side stale Mac heartbeat detector is implemented in the deployed
updates Worker. The Mac monitor POSTs `/wifi-failover/heartbeat`; when Bridge
polls `/phone-wake/poll?phone_id=<phone_id>` and the heartbeat is stale or
reports `internet_ok=false`, the Worker returns a synthetic `enable_hotspot`
command and records Bridge ACKs at `/phone-wake/ack`. A Mac-side Worker request
after total local network loss is still too late; the heartbeat and Bridge
polling must be pre-armed while healthy.

The local monitor now also uses the wake queue for `enable_tailscale` when
Bridge is not reachable, sourced from parent repo `.env` and
`.main-phone-remote.env`. It also derives the heartbeat URL from the configured
wake request URL unless `WIFI_FAILOVER_HEARTBEAT_URL` is set.

Emergency killswitch is centralized in the updates Worker:

```bash
scripts/wifi-failover-killswitch.sh on "overtriggering"
```

Run `scripts/wifi-failover-killswitch.sh off` to re-arm failover, and
`scripts/wifi-failover-killswitch.sh status` to check the state. The Worker
suppresses queued and synthetic phone wake commands while enabled, and the Mac
monitor skips local hotspot/Tailscale wake actions after it observes the
heartbeat response.

## Verified Before Cleanup

Main phone:

```text
192.168.0.110:5555 product:CPH2573IN model:CPH2573
```

Bridge health endpoint through ADB forwarding:

```json
{"ok":true,"app":"Bridge","mode":"local","port":38788}
```

Bridge hotspot request path:

```bash
curl http://127.0.0.1:38788/enable-hotspot
networksetup -setairportnetwork en0 "Dhruv's Phone"
```

Observed logs showed Bridge opened hotspot settings and clicked the hotspot
toggle. Android tethering state then showed SoftAP active.

## Manual Root Cleanup

An old system LaunchDaemon may still exist from earlier experiments. Codex
should not run sudo commands. If it exists, D should remove it manually:

```bash
sudo launchctl bootout system /Library/LaunchDaemons/com.dhruvanand.wifi-failover.plist
sudo rm -f /Library/LaunchDaemons/com.dhruvanand.wifi-failover.plist
```

## Resume

```bash
adb devices -l
export WIFI_FAILOVER_PHONE_SERIAL=192.168.0.110:5555
scripts/local-failover-monitor.sh
```
