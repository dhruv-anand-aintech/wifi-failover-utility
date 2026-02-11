"""Generate step-by-step Tasker setup instructions"""


def get_tasker_setup_guide(worker_url: str, worker_secret: str) -> str:
    """Generate comprehensive Tasker setup instructions"""

    return f"""
╔════════════════════════════════════════════════════════════════════════════╗
║                    ANDROID TASKER SETUP - STEP BY STEP                     ║
╚════════════════════════════════════════════════════════════════════════════╝

Your Cloudflare Worker is deployed at:
  {worker_url}

Secret: {worker_secret}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

STEP 1: INSTALL TASKER & ENABLE DEVICE ADMIN
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Install "Tasker" from Google Play Store (~$3)
2. Open Tasker → Tap ≡ Menu (three lines) → Preferences
3. Scroll to "Misc" section
4. Toggle "Device Admin" ON
5. When prompted, tap "Activate" to grant permission
   (This allows Tasker to enable hotspot)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

STEP 2: CREATE THE TASK
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Tap "Tasks" tab (circle icon at bottom)
2. Tap "+" button to create new task
3. Name it: WiFi Failover Monitor
4. Tap checkmark ✓

You're now in the task editor. Add these actions in order:

─ ACTION 1: HTTP GET (Check status) ─────────────────────────────────────

1. Tap "+" to add action
2. Navigate: Net → HTTP Get
3. Configure these fields:
   Server:Port: {worker_url}/api/status
   Timeout (Sec): 10
   Output variable: %http_response
   Accept Any Certificate: ✓ (check if needed)
4. Tap ✓ to save

─ ACTION 2: JAVASCRIPT (Parse response) ────────────────────────────────

1. Tap "+" to add action
2. Navigate: Code → JavaScriptlet
3. Paste this code:
   let response = JSON.parse(global("http_response"));
   setLocal("should_enable", response.hotspot_enabled ? "yes" : "no");

─ ACTION 3: IF (Conditional) ───────────────────────────────────────────

1. Tap "+" to add action
2. Navigate: Logic → If
3. Configure condition:
   %should_enable eq yes
   (Leave Type as "Condition")

─ ACTION 4: HOTSPOT (Turn ON) ──────────────────────────────────────────

1. Tap "+" (should be INSIDE the If block, indented)
2. Navigate: System → Hotspot
3. Configure:
   Action: Turn On
   Wifi: Off (or On, depending on your preference)

   ⚠️  If "Hotspot" action doesn't appear, use alternative:
   - Navigate: Misc → Run Shell
   - Command: cmd connectivity tether wifi start

─ ACTION 5: HTTP POST (Send acknowledgment) ────────────────────────────

1. Tap "+" (should still be INSIDE If block)
2. Navigate: Net → HTTP Post
3. Configure:
   Server:Port: {worker_url}/api/acknowledge
   Data / Form:
     secret={worker_secret}
     timestamp=%TIMES

   Timeout (Sec): 10
   Accept Any Certificate: ✓

─ ACTION 6: END IF ─────────────────────────────────────────────────────

1. Tap "+"
2. Navigate: Logic → End If
   (This closes the conditional block)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

STEP 3: CREATE THE PROFILE (Make it run automatically)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Tap "Profiles" tab (stopwatch icon)
2. Tap "+" to create new profile
3. Select "Time"
4. Configure:
   Repeat: Every 2 Minutes (or 1 minute for faster failover)
   From: 00:00
   To: 23:59
5. Tap ✓ to save
6. When prompted, select: WiFi Failover Monitor (your task)
7. Tap ✓

✓ Profile is now active! The task will run every 2 minutes.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

STEP 4: TEST THE SETUP
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Manual Test:
1. In Tasker, go to Tasks tab
2. Find "WiFi Failover Monitor"
3. Tap the ▶ Play button
4. Check Tasker log: Menu → More → Logcat

Manual Failover Trigger (from Mac terminal):
  curl -X POST {worker_url}/api/command/enable \\
    -H "Content-Type: application/json" \\
    -d '{{"secret": "{worker_secret}"}}'

Then run the Tasker task manually. It should:
  1. Fetch /api/status → returns hotspot_enabled: true
  2. Parse the response
  3. Enable hotspot
  4. Send acknowledgment

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

TROUBLESHOOTING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Task doesn't run:
  ✓ Check Device Admin is enabled
  ✓ Check Profile is active (Profiles tab should show it)
  ✓ Check battery optimization isn't blocking Tasker
    Settings → Apps → Tasker → Battery → Unrestricted/Never sleep

Hotspot doesn't turn on:
  ✓ Verify hotspot works manually on your phone first
  ✓ Try the shell command alternative instead of native action
  ✓ Some Android versions have different commands

HTTP request fails:
  ✓ Check phone has internet connection
  ✓ Verify Worker URL is correct (test in browser)
  ✓ View %http_response in debug notification:
    Tap ≡ Menu → More → Logcat

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

SECURITY NOTES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

• Your secret is embedded in the Tasker task
• Only store Tasker backup files in secure locations
• Don't share Tasker backups publicly
• Consider rotating the secret periodically

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
"""


def save_tasker_instructions(worker_url: str, worker_secret: str, output_path: str):
    """Save Tasker instructions to a file"""
    content = get_tasker_setup_guide(worker_url, worker_secret)
    with open(output_path, 'w') as f:
        f.write(content)
    print(f"✓ Tasker instructions saved to: {output_path}")
