# Complete WiFi Failover Setup Guide

This guide walks you through the complete setup process, from scratch to fully operational system.

## Overview

You'll need to:
1. **Deploy a Cloudflare Worker** - Acts as the relay between Mac and Android
2. **Install the utility on Mac** - Run the interactive setup
3. **Configure Android Automation** - Set up the phone to listen for commands
   - **Automate** (recommended - easier, visual)
   - **Tasker** (alternative - more powerful)
4. **Test the system** - Verify everything works together

Total time: **30-45 minutes**

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

### Step 1.3: Create KV Namespaces

```bash
# Production namespace
wrangler kv:namespace create "WIFI_FAILOVER"

# Preview namespace (for testing)
wrangler kv:namespace create "WIFI_FAILOVER" --preview
```

**Save the output** - you'll need the namespace IDs.

Example output:
```
✨ Success!
Add the following to your wrangler.toml:

kv_namespaces = [
 { binding = "WIFI_FAILOVER", id = "abc123...", preview_id = "def456..." }
]
```

### Step 1.4: Create Project Files

Create these files in your Cloudflare Worker project directory:

**File: `wrangler.toml`**
```toml
name = "wifi-failover"
main = "src/index.js"
compatibility_date = "2024-01-01"

kv_namespaces = [
  { binding = "WIFI_FAILOVER", id = "PASTE_YOUR_ID_HERE", preview_id = "PASTE_PREVIEW_ID_HERE" }
]

[triggers]
crons = ["0 */10 * * * *"]
```

**Replace `PASTE_YOUR_ID_HERE` and `PASTE_PREVIEW_ID_HERE` with values from Step 1.3.**

**File: `src/index.js`**

Copy the Worker code from `CLOUDFLARE_SETUP.md` in the main README.

### Step 1.5: Generate Secret

Generate a random secret string:

```bash
# macOS/Linux
openssl rand -base64 32

# Copy the output - you'll use it later
```

**Save this secret somewhere safe.**

### Step 1.6: Update Worker Code

Edit `src/index.js` and replace `FAILOVER_SECRET`:

```javascript
const FAILOVER_SECRET = "YOUR_SECRET_FROM_STEP_1.5";
```

### Step 1.7: Deploy

```bash
wrangler deploy
```

**Save the Worker URL from the output** - looks like:
```
https://wifi-failover-youraccount.workers.dev
```

### Step 1.8: Test Worker

```bash
# Test health endpoint
curl https://wifi-failover-youraccount.workers.dev/health

# Test status endpoint
curl https://wifi-failover-youraccount.workers.dev/api/status
```

Both should respond with JSON.

---

## Part 2: Install & Configure on Mac

### Step 2.1: Install the Package

```bash
# Using pip
pip install git+https://github.com/yourusername/wifi-failover-utility.git

# Or using uv
uv pip install git+https://github.com/yourusername/wifi-failover-utility.git
```

Verify installation:
```bash
wifi-failover --help
```

### Step 2.2: Run Interactive Setup

```bash
wifi-failover setup
```

This will guide you through:
- Selecting which WiFi networks to monitor
- Entering your phone's hotspot SSID
- Entering your Cloudflare Worker credentials
- Saving hotspot password to Keychain

**During this setup, you'll need:**
- Your phone's hotspot SSID (from Part 3 or ask your phone)
- Cloudflare Worker URL (from Step 1.7)
- Cloudflare Worker Secret (from Step 1.5)

### Step 2.3: Test the Monitor

```bash
# Run in foreground to see what happens
wifi-failover start

# Should log messages every 30 seconds
# Press Ctrl+C to stop
```

Watch the output. You should see:
```
Starting WiFi failover monitor
Networks to monitor: ['901 EXT5G']
Hotspot SSID: Dhruv's iPhone
Worker URL: https://wifi-failover-xxx.workers.dev
...
Network: 901 EXT5G, Internet: True, Failover: False
```

### Step 2.4: Install as Daemon (Optional)

To run automatically on Mac startup:

```bash
# Copy the plist file
sudo cp /path/to/com.wifi-failover.monitor.plist /Library/LaunchDaemons/

# Fix permissions
sudo chown root:wheel /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Load it
sudo launchctl load /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Verify it's running
ps aux | grep wifi-failover

# Watch logs
tail -f /tmp/wifi-failover/monitor.log
```

---

## Part 3: Configure Android Automation

Choose one of the following based on your preference:

### Option A: Automate (Recommended - Easier)

**Why Automate?**
- Visual block-based programming
- Easier learning curve
- Perfect for this use case
- Free version has all needed features

#### Step 3A.1: Install Automate

1. Open Google Play Store on Android phone
2. Search for "Automate" (by LlamaLab)
3. Install the app (free version)

#### Step 3A.2: Get Setup Instructions

The setup file was created on your Mac:
```
~/Desktop/AUTOMATE_SETUP.txt
```

Transfer it to your phone and follow the **5 steps** to:
1. Create a visual flow with HTTP GET, JSON parsing, and Hotspot blocks
2. Add an IF/ELSE conditional
3. Set up automatic 2-minute polling
4. Test the flow

#### Step 3A.3: Test Automate

1. In Automate, verify the flow toggle is ON (blue)
2. Tap the flow to open it
3. Tap ▶ Play button to manually test
4. Check that hotspot turns on/off correctly

---

### Option B: Tasker (More Powerful)

**Why Tasker?**
- More advanced automation capabilities
- Larger community support
- More trigger options available
- Better for complex workflows

#### Step 3B.1: Install Tasker

1. Open Google Play Store on Android phone
2. Search for "Tasker"
3. Install it (~$3)

#### Step 3B.2: Enable Device Admin

1. Open Tasker app
2. Tap ≡ Menu → Preferences
3. Scroll to "Misc"
4. Toggle "Device Admin" ON
5. Tap "Activate" when prompted

#### Step 3B.3: Get Setup Instructions

The setup file was created on your Mac:
```
~/Desktop/TASKER_SETUP.txt
```

Transfer it to your phone and follow the **7 steps** to:
1. Create a task named "WiFi Failover Monitor"
2. Add HTTP GET action to check status
3. Add JSON parsing
4. Add IF conditional
5. Add Hotspot turn-on action
6. Add HTTP POST acknowledgment
7. Create a time-based Profile that runs every 2 minutes

#### Step 3B.4: Test Tasker

1. In Tasker, go to **Tasks** tab
2. Find "WiFi Failover Monitor"
3. Tap the **▶ Play button**
4. Check Tasker log (Menu → More → Logcat) for any errors

---

## Part 4: End-to-End Testing

### Test 1: Trigger Failover Manually

On your Mac:

```bash
# Get your secret from config
grep worker_secret ~/.config/wifi-failover/config.json

# Trigger failover
curl -X POST https://wifi-failover-youraccount.workers.dev/api/command/enable \
  -H "Content-Type: application/json" \
  -d '{"secret": "YOUR_SECRET"}'

# Check status
curl https://wifi-failover-youraccount.workers.dev/api/status
```

Should return:
```json
{"hotspot_enabled": true, "timestamp": 1234567890, "mac_acknowledged": false}
```

### Test 2: Run Tasker Task Manually

1. Open Tasker on Android
2. Go to Tasks tab
3. Find "WiFi Failover Monitor"
4. Tap ▶ Play button
5. Wait a few seconds

Expected behavior:
- Phone's hotspot should turn ON
- You should see it in WiFi networks
- Logcat should show successful HTTP requests

### Test 3: Mac Connects to Hotspot

Once hotspot is enabled:

```bash
# Manually connect
networksetup -setairportnetwork en0 "Dhruv's iPhone" "your-hotspot-password"

# Or let the daemon do it (if running)
```

### Test 4: Full Failover Simulation

**Prerequisites:**
- Mac is on monitored WiFi network (e.g., "901 EXT5G")
- Daemon is running
- Hotspot password is in Keychain
- Tasker profile is active on Android

**Steps:**
1. Turn off your WiFi router (or disconnect the monitored network)
2. Watch Mac daemon logs:
   ```bash
   tail -f /tmp/wifi-failover/monitor.log
   ```
3. You should see:
   - "Connectivity lost" warning
   - "Triggering failover" message
   - "Waiting for hotspot to activate..."
   - "Connected to hotspot" success message
4. Verify Mac has internet:
   ```bash
   ping google.com
   ```
5. Turn router back on, wait 3+ successful pings
6. Watch daemon disable hotspot automatically

---

## Troubleshooting

### Mac Setup Issues

**"Configuration incomplete"**
- Run `wifi-failover setup` again
- Make sure you saved all values

**"Worker not responding"**
```bash
# Test Worker directly
curl https://your-worker-url/health
curl https://your-worker-url/api/status
```
- Check Worker URL is correct
- Verify Cloudflare deployment was successful

**"Can't connect to hotspot"**
```bash
# Check password is in Keychain
security find-generic-password -wa "Dhruv's iPhone"

# Test manual connection
networksetup -setairportnetwork en0 "Dhruv's iPhone" "password"
```

### Android Automation Issues

**Automate Issues:**

**"Flow doesn't run"**
- Check toggle is ON (blue) next to flow name
- Verify Automate has background execution enabled
- Check battery optimization isn't blocking Automate
- Tap ▶ Play to manually test the flow
- Check flow history (History tab) for execution logs

**"Hotspot doesn't enable"**
- Test manual hotspot toggle on phone first
- Verify HTTP blocks show correct responses
- Tap individual block names to see debug output
- Check internet connectivity during flow run

**"HTTP request fails"**
- Check phone has internet connection
- Verify Worker URL is exactly correct
- Test URL in phone browser
- Check flow history for error messages

---

**Tasker Issues:**

**"Task doesn't run"**
- Verify Device Admin is enabled
- Check Profile is active (Profiles tab)
- Check app battery optimization isn't blocking Tasker
- Manually test task with ▶ button

**"Hotspot doesn't enable"**
- Test manual hotspot toggle on phone first
- Try shell command instead: `cmd connectivity tether wifi start`
- Some Android versions require different commands

**"HTTP request fails"**
- Check phone has internet
- Verify Worker URL is correct
- Test URL in phone browser
- Check Logcat for error details

### General Issues

**"Daemon crashes"**
```bash
tail -f /tmp/wifi-failover/stderr.log
tail -f /tmp/wifi-failover/monitor.log
```

**"Wrong network detected"**
```bash
# Check available networks
/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport -s

# Reconfigure
wifi-failover setup
```

---

## Next Steps

Once everything is working:

1. **Monitor logs regularly**
   ```bash
   tail -f /tmp/wifi-failover/monitor.log
   ```

2. **Test periodically** (monthly or after macOS updates)

3. **Keep secrets safe**
   - Don't commit config files to git
   - Don't share Tasker backups publicly
   - Consider rotating Worker secret annually

4. **Improve the setup** - Feel free to:
   - Add more monitored networks
   - Adjust polling intervals (edit config)
   - Contribute improvements to the project

---

## Support

If you encounter issues:

1. **Check logs** - Usually the most helpful
2. **Review Troubleshooting** section above
3. **Test each component separately:**
   - Worker: Use curl
   - Mac daemon: Run in foreground
   - Tasker: Manually run task
4. **Open a GitHub issue** with:
   - Error messages
   - Relevant logs (remove secrets)
   - Setup steps taken
   - Android/macOS versions
