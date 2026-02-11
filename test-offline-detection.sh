#!/bin/bash
# Test script for offline detection

set -e

echo "==============================================="
echo "WiFi Failover - Offline Detection Test"
echo "==============================================="
echo ""

# Kill only wifi-failover-monitor instances (not all python)
echo "1. Cleaning up wifi-failover-monitor instances..."
ps aux | grep "wifi-failover-monitor" | grep -v grep | awk '{print $2}' | xargs -r kill -9 2>/dev/null || true
sleep 2

# Start via launchctl (ensures single instance)
echo "2. Starting daemon via launchctl..."
launchctl unload ~/Library/LaunchAgents/com.wifi-failover.monitor.plist 2>/dev/null || true
sleep 1
launchctl load ~/Library/LaunchAgents/com.wifi-failover.monitor.plist
sleep 5

# Verify single instance
DAEMON_COUNT=$(ps aux | grep -c "wifi-failover-monitor" | grep -v grep || echo "0")
echo "   Daemon instances: $DAEMON_COUNT (should be 1 or 2)"
echo ""

# Check Worker status before pause
echo "3. Checking Worker status (should show fresh heartbeat)..."
WORKER_STATUS=$(curl -s "https://wifi-failover.dhruv-anand.workers.dev/api/status?secret=dhruv-secret" | jq '.time_since_heartbeat')
echo "   time_since_heartbeat: ${WORKER_STATUS}ms (should be < 5000ms)"
echo ""

# Pause heartbeats
echo "4. Pausing heartbeats..."
wifi-failover pause-heartbeat
echo ""

# Wait for offline detection
echo "5. Waiting 20 seconds for offline detection..."
echo "   Watch Android logcat: adb logcat 'WiFiFailoverWorker' -v time"
sleep 20

# Check Worker status after pause
echo ""
echo "6. Checking Worker status (should show stale heartbeat)..."
WORKER_STATUS=$(curl -s "https://wifi-failover.dhruv-anand.workers.dev/api/status?secret=dhruv-secret" | jq '.time_since_heartbeat')
echo "   time_since_heartbeat: ${WORKER_STATUS}ms (should be > 12000ms for offline)"
echo ""

# Resume
echo "7. Resuming heartbeats..."
wifi-failover resume-heartbeat
sleep 5

# Check Worker status after resume
echo ""
echo "8. Verifying heartbeats resumed..."
WORKER_STATUS=$(curl -s "https://wifi-failover.dhruv-anand.workers.dev/api/status?secret=dhruv-secret" | jq '.time_since_heartbeat')
echo "   time_since_heartbeat: ${WORKER_STATUS}ms (should be < 5000ms)"
echo ""

echo "==============================================="
echo "Test complete!"
echo "==============================================="
