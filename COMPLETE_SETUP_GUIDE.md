# Complete WiFi Failover Setup Guide

This guide walks you through setting up the complete WiFi failover system from scratch.

## Overview

You'll need to:
1. **Deploy a Cloudflare Worker** - Acts as the relay between Mac and Android
2. **Install the utility on Mac** - Run the interactive setup
3. **Install the Android App** - Native app automatically monitors daemon
4. **Test the system** - Verify everything works together

**Total time: ~20-30 minutes**

---

## Part 1: Deploy Cloudflare Worker

### Step 1.1: Install Wrangler

```bash
# Install via npm (requires Node.js)
npm install -g wrangler

# Or install via Homebrew on macOS
brew install wrangler
```

Verify installation:
```bash
wrangler --version
```

### Step 1.2: Authenticate

```bash
wrangler login
```

This opens your browser to authorize Cloudflare access.

### Step 1.3: Clone and Deploy the Worker

```bash
# Clone the repository
git clone https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
cd wifi-failover-utility

# Create KV namespace
wrangler kv:namespace create "WIFI_FAILOVER"
wrangler kv:namespace create "WIFI_FAILOVER" --preview

# Update wrangler.toml with the namespace IDs (they'll be printed above)
# Then deploy
wrangler deploy
```

The output will show your Worker URL, e.g.:
```
✨ Deployed to https://wifi-failover-xxxxx.workers.dev
```

**Save this URL** - you'll need it for the Mac setup.

### Step 1.4: Verify Worker is Running

```bash
curl https://your-worker-url/health
# Should return: OK
```

---

## Part 2: Install and Configure macOS Daemon

### Step 2.1: Install the Package

```bash
# Using pip
pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git

# Or using uv
uv pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

### Step 2.2: Run Setup Wizard

```bash
wifi-failover setup
```

This interactive wizard will ask for:
1. **Networks to monitor** - Auto-detects available WiFi networks
2. **Phone hotspot SSID** - The name that appears in WiFi settings
3. **Worker URL** - The URL from Part 1 (https://wifi-failover-xxxxx.workers.dev)
4. **Worker Secret** - Use a random strong string (suggestion: run `openssl rand -base64 32`)
5. **Hotspot password** - Stored securely in macOS Keychain

At the end, the wizard will ask if you want to start the daemon now.

### Step 2.3: Start the Daemon

If you didn't start it in the wizard:

```bash
# Run in background
wifi-failover daemon

# Or enable auto-start on login
wifi-failover enable-autostart
```

Check the logs:
```bash
tail -f ~/.wifi-failover-logs/monitor.log
```

You should see:
```
Starting WiFi failover monitor
Networks to monitor: ['Your WiFi']
Hotspot SSID: Your Phone
```

---

## Part 3: Install Android App

### Step 3.1: Get the APK

**Option A: Download Latest Release**
- Go to: https://github.com/dhruv-anand-aintech/wifi-failover-utility/releases
- Download the latest `wifi-failover-*.apk`
- Transfer to your phone via USB/email/cloud

**Option B: Build Yourself**
```bash
cd android-app
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Step 3.2: Install APK on Phone

**Method 1: USB Installation (Recommended)**
```bash
adb install android-app/app/build/outputs/apk/debug/app-debug.apk
```

**Method 2: Manual Installation**
1. Copy APK file to phone via cloud storage/email
2. Open file manager on phone
3. Tap the APK file
4. Tap "Install"
5. Grant requested permissions

### Step 3.3: Configure App on Phone

1. Open **WiFi Failover** app
2. You should see:
   - ❌ Device Admin NOT enabled
   - ✓ Auto-start enabled
3. Tap **"Enable Device Admin"**
   - Go to Settings → Apps → Special app access → Device admin apps
   - Toggle **WiFi Failover** ON
4. Enter your **Worker URL** (from Part 1)
5. Enter your **Worker Secret** (from Part 2)
6. Enter your **Hotspot SSID** (from Part 2)
7. Tap **"Start Monitoring"**

The button should now show "Stop Monitoring" and the status should be green.

### Step 3.4: Enable Auto-Start on Boot

The app should auto-start, but verify in Settings:
- Settings → Apps → WiFi Failover → Battery
- Make sure battery optimization is **NOT** enabled for this app

---

## Part 4: Test the System

### Test 1: Manual Failover Trigger

```bash
# On Mac, manually trigger failover (for testing)
curl -X POST https://your-worker-url/api/command/enable \
  -H "Content-Type: application/json" \
  -d '{"secret": "your-secret-here"}'

# Check status
curl https://your-worker-url/api/status
```

Check the Android app - it should show the daemon is "OFFLINE" and hotspot should enable.

### Test 2: Lock Detection

```bash
# Watch the daemon logs
tail -f ~/.wifi-failover-logs/monitor.log

# Lock your Mac: Cmd+Ctrl+Q
# You should see within 2 seconds:
# 🔒 Screen LOCKED - sending 'paused' status

# Unlock your Mac
# You should see:
# 🔓 Screen UNLOCKED - sending 'active' status
```

Check the Android app - when screen is locked, it shows "PAUSED" status.

### Test 3: Real Failover

1. Make sure daemon and app are running
2. On your phone, turn off WiFi (airplane mode or disable WiFi)
3. Watch the daemon logs - it should detect loss after ~60 seconds
4. The app should receive the enable command
5. Phone hotspot should turn on automatically
6. Mac should connect to hotspot
7. Turn WiFi back on the phone
8. After 3 successful pings, Mac disconnects from hotspot

---

## Troubleshooting

### "Worker URL is invalid"
- Make sure Worker URL starts with `https://`
- Check that Worker deployed successfully: `curl https://your-url/health`

### "Configuration incomplete"
- Make sure you selected at least one network
- Hotspot SSID cannot be empty
- Worker URL and secret must be provided

### Daemon not starting
```bash
# Check if already running
ps aux | grep wifi-failover

# View logs
cat ~/.wifi-failover-logs/monitor.log
cat ~/.wifi-failover-logs/daemon.log

# Try running in foreground to see errors
wifi-failover start
```

### Android app not receiving commands
- Verify "Start Monitoring" is enabled (button shows "Stop Monitoring")
- Check Device Admin is enabled: Settings → Apps → Special app access → Device admin
- Verify Worker URL and Secret are correct
- Check phone has internet connection
- Check app isn't in battery optimization

### Hotspot won't auto-enable
- Verify password is stored in Keychain (mac side):
  ```bash
  security find-generic-password -wa "Hotspot Name"
  ```
- On Android 12+, manual WiFi control may be restricted - enable Developer Options and check WiFi restrictions
- Verify hotspot password is correct on phone

### Can't connect to hotspot
Test manual connection first:
```bash
networksetup -setairportnetwork en0 "Hotspot Name" "Password"
```

If that works, the issue is the daemon. If it fails, the issue is macOS WiFi settings.

### Check Daemon Status
```bash
# Quick status
wifi-failover status

# Full logs
tail -50 ~/.wifi-failover-logs/monitor.log

# Check launchd (if installed as daemon)
launchctl list | grep wifi-failover
```

---

## Configuration Reference

Configuration is stored in: `~/.config/wifi-failover/config.json`

```json
{
  "monitored_networks": ["WiFi1", "WiFi2"],
  "hotspot_ssid": "My iPhone",
  "worker_url": "https://wifi-failover-xxxxx.workers.dev",
  "worker_secret": "your-secret-key-here"
}
```

To reconfigure:
```bash
wifi-failover setup
```

---

## Next Steps

- ✅ Daemon is running and sending heartbeats
- ✅ Android app is monitoring and polling
- ✅ System is ready for automatic failover

For issues or questions:
- Check the troubleshooting section above
- Review [README.md](README.md) for more details
- Open a GitHub issue: https://github.com/dhruv-anand-aintech/wifi-failover-utility/issues
