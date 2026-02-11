# WiFi Failover Utility

Automatic failover from WiFi to Android hotspot. When your primary WiFi network loses internet connectivity, this utility automatically commands your Android phone to enable hotspot and connects your Mac to it—all without manual intervention.

```
┌─ Detect connectivity loss ─────────────┐
│  (macOS daemon polls WiFi every 30s)   │
│                                        │
├─ Post to Cloudflare Worker ───────────┤
│  (triggers hotspot command)            │
│                                        │
├─ Android app polls Worker ────────────┤
│  (native app, every 5 seconds)         │
│                                        │
├─ Phone enables hotspot ───────────────┤
│  (automatically via native app)        │
│                                        │
└─ Mac connects to hotspot ─────────────┘
   (using stored WiFi password)
```

## Requirements

- **macOS** (tested on Big Sur+)
- **Android phone** (Android 11+) with WiFi Failover App (native app - see `android-app/`)
- **Cloudflare account** (free tier is sufficient)
- **Python 3.8+**

## Quick Start

### 1. Install the package

```bash
# Using pip
pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git

# Or using uv
uv pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

### 2. Run setup wizard

```bash
wifi-failover setup
```

This interactive wizard will:
- Auto-detect available WiFi networks on your Mac
- Ask which networks to monitor
- Request your phone's hotspot SSID
- Prompt for Cloudflare Worker credentials
- Save hotspot password to Keychain
- Start the daemon automatically (optional)

### 3. Deploy Cloudflare Worker (if you don't have one)

See [CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md) for detailed instructions.

The Worker URL and secret will be used in the setup wizard.

### 4. Install Android App

```bash
# Build and install the native app
cd android-app
./gradlew installDebug

# Or transfer the APK and install via adb
adb install android-app/app/build/outputs/apk/debug/app-debug.apk
```

**Setup on phone:**
1. Open WiFi Failover app
2. Tap "Enable Device Admin" and grant permission
3. Enter Cloudflare Worker URL
4. Enter Worker Secret
5. Enter your hotspot SSID
6. Tap "Start Monitoring"
7. App will auto-start on device reboot

See [android-app/README.md](android-app/README.md) for detailed instructions.

### 5. Start monitoring

```bash
# Test it first (runs in foreground)
wifi-failover start

# For background daemon, see Installation section below
```

## Installation as Daemon

To run as a background service on Mac startup:

```bash
# Copy the launchd plist template
sudo cp launchd/com.wifi-failover.monitor.plist /Library/LaunchDaemons/

# Edit the plist with your username and paths
sudo nano /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Load it
sudo launchctl load /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Verify it's running
ps aux | grep wifi-failover

# Watch logs
tail -f /tmp/wifi-failover/monitor.log
```

## Configuration

Configuration is stored in `~/.config/wifi-failover/config.json`:

```json
{
  "monitored_networks": ["901 EXT5G", "MyWiFi"],
  "hotspot_ssid": "Dhruv's iPhone",
  "worker_url": "https://wifi-failover.youraccount.workers.dev",
  "worker_secret": "your-random-secret-here"
}
```

Edit this file directly or rerun `wifi-failover setup` to reconfigure.

## Commands

```bash
# Interactive setup (with option to start daemon)
wifi-failover setup

# Start monitoring in foreground (for testing)
wifi-failover start

# Start daemon in background
wifi-failover daemon

# Enable auto-start on login
wifi-failover enable-autostart

# Disable auto-start on login
wifi-failover disable-autostart

# Show current configuration and status
wifi-failover status
```

## How It Works

### macOS Daemon

- Runs continuously via launchd
- Checks WiFi network every 30 seconds
- Tests internet connectivity with ping to 8.8.8.8
- If 2+ consecutive failures: POSTs to Worker to enable hotspot
- Waits for hotspot to activate, then connects via `networksetup`
- If 3+ consecutive successes: disables hotspot command

### Cloudflare Worker

- Stores state in KV storage (10-min TTL)
- **POST** `/api/command/enable` - Trigger hotspot
- **POST** `/api/command/disable` - Cancel hotspot
- **GET** `/api/status` - Check command status
- **POST** `/api/acknowledge` - Confirm action completed

### Android (WiFi Failover App - Native)

- Runs as background WorkManager task every 5 seconds
- GETs `/api/status` to check if hotspot should be enabled
- Parses JSON response in Kotlin
- If `daemon_status = "online"`: keeps hotspot disabled
- If `daemon_status = "offline"`: enables hotspot automatically
- POSTs `/api/acknowledge` to confirm action
- Auto-starts on device boot via BootCompleteReceiver
- Respects lock/sleep detection from daemon (paused status)

## Troubleshooting

### Daemon not running

```bash
# Check status
ps aux | grep wifi-failover

# Check launchd status
sudo launchctl list | grep wifi-failover

# View logs
tail -f /tmp/wifi-failover/monitor.log
```

### Hotspot not triggering (Android App)

- Verify "Start Monitoring" button shows "Stop Monitoring" (toggle is ON)
- Check battery optimization: Settings → Battery → App not restricted
- Check permissions: Settings → Apps → WiFi Failover → Permissions
- Check Device Admin permission is enabled
- Check logs: `adb logcat | grep WiFiFailover`
- Verify Worker URL and Secret are correct in app settings
- Test Worker manually: `curl https://your-worker/health`
- On Android 12+, some versions limit hotspot control (see limitations)

### Can't connect to hotspot

- Verify password is stored in Keychain:
  ```bash
  security find-generic-password -wa "Dhruv's iPhone"
  ```
- Test manual connection first:
  ```bash
  networksetup -setairportnetwork en0 "Dhruv's iPhone" "your-password"
  ```

### Worker not responding

```bash
curl https://your-worker/health
curl https://your-worker/api/status
```

## File Structure

```
wifi-failover-utility/
├── wifi_failover/
│   ├── __init__.py
│   ├── cli.py                  # Interactive CLI
│   ├── config.py               # Configuration management
│   └── monitor.py              # Main daemon logic
├── android-app/                # Native Android app
│   └── app/src/main/kotlin/
├── launchd/
│   └── com.wifi-failover.monitor.plist
├── src/
│   └── index.js                # Cloudflare Worker code
├── setup.py
├── README.md
├── CLOUDFLARE_SETUP.md
└── LICENSE
```

## Security Notes

- Your Cloudflare Worker secret is stored in plaintext in the config file
- Store config file securely (it contains sensitive credentials)
- Consider rotating the secret periodically
- Don't commit config files to version control

## Limitations

- Requires phone to be nearby (hotspot range ~30ft)
- If both networks fail, system can't help
- Hotspot password must be stored in macOS Keychain
- Android device must have internet to enable hotspot
- Some Android 12+ devices restrict hotspot control via WifiManager
- App polling every 5 seconds (battery usage ~1-2% per hour)

## Development

```bash
# Clone and install in development mode
git clone https://github.com/yourusername/wifi-failover-utility.git
cd wifi-failover-utility
pip install -e .

# Run tests
pytest

# Build distribution
python setup.py sdist bdist_wheel
```

## License

MIT License - See LICENSE file for details

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Support

For issues, questions, or suggestions:
- Open a GitHub issue
- Check [Troubleshooting](#troubleshooting) section
- Review [CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md) for Worker deployment
