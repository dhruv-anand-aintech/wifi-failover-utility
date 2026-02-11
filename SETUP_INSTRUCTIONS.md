# WiFi Failover Utility - Quick Start Guide

## Overview

Automatically switch your Mac from failing WiFi to your Android phone's hotspot. The daemon detects connectivity loss and the Android app enables the hotspot with automatic failover.

**Setup time: 15-20 minutes**

## What You Need

- **macOS** (Big Sur or later)
- **Android phone** (API 30+) with the WiFi Failover app
- **Cloudflare account** (free tier works)
- **Python 3.8+** on Mac
- **Internet connection** for initial setup

## Quick Setup (3 steps)

### Step 1: Deploy Cloudflare Worker

```bash
# Install wrangler
npm install -g wrangler
# or: brew install wrangler

# Authenticate
wrangler login

# Create KV namespace
wrangler kv:namespace create "WIFI_FAILOVER"
wrangler kv:namespace create "WIFI_FAILOVER" --preview
```

Save the namespace IDs from the output.

Create `wrangler.toml`:
```toml
name = "wifi-failover"
main = "src/index.js"
compatibility_date = "2024-01-01"

kv_namespaces = [
  { binding = "WIFI_FAILOVER", id = "YOUR_ID_HERE", preview_id = "YOUR_PREVIEW_ID_HERE" }
]

[triggers]
crons = ["0 */10 * * * *"]
```

Copy the Worker code from `src/index.js` to your project, then deploy:
```bash
wrangler deploy
```

**Save your Worker URL** (looks like: `https://wifi-failover-xxx.workers.dev`)

### Step 2: Install & Configure Mac Daemon

```bash
# Install via pip
pip install -e .

# Run interactive setup
wifi-failover setup

# Follow prompts to:
# 1. Select networks to monitor
# 2. Enter Worker URL
# 3. Enter Worker secret (from setup)
# 4. Enter phone hotspot SSID

# Enable auto-start on login
wifi-failover enable-autostart

# Start daemon
wifi-failover daemon
```

### Step 3: Install & Configure Android App

1. **Build and install the app:**
   ```bash
   cd android-app
   ./gradlew installDebug
   ```

2. **Open the WiFi Failover app on your phone**

3. **Enable required permissions:**
   - Tap "Enable Device Admin" button
   - Grant the requested permissions

4. **Configure app settings:**
   - Enter Worker URL
   - Enter Worker Secret
   - Enter Phone Hotspot SSID
   - Set polling interval (default: 10 seconds)
   - Tap "Save Configuration"

5. **Start monitoring:**
   - Tap "Start Monitoring"
   - The app will poll daemon status every 10 seconds in the background

## How It Works

```
Mac Daemon                     Cloudflare Worker              Android Phone
(heartbeat every 5s)           (relay & state storage)        (WorkManager polling)
    ↓                               ↓                              ↓
  Send heartbeat ────────→ Store timestamp ←──────────── Poll every 10s
                                                           Daemon online? ✓

[Mac loses internet]
  ↓
  No heartbeat sent
                                                           Daemon online? ✗
                                                           Daemon online? ✗ (count: 2)
                                                           ↓
                                                           Enable hotspot
                                                           ↓
  Hotspot enabled (can connect)
  Daemon connects to hotspot
  Heartbeats resume
                                                           Daemon online? ✓
                                                           Hotspot no longer needed
```

## Commands

```bash
# Start daemon in background (auto-kills existing)
wifi-failover daemon

# Start in foreground (for debugging)
wifi-failover start

# Show status & configuration
wifi-failover status

# Enable auto-start on login
wifi-failover enable-autostart

# Disable auto-start
wifi-failover disable-autostart

# View logs
tail -f ~/.wifi-failover-logs/monitor.log
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| App won't start | Ensure Android 11+ and check permissions |
| Hotspot not enabling | Check Device Admin and Accessibility Service enabled |
| Daemon won't start | Run `wifi-failover setup` to configure |
| Lost connection | Check Worker URL is correct and accessible |
| Logs not appearing | Check `~/.wifi-failover-logs/monitor.log` |

## Architecture

- **macOS Daemon:** Monitors internet connectivity, sends heartbeats every 5 seconds
- **Cloudflare Worker:** Stores heartbeat state, provides status endpoint
- **Android App:** WorkManager polls daemon status, enables hotspot when offline >15 seconds
- **Accessibility Service:** Clicks hotspot toggle in Settings automatically

## See Also

- **COMPLETE_SETUP_GUIDE.md** - Detailed step-by-step walkthrough
- **CLOUDFLARE_SETUP.md** - Detailed Worker deployment
- **README.md** - Feature overview & limitations
