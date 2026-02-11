"""Configuration management for WiFi Failover Utility"""

import json
import subprocess
from pathlib import Path
from typing import List, Optional


class Config:
    """Manages WiFi Failover configuration"""

    CONFIG_DIR = Path.home() / ".config" / "wifi-failover"
    CONFIG_FILE = CONFIG_DIR / "config.json"

    def __init__(self):
        self.config_dir = self.CONFIG_DIR
        self.config_file = self.CONFIG_FILE
        self.data = self.load()

    @classmethod
    def ensure_config_dir(cls):
        """Create config directory if it doesn't exist"""
        cls.CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    def load(self) -> dict:
        """Load configuration from file"""
        self.ensure_config_dir()
        if self.config_file.exists():
            with open(self.config_file, 'r') as f:
                return json.load(f)
        return {}

    def save(self):
        """Save configuration to file"""
        self.ensure_config_dir()
        with open(self.config_file, 'w') as f:
            json.dump(self.data, f, indent=2)

    def get(self, key: str, default=None):
        """Get configuration value"""
        return self.data.get(key, default)

    def set(self, key: str, value):
        """Set configuration value"""
        self.data[key] = value
        self.save()

    def get_monitored_networks(self) -> List[str]:
        """Get list of networks to monitor"""
        networks = self.get("monitored_networks", [])
        return networks if isinstance(networks, list) else [networks] if networks else []

    def set_monitored_networks(self, networks: List[str]):
        """Set networks to monitor"""
        self.set("monitored_networks", networks)

    def get_hotspot_ssid(self) -> str:
        """Get phone's hotspot SSID"""
        return self.get("hotspot_ssid", "")

    def set_hotspot_ssid(self, ssid: str):
        """Set phone's hotspot SSID"""
        self.set("hotspot_ssid", ssid)

    def get_worker_url(self) -> str:
        """Get Cloudflare Worker URL"""
        return self.get("worker_url", "")

    def set_worker_url(self, url: str):
        """Set Cloudflare Worker URL"""
        self.set("worker_url", url)

    def get_worker_secret(self) -> str:
        """Get Cloudflare Worker secret"""
        return self.get("worker_secret", "")

    def set_worker_secret(self, secret: str):
        """Set Cloudflare Worker secret"""
        self.set("worker_secret", secret)


def get_available_networks() -> List[str]:
    """
    Get list of available WiFi networks on Mac
    Returns SSID names that the Mac can detect
    """
    try:
        # Use airport command to scan networks
        airport_path = "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport"
        result = subprocess.run(
            [airport_path, "-s"],
            capture_output=True,
            text=True,
            timeout=10
        )

        networks = []
        for line in result.stdout.strip().split('\n')[1:]:  # Skip header
            if line.strip():
                # Format: "SSID BSSID             RSSI CHANNEL HT CC SECURITY"
                parts = line.split()
                if parts:
                    # SSID is the first part (could be multiple words if quoted)
                    # But airport -s doesn't quote, so take first word
                    ssid = parts[0]
                    if ssid and ssid != "SSID":
                        networks.append(ssid)

        return sorted(list(set(networks)))  # Remove duplicates and sort
    except Exception as e:
        print(f"Error scanning networks: {e}")
        return []


def get_current_network() -> Optional[str]:
    """Get currently connected WiFi network"""
    try:
        airport_path = "/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport"
        result = subprocess.run(
            [airport_path, "-I"],
            capture_output=True,
            text=True,
            timeout=5
        )
        for line in result.stdout.split('\n'):
            if 'SSID:' in line:
                return line.split('SSID:')[1].strip()
    except Exception as e:
        print(f"Error getting current network: {e}")
    return None
