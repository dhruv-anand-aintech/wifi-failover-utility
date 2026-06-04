# WiFi Failover Local-First Handoff

## Current State

Cloudflare heartbeat failover was turned off because it is a bad control plane for network-loss recovery. The deployed `wifi-failover` Worker was deleted and the user LaunchAgent `com.wifi-failover.monitor` was unloaded, disabled, and removed from `~/Library/LaunchAgents`.

One root LaunchDaemon still needs manual sudo cleanup:

```bash
sudo launchctl bootout system /Library/LaunchDaemons/com.dhruvanand.wifi-failover.plist
sudo rm -f /Library/LaunchDaemons/com.dhruvanand.wifi-failover.plist
```

Do not run those commands from Codex; ask the user to run them.

## Implemented Local-First Path

The Android app now starts a foreground `LocalControlService` on launch. It hosts a local NanoHTTPD server on port `38788` with:

- `GET /health`
- `GET /enable-hotspot`
- `GET /cancel`

The active path no longer depends on Cloudflare Worker status or heartbeat polling. `Preferences.isConfigured()` only requires the hotspot SSID, and boot restore starts `LocalControlService` instead of WorkManager polling.

The Mac-side helper is:

```bash
scripts/local-failover-monitor.sh
```

It forwards `tcp:38788` over ADB and triggers `/enable-hotspot` after repeated ping failures.

## Verified

Device seen before ADB restart:

```text
adb-547df14d-6g2usO._adb-tls-connect._tcp device product:CPH2573IN model:CPH2573 device:OP595DL1
```

Builds passed:

```bash
cd android-app
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Release APK installed successfully:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

App launched and health endpoint worked through ADB forwarding:

```bash
adb shell am start -n com.wififailover.app/.MainActivity
adb forward tcp:38788 tcp:38788
curl http://127.0.0.1:38788/health
```

Observed response:

```json
{"ok":true,"mode":"local","port":38788}
```

After enabling the app Accessibility service via ADB, `/enable-hotspot` accepted the request:

```json
{"ok":true,"action":"enable-hotspot"}
```

## Known Blocker

The phone was locked when hotspot settings opened. Accessibility saw `com.android.systemui` instead of the OPPO/ColorOS settings UI, so it could not click the hotspot toggle. This confirms the main remaining constraint: the current last-mile automation needs the phone unlocked and the Settings UI visible.

After this locked-screen attempt, wireless ADB became unstable. Restarting ADB cleared the connection, and `adb mdns services` did not rediscover the paired phone at that moment.

## Resume Steps

1. Reconnect the paired phone over wireless ADB, or pair again from Android Developer Options.
2. Launch the app:

```bash
adb shell am start -n com.wififailover.app/.MainActivity
adb forward tcp:38788 tcp:38788
curl http://127.0.0.1:38788/health
```

3. Keep the phone unlocked and run:

```bash
curl http://127.0.0.1:38788/enable-hotspot
adb logcat -d -s LocalControlService HotspotService HotspotA11yService | tail -n 120
```

4. If the OPPO settings page opens but the toggle is not clicked, inspect the live UI:

```bash
adb shell uiautomator dump /sdcard/window.xml
adb exec-out cat /sdcard/window.xml
```

Then update `HotspotAccessibilityService` selectors for the actual ColorOS node text/resource IDs.

## Better Next Architecture

Keep the local service and notification actions. Treat Accessibility as best-effort only. The more reliable path is one of:

- A visible foreground notification action the user can tap when the phone is locked.
- USB/wireless ADB command path for trusted development use.
- A rooted/Shizuku/system-permission path if fully automatic hotspot toggling is mandatory.

Cloudflare can remain useful for diagnostics or history, but not for failover decisions.
