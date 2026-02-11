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
│  (Automate or Tasker, every 1-2 min)  │
│                                        │
├─ Phone enables hotspot ───────────────┤
│  (automatically via automation)        │
│                                        │
└─ Mac connects to hotspot ─────────────┘
   (using stored WiFi password)
```

## Requirements

- **macOS** (tested on Big Sur+)
- **Android phone** (Android 11+) with one of:
  - **WiFi Failover App** (native, recommended - see `android-app/`)
  - **Automate** (free, visual blocks)
  - **Tasker** (~$3, more powerful)
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
- Generate automation app setup instructions (Automate or Tasker)

### 3. Deploy Cloudflare Worker (if you don't have one)

See [CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md) for detailed instructions.

The Worker URL and secret will be used in the setup wizard.

### 4. Configure Android (Choose One)

**Option A: Native WiFi Failover App (Recommended):**
```bash
# Build and install the native app
cd android-app
./gradlew installDebug
```
- Open app on phone
- Enter: Worker URL, Secret, Hotspot SSID
- Tap "Start Monitoring"
- App automatically starts on boot

See [android-app/README.md](android-app/README.md) for detailed instructions.

**Option B: Automate (Visual Blocks):**
```bash
open ~/Desktop/AUTOMATE_SETUP.txt
```
- Install from Google Play Store
- Follow the 5-step visual setup
- No complex scripting needed

**Option C: Tasker (Advanced):**
```bash
open ~/Desktop/TASKER_SETUP.txt
```
- Install from Google Play Store (~$3)
- Enable Device Admin
- Follow the 7-step task setup

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
# Interactive setup
wifi-failover setup

# Start monitoring (foreground)
wifi-failover start

# Show current configuration and status
wifi-failover status

# Display Tasker setup instructions
wifi-failover tasker-guide
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

### Android (WiFi Failover App, Automate, or Tasker)

**WiFi Failover App (Native - Recommended):**
- Runs as background WorkManager task every 1-2 minutes
- GETs `/api/status` to check if hotspot should be enabled
- Parses JSON response in Kotlin
- If `hotspot_enabled = true`: enables hotspot via WifiManager
- POSTs `/api/acknowledge` to confirm
- Auto-starts on device boot

**Automate (Visual blocks):**
- Runs flow every 1-2 minutes (configurable)
- GETs `/api/status` to check if hotspot should be enabled
- Parses JSON response using visual blocks
- If `hotspot_enabled = true`: enables hotspot
- POSTs `/api/acknowledge` to confirm

**Tasker (Script-based):**
- Runs task every 1-2 minutes (configurable)
- GETs `/api/status` to check if hotspot should be enabled
- Parses JSON with JavaScript
- If `hotspot_enabled = true`: enables hotspot
- POSTs `/api/acknowledge` to confirm

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

### Hotspot not triggering

**If using WiFi Failover App:**
- Verify "Start Monitoring" button shows "Stop Monitoring" (toggle is ON)
- Check battery optimization: Settings → Battery → App not restricted
- Check permissions: Settings → Apps → WiFi Failover → Permissions
- Check logs: `adb logcat | grep WiFiFailover`
- On Android 12+, some versions limit hotspot control (see limitations)

**If using Automate:**
- Verify flow toggle is enabled (blue) in flows list
- Check flow in battery optimization settings
- Tap flow → ▶ Play to manually test
- Check flow history for errors

**If using Tasker:**
- Verify Device Admin is enabled
- Check profile is active
- Run task manually (▶ button)
- Check battery optimization isn't blocking Tasker

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
│   ├── monitor.py              # Main daemon logic
│   └── tasker_instructions.py  # Tasker setup guide generator
├── launchd/
│   └── com.wifi-failover.monitor.plist
├── cloudflare/
│   └── worker.js               # Cloudflare Worker code
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
- Tasker task runs every 1-2 min (affects failover response time)
- If both networks fail, system can't help
- Hotspot password must be stored in macOS Keychain
- Android device must have internet to enable hotspot

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
