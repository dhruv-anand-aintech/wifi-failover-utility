# WiFi Failover Utility - Getting Started

## Quick Summary

This utility automatically switches your Mac from a failing WiFi network to your Android phone's hotspot, using a Cloudflare Worker as a relay and Automate (or Tasker) to control the phone.

## What You Need

- **macOS** (Big Sur or later)
- **Android phone** with either:
  - **Automate** (Google Play Store - free, recommended)
  - **Tasker** (Google Play Store - ~$3, more powerful)
- **Cloudflare account** (free tier works)
- **Internet connection** to deploy the Worker
- **15-20 minutes** to set everything up

## The Setup Order

> ⚠️ **Important:** Follow this exact order!

```
1. Deploy Cloudflare Worker
   └─ Get: Worker URL & Secret

2. Install Mac utility
   └─ Run: wifi-failover setup
   └─ Provide: Worker URL, Secret, Hotspot SSID

3. Configure Android Tasker
   └─ Create: WiFi Failover Monitor task
   └─ Set: Time-based profile (every 2 min)

4. Test everything
   └─ Trigger manual failover
   └─ Verify phone enables hotspot
   └─ Check Mac connects automatically
```

## Step 1: Deploy Cloudflare Worker (5 min)

### Install Wrangler

```bash
npm install -g wrangler
# or: brew install wrangler
```

### Authenticate

```bash
wrangler login
```

### Create KV Namespaces

```bash
wrangler kv:namespace create "WIFI_FAILOVER"
wrangler kv:namespace create "WIFI_FAILOVER" --preview
```

**Save the output** - you'll need the namespace IDs.

### Create `wrangler.toml`

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

Replace the namespace IDs from the previous command.

### Create Worker Code

Create `src/index.js` with the code from this file:
**[Copy from CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md)**

Don't forget to replace `FAILOVER_SECRET`:

```javascript
const FAILOVER_SECRET = "openssl rand -base64 32";  // Generate a random string
```

Generate a secret:
```bash
openssl rand -base64 32
```

### Deploy

```bash
wrangler deploy
```

**Save the Worker URL** - looks like:
```
https://wifi-failover-youraccount.workers.dev
```

### Verify

```bash
curl https://wifi-failover-youraccount.workers.dev/health
```

✓ Should respond with `OK`

---

## Step 2: Install & Configure Mac (5 min)

### Install the Package

```bash
pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

Or with uv:
```bash
uv pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

### Run Setup Wizard

```bash
wifi-failover setup
```

This will ask you:

1. **Which networks to monitor?**
   - Lists your available WiFi networks
   - Select the ones you want to failover from (e.g., "901 EXT5G")

2. **Phone's hotspot SSID?**
   - The name your phone shows in WiFi networks
   - (Example: "Dhruv's iPhone")

3. **Cloudflare Worker URL?**
   - The URL from Step 1 deploy
   - (Example: https://wifi-failover-youraccount.workers.dev)

4. **Worker Secret?**
   - The random string you generated in Step 1
   - (Example: abc123XYZ...)

5. **Save hotspot password to Keychain?**
   - Tap 'y' to let the daemon auto-connect
   - You'll need the actual hotspot WiFi password

### Test It

```bash
wifi-failover start
```

Should log messages like:
```
Network: 901 EXT5G, Internet: True, Failover: False
```

Press `Ctrl+C` to stop.

### View Configuration

```bash
wifi-failover status
```

Shows your current setup and recent logs.

---

## Step 3: Configure Android (10 min)

### Choose Your Automation App

**Recommended: Automate** (easier, visual blocks)
- Free, simpler to set up
- Use this if you want a straightforward visual approach
- Instructions: `~/Desktop/AUTOMATE_SETUP.txt`

**Alternative: Tasker** (more powerful)
- ~$3 paid app with advanced features
- Use this if you need complex automation
- Instructions: `~/Desktop/TASKER_SETUP.txt`

### Install Your Chosen App

**For Automate:**
1. Open Google Play Store on Android phone
2. Search for "Automate"
3. Install the app by LlamaLab

**For Tasker:**
1. Open Google Play Store on Android phone
2. Search for "Tasker"
3. Install it

### Get Setup Instructions

Both setup files are automatically created on your Mac:

```bash
# Check what files were created
ls ~/Desktop/*_SETUP.txt
```

### Follow the Instructions

Transfer the appropriate file to your phone:

**For Automate:**
- Follow the 5 steps to create a visual flow
- Set up automatic 2-minute polling
- No complex scripting needed

**For Tasker:**
- Follow the 7 steps to create a task and profile
- Configure Device Admin access
- Set up time-based profile

---

## Step 4: Test Everything (5 min)

### Manual Failover Test

```bash
# Get your secret
grep worker_secret ~/.config/wifi-failover/config.json

# Trigger failover
curl -X POST https://your-worker-url/api/command/enable \
  -H "Content-Type: application/json" \
  -d '{"secret": "YOUR_SECRET"}'

# Check status
curl https://your-worker-url/api/status
```

Should return:
```json
{"hotspot_enabled": true, "timestamp": ..., "mac_acknowledged": false}
```

### Phone Should Enable Hotspot

1. Open Tasker on Android
2. Go to **Tasks** tab
3. Find **WiFi Failover Monitor**
4. Tap **▶ Play**
5. Watch your phone's hotspot turn ON

### Mac Should Connect

If daemon is running, it will auto-connect. Otherwise:

```bash
# Check if connection succeeded
ifconfig | grep "ssid"
ping google.com
```

### Reset Everything

To cancel the failover:

```bash
curl -X POST https://your-worker-url/api/command/disable \
  -H "Content-Type: application/json" \
  -d '{"secret": "YOUR_SECRET"}'
```

---

## Installation as Daemon (Optional)

To run automatically on Mac startup:

```bash
# Copy the plist
sudo cp launchd/com.wifi-failover.monitor.plist /Library/LaunchDaemons/

# Fix permissions
sudo chown root:wheel /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Load it
sudo launchctl load /Library/LaunchDaemons/com.wifi-failover.monitor.plist

# Verify
ps aux | grep wifi-failover

# Watch logs
tail -f /tmp/wifi-failover/monitor.log
```

---

## Verify It's Working

```bash
# Check if daemon is running
ps aux | grep "wifi-failover start"

# Watch logs in real-time
tail -f /tmp/wifi-failover/monitor.log

# Should see:
# - Network connectivity checks every 30 sec
# - Status updates every 60 sec
# - Failover triggers if internet drops
```

---

## Troubleshooting Quick Reference

| Problem | Check |
|---------|-------|
| WiFi not detected | `airport -I` |
| Can't connect to hotspot | Verify password in Keychain: `security find-generic-password -wa "Dhruv's Phone"` |
| Automate not running | Check app is in background, toggle is enabled |
| Tasker not running | Enable Device Admin, check Profile is active |
| Worker not responding | `curl https://your-worker/health` |
| Daemon won't start | Check logs: `tail -f /tmp/wifi-failover/stderr.log` |

---

## Full Documentation

- **README.md** - Feature overview
- **COMPLETE_SETUP_GUIDE.md** - Detailed step-by-step guide
- **CLOUDFLARE_SETUP.md** - Cloudflare Worker deployment
- **TASKER_SETUP.txt** - Generated during setup, copy to phone

---

## GitHub Repository

The package is ready to use:

**Repository:** https://github.com/dhruv-anand-aintech/wifi-failover-utility

**Install with:**
```bash
pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
```

---

## Need Help?

Check the logs first:
```bash
tail -f /tmp/wifi-failover/monitor.log
tail -f /tmp/wifi-failover/stderr.log
```

See **Troubleshooting** section in:
- COMPLETE_SETUP_GUIDE.md
- README.md

Or check Tasker Logcat on Android:
- Menu → More → Logcat
