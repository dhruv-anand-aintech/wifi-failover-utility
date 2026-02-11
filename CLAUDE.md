# WiFi Failover Utility - Project Memory

This project provides automatic WiFi failover to Android hotspot using a Cloudflare Worker relay and native Android app.

## Project Overview

**Purpose:** Detect WiFi connectivity loss and automatically enable phone hotspot + connect Mac to it.

**Architecture:**
```
macOS Daemon (python)
    ↓ (HTTP POST every 2s)
Cloudflare Worker (KV storage)
    ↑ (HTTP GET every 5s)
Android App (native, WorkManager)
    ↓ (enables hotspot via Device Admin)
Mac reconnects via networksetup
```

**Target Users:** macOS users with Android phones who need reliable failover.

## Project Structure

```
wifi-failover-utility/
├── wifi_failover/
│   ├── __init__.py
│   ├── cli.py                 # Interactive setup wizard + daemon commands
│   ├── config.py              # Network detection, config storage
│   └── monitor.py             # Core daemon: connectivity checks, heartbeats
├── android-app/               # Native Android app
│   ├── app/src/main/
│   │   ├── kotlin/com/wififailover/app/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── src/
│   └── index.js               # Cloudflare Worker
├── launchd/
│   └── com.wifi-failover.monitor.plist  # macOS daemon auto-start
├── .github/workflows/
│   └── build-and-release.yml  # GitHub Actions APK builder
├── setup.py                   # Package metadata
├── wrangler.toml              # Cloudflare Worker config
├── README.md                  # Feature overview
├── COMPLETE_SETUP_GUIDE.md    # Full setup walkthrough
├── CLOUDFLARE_SETUP.md        # Worker deployment steps
├── LICENSE                    # MIT
└── CLAUDE.md                  # Project memory (this file)
```

## Key Features

1. **Interactive Setup Wizard** (`wifi_failover.cli`)
   - Auto-detects available WiFi networks on Mac
   - User selects which networks to monitor
   - Stores config in `~/.config/wifi-failover/config.json`
   - Generates Tasker instructions with user's exact Worker URL/secret

2. **Core Daemon** (`wifi_failover.monitor`)
   - Runs continuously, checks internet every 30 seconds
   - Tracks failure/recovery counts
   - POSTs to Cloudflare Worker on 2 consecutive failures
   - Connects to hotspot via `networksetup` with Keychain password
   - Auto-disables on 3 consecutive successes

3. **Configuration** (`wifi_failover.config`)
   - Detects available networks: `airport -s` command
   - Stores monitored networks list, hotspot SSID, Worker URL/secret
   - Simple JSON format for manual editing

4. **Native Android App** (`android-app/`)
   - Built with Kotlin + Jetpack Compose
   - WorkManager for 5-second polling
   - Device Admin for hotspot control
   - Auto-starts on boot
   - Shows daemon status (online/paused/offline)
   - Release builds via GitHub Actions

## How to Extend

### Add New Connectivity Check Methods

In `monitor.py`, modify `check_internet_connectivity()`:
```python
def check_internet_connectivity(self, host: str = "8.8.8.8", timeout: int = 5) -> bool:
    # Currently: ping-based
    # Could add: DNS lookup, HTTP GET, traceroute
```

### Add Alternative Failover Options

Extend to support other actions besides hotspot:
- VPN activation
- Mesh network switching
- Mobile network toggle

Add to `monitor.py`: new methods like `trigger_vpn_failover()`

### Support Multiple Hotspots

Current: Single hotspot SSID
Extend `config.py` to store list, try each one sequentially.

### Add Metrics/Logging

Integrate with:
- CloudWatch logs
- Datadog
- Custom log aggregation

Extend `monitor.py.logger` to send to external service.

### Enhance the Android App

Current implementation:
- WorkManager for periodic polling (5 second interval)
- Device Admin for hotspot control
- Jetpack Compose for UI

Possible enhancements:
- Faster polling via foreground service (requires notification)
- WiFi network auto-detection on Android
- Rich notifications with action buttons
- App shortcuts for quick start/stop
- Accessibility service for more reliable hotspot control

## Important Implementation Details

### Network Detection (macOS Only)
```bash
# This is how we detect networks:
/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport -s
/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport -I
```
Uses private Apple framework - not guaranteed stable across OS versions.

### Password Storage
- Uses macOS Keychain via `security` command
- Retrieves with: `security find-generic-password -wa "SSID"`
- Sets with: `security add-generic-password -a SSID -s SSID -w PASSWORD -U`

### Cloudflare Worker Pattern
- State stored in KV with 10-minute TTL
- Three endpoints: `/api/command/enable`, `/api/command/disable`, `/api/status`
- Mac POSTs commands, Android GETs status
- Secret validation on both sides

### Android App Integration - Offline Detection Architecture

⚠️ **IMPORTANT**: The Android app (NOT the Worker) is responsible for detecting daemon offline state.

**Why**: When daemon loses internet, it CAN'T send heartbeats to Worker. So the daemon cannot explicitly tell the Worker it's offline - that's a logical impossibility.

**How it works**:
1. macOS daemon sends heartbeats every 2 seconds (when online)
2. Cloudflare Worker stores `daemon_last_heartbeat` timestamp (just stores it, no timeout logic needed)
3. Android app polls Worker every 5 seconds, gets `time_since_heartbeat`
4. **Android app checks: if `time_since_heartbeat > 12 seconds` → daemon is offline** (gives 6 heartbeat cycles)
5. If offline for 2 consecutive checks, enable hotspot

**Current Implementation** (`WiFiFailoverWorker.kt`):
- Checks `status.daemon_status` which is Worker-computed
- **ISSUE**: Worker doesn't have timeout logic, so it keeps reporting "online" after daemon goes offline
- **FIX**: Android app should check `time_since_heartbeat > 12000ms` directly, not rely on Worker's `daemon_status`

**Response Fields Used**:
- `daemon_last_heartbeat` - Unix timestamp (ms) of last heartbeat received
- `time_since_heartbeat` - Milliseconds since last heartbeat (pre-computed by Worker for convenience)
- `daemon_status` - Currently set by Worker (should be set by Android app logic instead)

**Polling**: WorkManager schedules task every 5 seconds
**Device Admin**: Uses Device Admin for reliable hotspot control

## Configuration Schema

**File:** `~/.config/wifi-failover/config.json`

```json
{
  "monitored_networks": ["901 EXT5G", "MyWiFi"],
  "hotspot_ssid": "Dhruv's iPhone",
  "worker_url": "https://wifi-failover.xxx.workers.dev",
  "worker_secret": "random-secret-string"
}
```

## Common Commands

```bash
# Setup wizard (interactive, auto-starts daemon)
wifi-failover setup

# Run daemon in foreground (for testing)
wifi-failover start

# Run daemon in background
wifi-failover daemon

# Enable auto-start on login
wifi-failover enable-autostart

# Disable auto-start on login
wifi-failover disable-autostart

# Show config and recent logs
wifi-failover status

# Watch daemon logs
tail -f ~/.wifi-failover-logs/monitor.log

# Check if running as daemon
ps aux | grep wifi-failover
launchctl list | grep wifi-failover

# Manually trigger failover (for testing)
curl -X POST https://your-worker/api/command/enable \
  -H "Content-Type: application/json" \
  -d '{"secret": "your-secret"}'
```

## Development Setup

```bash
# Install in editable mode
pip install -e .

# Run tests (if added)
pytest

# Test CLI locally
wifi-failover setup
wifi-failover start

# View logs
tail -f /tmp/wifi-failover/monitor.log
```

## Testing Strategy

### Manual Testing (Current)
1. Trigger failover: `curl` to Worker with enable command
2. Run Tasker task: press ▶ in Tasker app
3. Watch phone hotspot turn on
4. Check Mac connects: `networksetup -listallhardwareports`

### Unit Tests (To Add)
```python
# Test network detection
def test_get_available_networks():
    networks = get_available_networks()
    assert isinstance(networks, list)

# Test config storage
def test_config_save_load():
    config = Config()
    config.set_monitored_networks(["TestNet"])
    assert config.get_monitored_networks() == ["TestNet"]

# Test monitor connectivity check
def test_check_internet_connectivity():
    monitor = WiFiFailoverMonitor(...)
    result = monitor.check_internet_connectivity()
    assert isinstance(result, bool)
```

### Integration Testing (To Add)
- Deploy test Worker
- Run daemon with test config
- Simulate network failure
- Verify daemon triggers failover correctly

## Security Considerations

⚠️ **Config file stores secrets in plaintext**
- File: `~/.config/wifi-failover/config.json`
- Contains: Worker secret, hotspot password (if saved)
- Permissions: User-readable only (mode 0600)
- Never commit to git

**Recommendations:**
- Use different Worker secret than other projects
- Rotate secret monthly
- Encrypt config file if on shared system
- Don't backup Android app with secrets enabled
- Use VPN if Worker is accessed over untrusted networks
- Disable auto-start if loaning phone to others

## Dependencies

**Python packages:**
- `requests>=2.28.0` - HTTP calls to Worker
- `psutil>=5.9.0` - Process monitoring (currently unused but available)

**macOS tools (used via subprocess):**
- `airport` - WiFi network detection
- `networksetup` - WiFi connection
- `security` - Keychain access
- `ping` - Connectivity check
- `launchctl` - Daemon management

**Cloudflare:**
- Workers KV storage
- Custom domain or workers.dev subdomain

**Android:**
- Tasker app ($3)
- Device Admin permission

## Troubleshooting Guide for Claude

**User can't detect networks:**
- Check `airport` command works: `/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport -s`
- May fail on newer macOS versions (use private API)

**Hotspot won't connect:**
- Verify password in Keychain: `security find-generic-password -wa "SSID"`
- Test manual connection: `networksetup -setairportnetwork en0 "SSID" "password"`

**Daemon won't start:**
- Check logs: `/tmp/wifi-failover/monitor.log` and `/tmp/wifi-failover/stderr.log`
- Verify config file exists: `~/.config/wifi-failover/config.json`
- Test in foreground: `wifi-failover start`

**Worker not responding:**
- Test directly: `curl https://worker-url/health`
- Check KV namespace is configured
- Verify secret matches

**Android app not monitoring:**
- Verify "Start Monitoring" button is enabled (shows "Stop Monitoring")
- Check Device Admin is enabled: Settings → Apps → Special app access → Device admin apps → WiFi Failover
- Check battery optimization: Settings → Battery → Battery saver → App restrictions
- Check app is not disabled: Settings → Apps → WiFi Failover → Enabled
- Check permissions: Settings → Apps → WiFi Failover → Permissions

## Future Improvements

1. **Add unit + integration tests** - Currently manual only
2. **PyPI package improvements** - Currently git install, could add auto-update checks
3. **Android app enhancements**:
   - Faster polling via ForegroundService (with notification)
   - Rich notifications with action buttons
   - App shortcuts for quick toggle
   - WiFi network auto-detection on Android
4. **macOS enhancements**:
   - GUI dashboard for status monitoring
   - One-click manual failover trigger
   - Network auto-connect on recovery
5. **Multi-hotspot support** - Try multiple devices sequentially
6. **Metrics collection** - Track failovers, uptime, failures
7. **Configuration UI** - Web dashboard instead of CLI wizard
8. **Cross-platform** - Linux, Windows support (need network APIs)

## Repository

**GitHub:** https://github.com/dhruv-anand-aintech/wifi-failover-utility

**Installation:**
```bash
pip install wifi-failover-utility
uv pip install wifi-failover-utility
```

## Documentation

- **README.md** - Feature overview, limitations, support
- **SETUP_INSTRUCTIONS.md** - Quick 15-minute setup
- **COMPLETE_SETUP_GUIDE.md** - Detailed step-by-step with troubleshooting
- **CLOUDFLARE_SETUP.md** - Worker deployment guide
- **This file (CLAUDE.md)** - Architecture, development, extending
