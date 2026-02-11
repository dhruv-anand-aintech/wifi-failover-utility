# WiFi Failover Utility - Project Memory

This project provides automatic WiFi failover to Android hotspot using a Cloudflare Worker relay and Tasker automation.

## Project Overview

**Purpose:** Detect WiFi connectivity loss and automatically enable phone hotspot + connect Mac to it.

**Architecture:**
```
macOS Daemon (python)
    ↓ (HTTP POST)
Cloudflare Worker (KV storage)
    ↑ (HTTP GET, every 2 min)
Android Tasker
    ↓ (enables hotspot)
Mac reconnects via networksetup
```

**Target Users:** macOS users with Android phones who need reliable failover.

## Project Structure

```
wifi-failover-utility/
├── wifi_failover/
│   ├── __init__.py
│   ├── cli.py                 # Main: Interactive setup wizard + commands
│   ├── config.py              # Auto-detect networks, load/save config
│   ├── monitor.py             # Core daemon: connectivity checks, hotspot commands
│   └── tasker_instructions.py # Generate Tasker setup guide
├── launchd/
│   └── com.wifi-failover.monitor.plist  # macOS daemon config
├── setup.py                   # Package metadata
├── README.md                  # Feature overview
├── SETUP_INSTRUCTIONS.md      # Quick start guide (15 min)
├── COMPLETE_SETUP_GUIDE.md    # Detailed walkthrough with troubleshooting
├── CLOUDFLARE_SETUP.md        # Cloudflare Worker deployment steps
├── LICENSE                    # MIT
└── .gitignore
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

4. **Tasker Guide Generator** (`wifi_failover.tasker_instructions`)
   - Generates step-by-step instructions
   - Embeds user's Worker URL and secret
   - Covers: Device Admin, task creation, profile setup, testing

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

### Support Android Methods Beyond Tasker

- Termux + SSH for direct commands
- scrcpy + adb automation
- Android Debug Bridge (adb)

Create parallel implementation in new file: `android_tasker.py`, `android_adb.py`, etc.

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

### Tasker Integration
- Polls every 1-2 minutes (configurable)
- Parses JSON response
- Executes actions conditionally (IF hotspot_enabled = true)
- Uses HTTP POST for acknowledgment

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
# Setup (interactive, auto-detects networks)
wifi-failover setup

# Run daemon in foreground (for testing)
wifi-failover start

# Show config and recent logs
wifi-failover status

# Display Tasker setup instructions
wifi-failover tasker-guide

# Watch daemon logs
tail -f /tmp/wifi-failover/monitor.log
tail -f /tmp/wifi-failover/stderr.log

# Check if running as daemon
ps aux | grep wifi-failover
sudo launchctl list | grep wifi-failover

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
- Don't share Tasker backups publicly
- Use VPN if Worker is accessed over untrusted networks

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

**Tasker not running:**
- Check Device Admin enabled in Tasker Preferences
- Verify profile is active (Profiles tab)
- Check battery optimization isn't blocking Tasker
- Test manually: tap ▶ button

## Future Improvements

1. **Add unit + integration tests** - Currently manual only
2. **Support PyPI publishing** - Currently git-only install
3. **Add GUI dashboard** - Show daemon status, trigger failover manually
4. **Multi-hotspot support** - Try multiple devices sequentially
5. **Android alternatives** - ADB, Termux support beyond Tasker
6. **Metrics collection** - Track failovers, uptime, failures
7. **Configuration UI** - Web dashboard instead of CLI wizard
8. **Cross-platform** - Linux, Windows support (need network APIs)

## Repository

**GitHub:** https://github.com/dhruv-anand-aintech/wifi-failover-utility

**Installation:**
```bash
pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
uv pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

## Documentation

- **README.md** - Feature overview, limitations, support
- **SETUP_INSTRUCTIONS.md** - Quick 15-minute setup
- **COMPLETE_SETUP_GUIDE.md** - Detailed step-by-step with troubleshooting
- **CLOUDFLARE_SETUP.md** - Worker deployment guide
- **This file (CLAUDE.md)** - Architecture, development, extending
