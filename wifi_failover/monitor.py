"""WiFi Failover Monitor - macOS daemon"""

import subprocess
import time
import requests
import logging
import threading
from pathlib import Path
from typing import List, Optional


class WiFiFailoverMonitor:
    """Monitors WiFi connectivity and triggers hotspot failover"""

    def __init__(
        self,
        monitored_networks: List[str],
        hotspot_ssid: str,
        worker_url: str,
        worker_secret: str,
        check_interval: int = 5,
        failure_threshold: int = 2,
        recovery_threshold: int = 3,
        log_dir: Optional[str] = None
    ):
        self.monitored_networks = monitored_networks
        self.hotspot_ssid = hotspot_ssid
        self.worker_url = worker_url
        self.worker_secret = worker_secret
        self.check_interval = check_interval
        self.failure_threshold = failure_threshold
        self.recovery_threshold = recovery_threshold

        # Heartbeat thread control
        self.heartbeat_thread = None
        self.heartbeat_stop = threading.Event()
        self.heartbeat_count = 0
        self.last_lock_status = None  # Track lock status for change detection

        # Setup logging
        if log_dir is None:
            log_dir = str(Path.home() / ".wifi-failover-logs")
        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(exist_ok=True)
        self.log_file = self.log_dir / "monitor.log"

        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(levelname)s - %(message)s',
            handlers=[
                logging.FileHandler(self.log_file),
                logging.StreamHandler()
            ]
        )
        self.logger = logging.getLogger(__name__)

    def is_screen_locked(self) -> bool:
        """Check if macOS screen is locked or sleeping"""
        try:
            # Check if ScreenSaverEngine is running (indicates lock or screensaver active)
            pgrep_result = subprocess.run(
                ["pgrep", "-x", "ScreenSaverEngine"],
                capture_output=True,
                timeout=5
            )
            return pgrep_result.returncode == 0
        except Exception:
            # If we can't determine, assume not locked (safe default)
            return False

    def get_current_network(self) -> str:
        """Get currently connected WiFi network name"""
        try:
            # Use networksetup which works on all modern macOS versions
            result = subprocess.run(
                ["/usr/sbin/networksetup", "-getairportnetwork", "en0"],
                capture_output=True,
                text=True,
                timeout=5
            )
            output = result.stdout.strip()

            # Output format: "Current Wi-Fi Network: SSID_NAME" or "You are not associated..."
            if "Current Wi-Fi Network:" in output:
                return output.split("Current Wi-Fi Network:")[1].strip()
            elif "You are not associated" in output:
                return ""

            return ""
        except Exception as e:
            self.logger.error(f"Error getting network: {e}")
            return ""

    def check_internet_connectivity(self, host: str = "8.8.8.8", timeout: int = 5) -> bool:
        """Check if internet is reachable"""
        try:
            result = subprocess.run(
                ["ping", "-c", "1", "-W", str(timeout * 1000), host],
                capture_output=True,
                timeout=timeout + 3
            )
            return result.returncode == 0
        except subprocess.TimeoutExpired:
            # Ping timed out - treat as no connectivity
            return False
        except Exception as e:
            self.logger.error(f"Error checking connectivity: {e}")
            return False

    def trigger_hotspot_failover(self) -> bool:
        """Tell Android phone to enable hotspot via Cloudflare Worker"""
        try:
            response = requests.post(
                f"{self.worker_url}/api/command/enable",
                json={"secret": self.worker_secret},
                timeout=10
            )
            if response.status_code == 200:
                self.logger.info("Hotspot failover triggered")
                return True
            else:
                self.logger.error(f"Failed to trigger hotspot: {response.status_code}")
                return False
        except Exception as e:
            self.logger.error(f"Error triggering hotspot: {e}")
            return False

    def disable_hotspot_command(self) -> bool:
        """Tell Android to disable hotspot"""
        try:
            response = requests.post(
                f"{self.worker_url}/api/command/disable",
                json={"secret": self.worker_secret},
                timeout=10
            )
            if response.status_code == 200:
                self.logger.info("Hotspot disable command sent")
                return True
            else:
                self.logger.error(f"Failed to disable hotspot: {response.status_code}")
                return False
        except Exception as e:
            self.logger.error(f"Error disabling hotspot: {e}")
            return False

    def send_heartbeat(self) -> bool:
        """Send heartbeat to Cloudflare Worker to indicate daemon is alive"""
        try:
            # Check if screen is locked - if so, send "paused" status
            is_locked = self.is_screen_locked()
            status = "paused" if is_locked else "active"

            # Log immediately when lock status changes
            if self.last_lock_status is None or is_locked != self.last_lock_status:
                if is_locked:
                    self.logger.info("🔒 Screen LOCKED - sending 'paused' status")
                else:
                    self.logger.info("🔓 Screen UNLOCKED - sending 'active' status")
                self.last_lock_status = is_locked

            response = requests.post(
                f"{self.worker_url}/api/heartbeat",
                json={"secret": self.worker_secret, "status": status},
                timeout=10
            )
            if response.status_code == 200:
                self.heartbeat_count += 1
                # Log every 10th heartbeat (~20 seconds)
                if self.heartbeat_count % 10 == 0:
                    self.logger.info(f"♥ Heartbeats sent ({self.heartbeat_count}), status: {status}")
                return True
            else:
                self.logger.warning(f"Heartbeat failed: {response.status_code}")
                return False
        except Exception as e:
            self.logger.warning(f"Error sending heartbeat: {e}")
            return False

    def _heartbeat_loop(self):
        """Background thread that sends heartbeats every 2 seconds"""
        while not self.heartbeat_stop.is_set():
            self.send_heartbeat()
            time.sleep(2)

    def start_heartbeat_thread(self):
        """Start background thread for sending heartbeats"""
        if self.heartbeat_thread is None or not self.heartbeat_thread.is_alive():
            self.heartbeat_stop.clear()
            self.heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
            self.heartbeat_thread.start()
            self.logger.info("Heartbeat thread started")

    def stop_heartbeat_thread(self):
        """Stop background heartbeat thread"""
        if self.heartbeat_thread is not None:
            self.heartbeat_stop.set()
            self.heartbeat_thread.join(timeout=2)
            self.logger.info("Heartbeat thread stopped")

    def connect_to_hotspot(self) -> bool:
        """Connect Mac to phone's hotspot"""
        try:
            self.logger.info(f"Attempting to connect to {self.hotspot_ssid}...")

            # Try to get password from Keychain
            try:
                password = subprocess.run(
                    ["security", "find-generic-password", "-wa", self.hotspot_ssid],
                    capture_output=True,
                    text=True,
                    timeout=5
                ).stdout.strip()

                if password:
                    result = subprocess.run(
                        ["networksetup", "-setairportnetwork", "en0", self.hotspot_ssid, password],
                        capture_output=True,
                        text=True,
                        timeout=15
                    )
                    if result.returncode == 0:
                        self.logger.info(f"Connected to {self.hotspot_ssid}")
                        return True
                    else:
                        self.logger.error(f"Connection failed: {result.stderr}")
                        return False
                else:
                    self.logger.error(f"No password found in Keychain for {self.hotspot_ssid}")
                    return False
            except Exception as e:
                self.logger.error(f"Error getting password from Keychain: {e}")
                return False

        except Exception as e:
            self.logger.error(f"Error connecting to hotspot: {e}")
            return False

    def monitor_network(self):
        """Main monitoring loop"""
        self.logger.info(f"Starting WiFi failover monitor")
        self.logger.info(f"Networks to monitor: {self.monitored_networks}")
        self.logger.info(f"Hotspot SSID: {self.hotspot_ssid}")
        self.logger.info(f"Worker URL: {self.worker_url}")

        # Start heartbeat thread
        self.start_heartbeat_thread()

        failure_count = 0
        recovery_count = 0
        failover_active = False
        last_status_log = time.time()
        last_internet_state = None

        try:
            while True:
                is_connected = self.check_internet_connectivity()

                # Log on state change (immediately) or periodically (every 5 minutes)
                should_log_status = False
                if last_internet_state is None or is_connected != last_internet_state:
                    # Internet status changed - log immediately
                    should_log_status = True
                    last_status_log = time.time()
                elif time.time() - last_status_log > 300:
                    # 5 minutes have passed - log periodic status
                    should_log_status = True
                    last_status_log = time.time()

                if should_log_status:
                    status_msg = "🟢 ONLINE" if is_connected else "🔴 OFFLINE"
                    self.logger.info(f"Internet: {status_msg}, Failover: {failover_active}")
                    last_internet_state = is_connected

                # Check internet connectivity regardless of network
                if is_connected:
                    # Internet is working
                    failure_count = 0
                    recovery_count += 1

                    # If we had a failover active and now recovered, disable it
                    if failover_active and recovery_count >= self.recovery_threshold:
                        self.logger.info("Internet restored, disabling hotspot failover")
                        self.disable_hotspot_command()
                        failover_active = False
                        recovery_count = 0
                else:
                    # Internet is down
                    recovery_count = 0
                    failure_count += 1

                    if failure_count >= self.failure_threshold and not failover_active:
                        self.logger.warning(
                            f"Internet connectivity lost ({failure_count} failures), triggering failover"
                        )

                        # Step 1: Tell Android to enable hotspot
                        if self.trigger_hotspot_failover():
                            # Step 2: Wait for hotspot to come up
                            self.logger.info("Waiting for hotspot to activate...")
                            time.sleep(5)

                            # Step 3: Connect to hotspot
                            if self.connect_to_hotspot():
                                failover_active = True
                                failure_count = 0
                                self.logger.info("Failover complete, connected to hotspot")
                            else:
                                self.logger.error("Failed to connect to hotspot")

                time.sleep(self.check_interval)

        except KeyboardInterrupt:
            self.logger.info("Monitor stopped by user")
        except Exception as e:
            self.logger.error(f"Unexpected error: {e}", exc_info=True)
        finally:
            self.stop_heartbeat_thread()
