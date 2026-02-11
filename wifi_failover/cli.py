"""Interactive CLI for WiFi Failover Utility setup"""

import argparse
import subprocess
import psutil
import os
from pathlib import Path
from .config import Config
from .monitor import WiFiFailoverMonitor


def print_banner():
    """Print welcome banner"""
    print("""
╔════════════════════════════════════════════════════════════════════════════╗
║                  WiFi Failover Utility - Setup Wizard                      ║
║            Automatic failover from WiFi to Android hotspot                 ║
╚════════════════════════════════════════════════════════════════════════════╝
""")


def print_section(title: str):
    """Print a section header"""
    print(f"\n{'━' * 80}")
    print(f"  {title}")
    print(f"{'━' * 80}\n")


def load_env_file() -> dict:
    """Load environment variables from ~/.env or ~/Code/.env"""
    env_vars = {}

    # Try ~/Code/.env first, then ~/.env
    env_files = [
        Path.home() / "Code" / ".env",
        Path.home() / ".env"
    ]

    for env_file in env_files:
        if env_file.exists():
            try:
                with open(env_file) as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith('#') and '=' in line:
                            key, value = line.split('=', 1)
                            env_vars[key.strip()] = value.strip().strip('"\'')
                return env_vars
            except Exception as e:
                print(f"⚠️  Warning: Could not read {env_file}: {e}")

    return env_vars


def get_hotspot_ssid(env_vars: dict = None) -> str:
    """Get phone's hotspot SSID"""
    if env_vars is None:
        env_vars = {}

    print_section("Step 1: Phone Hotspot Name")
    print("Enter your phone's hotspot SSID (the name that appears in WiFi networks):")
    if "HOTSPOT_SSID" in env_vars:
        print(f"(Press Enter to use: {env_vars['HOTSPOT_SSID']})")
    print("(Example: 'Dhruv's iPhone')\n")

    ssid = input("> ").strip()

    # Use env var if empty string entered
    if not ssid and "HOTSPOT_SSID" in env_vars:
        ssid = env_vars["HOTSPOT_SSID"]
        print(f"Using HOTSPOT_SSID from .env: {ssid}")

    if not ssid:
        print("❌ Hotspot name cannot be empty")
        return get_hotspot_ssid(env_vars)

    print(f"\n✓ Hotspot SSID: {ssid}")
    return ssid


def get_cloudflare_credentials(env_vars: dict = None) -> tuple:
    """Get Cloudflare Worker credentials"""
    if env_vars is None:
        env_vars = {}

    print_section("Step 2: Cloudflare Worker Credentials")
    print("Enter your Cloudflare Worker URL and secret.")
    print("If you don't have one deployed yet, see: https://github.com/yourusername/wifi-failover-utility/blob/main/CLOUDFLARE_SETUP.md\n")

    worker_url = input("Worker URL (e.g., https://wifi-failover.youraccount.workers.dev): ").strip()

    # Use env var if empty string entered
    if not worker_url and "WORKER_URL" in env_vars:
        worker_url = env_vars["WORKER_URL"]
        print(f"Using WORKER_URL from .env: {worker_url}")

    if not worker_url.startswith("https://"):
        print("❌ Worker URL must start with https://")
        return get_cloudflare_credentials(env_vars)

    worker_secret = input("Worker Secret: ").strip()

    # Use env var if empty string entered
    if not worker_secret and "WORKER_SECRET" in env_vars:
        worker_secret = env_vars["WORKER_SECRET"]
        print(f"Using WORKER_SECRET from .env")

    if not worker_secret:
        print("❌ Worker secret cannot be empty")
        return get_cloudflare_credentials(env_vars)

    print(f"\n✓ Worker URL: {worker_url}")
    print(f"✓ Worker Secret: {'*' * (len(worker_secret) - 4)}{worker_secret[-4:]}")
    return worker_url, worker_secret


def save_hotspot_password(hotspot_ssid: str, env_vars: dict = None):
    """Save hotspot password to Keychain"""
    if env_vars is None:
        env_vars = {}

    print_section("Step 3: Hotspot Password")
    print("The daemon needs your hotspot password to auto-connect on failover.")
    print("This will be stored securely in your Mac's Keychain.\n")

    response = input(f"Save '{hotspot_ssid}' password to Keychain? (y/n): ").strip().lower()
    if response != 'y':
        print("⚠️  Skipped. You'll need to manually add it to Keychain later.")
        return

    password = input(f"Enter '{hotspot_ssid}' WiFi password: ").strip()

    # Use env var if empty string entered
    if not password and "HOTSPOT_PASSWORD" in env_vars:
        password = env_vars["HOTSPOT_PASSWORD"]
        print(f"Using HOTSPOT_PASSWORD from .env")

    if not password:
        print("❌ Password cannot be empty")
        return save_hotspot_password(hotspot_ssid, env_vars)

    # Add to Keychain
    import subprocess
    try:
        subprocess.run(
            ["security", "add-generic-password", "-a", hotspot_ssid, "-s", hotspot_ssid, "-w", password, "-U"],
            check=True,
            capture_output=True
        )
        print(f"✓ Password saved to Keychain for '{hotspot_ssid}'")
    except subprocess.CalledProcessError:
        # Password might already exist, try to update it
        try:
            subprocess.run(
                ["security", "delete-generic-password", "-a", hotspot_ssid, "-s", hotspot_ssid],
                capture_output=True
            )
            subprocess.run(
                ["security", "add-generic-password", "-a", hotspot_ssid, "-s", hotspot_ssid, "-w", password],
                check=True,
                capture_output=True
            )
            print(f"✓ Password updated in Keychain for '{hotspot_ssid}'")
        except Exception as e:
            print(f"❌ Error saving to Keychain: {e}")


def setup_interactive():
    """Run interactive setup wizard"""
    print_banner()

    # Load environment variables from .env file
    env_vars = load_env_file()
    if env_vars:
        print("✓ Loaded environment variables from .env\n")

    # Step 1: Hotspot SSID
    hotspot_ssid = get_hotspot_ssid(env_vars)

    # Step 2: Cloudflare credentials
    worker_url, worker_secret = get_cloudflare_credentials(env_vars)

    # Step 3: Save hotspot password
    save_hotspot_password(hotspot_ssid, env_vars)

    # Save configuration
    print_section("Saving Configuration")
    config = Config()
    config.set_hotspot_ssid(hotspot_ssid)
    config.set_worker_url(worker_url)
    config.set_worker_secret(worker_secret)
    print(f"✓ Configuration saved to: {config.config_file}")

    # Set up auto-start on login
    print_section("Auto-Start on Login")
    response = input("Enable auto-start when you login? (y/n): ").strip().lower()

    if response == 'y':
        setup_launchd_autostart()
        print("\nStarting daemon now...")
        start_daemon_launchd()
    else:
        print_section("Setup Complete! ✓")
        print("\nTo start the daemon later, run:")
        print("  wifi-failover daemon")
        print("\nTo enable auto-start on login, run:")
        print("  wifi-failover enable-autostart")

    print_section("Setup Complete! ✓")
    return True


def kill_existing_daemons():
    """Kill any existing WiFi failover daemon processes"""
    killed = 0
    try:
        for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
            try:
                cmdline = proc.info.get('cmdline') or []
                # Check if this is our daemon process
                if any('wifi_failover' in str(arg) and 'start' in str(arg) for arg in cmdline):
                    if proc.pid != os.getpid():  # Don't kill ourselves
                        proc.kill()
                        killed += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                pass
    except Exception:
        pass
    return killed


def setup_launchd_autostart():
    """Install and enable launchd auto-start on login"""
    print_section("Setting Up Auto-Start on Login")

    # Plist file is in the same directory as this module
    plist_source = Path(__file__).parent / "com.wifi-failover.monitor.plist"
    plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.wifi-failover.monitor.plist"

    if not plist_source.exists():
        print(f"❌ Plist file not found: {plist_source}")
        return False

    try:
        # Create LaunchAgents directory if needed
        plist_dest.parent.mkdir(parents=True, exist_ok=True)

        # Copy plist file
        import shutil
        shutil.copy(plist_source, plist_dest)
        print(f"✓ Copied plist to {plist_dest}")

        # Load the plist with launchctl
        result = subprocess.run(
            ["launchctl", "load", str(plist_dest)],
            capture_output=True,
            text=True
        )

        if result.returncode == 0:
            print(f"✅ Auto-start enabled!")
            print(f"   Daemon will start automatically on login")
            print(f"   To disable: launchctl unload {plist_dest}")
            return True
        else:
            # Might already be loaded
            if "already loaded" in result.stderr.lower():
                print(f"✓ Auto-start already enabled")
                return True
            else:
                print(f"❌ Error enabling auto-start: {result.stderr}")
                return False

    except Exception as e:
        print(f"❌ Error setting up auto-start: {e}")
        return False


def disable_launchd_autostart():
    """Disable launchd auto-start"""
    plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.wifi-failover.monitor.plist"

    try:
        result = subprocess.run(
            ["launchctl", "unload", str(plist_dest)],
            capture_output=True,
            text=True
        )

        if result.returncode == 0 or "not loaded" in result.stderr.lower():
            print(f"✓ Auto-start disabled")
            if plist_dest.exists():
                plist_dest.unlink()
            return True
        else:
            print(f"❌ Error: {result.stderr}")
            return False

    except Exception as e:
        print(f"❌ Error: {e}")
        return False


def start_daemon_launchd():
    """Start the daemon using launchctl (requires auto-start to be set up)"""
    plist_dest = Path.home() / "Library" / "LaunchAgents" / "com.wifi-failover.monitor.plist"

    if not plist_dest.exists():
        print(f"❌ Auto-start not configured. Run 'wifi-failover enable-autostart' first.")
        return False

    try:
        result = subprocess.run(
            ["launchctl", "start", "com.wifi-failover.monitor"],
            capture_output=True,
            text=True
        )

        if result.returncode == 0:
            print(f"✅ Daemon started successfully!")
            print(f"   Log file: ~/.wifi-failover-logs/monitor.log")
            print()
            print("Run 'wifi-failover status' to check daemon status")
            return True
        else:
            print(f"❌ Error starting daemon: {result.stderr}")
            return False

    except Exception as e:
        print(f"❌ Error starting daemon: {e}")
        return False


def start_daemon_background():
    """Start WiFi failover monitor as a background daemon (foreground mode for testing)"""
    print_section("Starting WiFi Failover Daemon (Foreground)")

    config = Config()

    # Validate configuration
    hotspot = config.get_hotspot_ssid()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not all([hotspot, worker_url, worker_secret]):
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return False

    print(f"✓ Configuration valid")
    print(f"  Hotspot: {hotspot}")
    print()

    # Start the monitor (this blocks until interrupted)
    monitor = WiFiFailoverMonitor(
        monitored_networks=[],
        hotspot_ssid=hotspot,
        worker_url=worker_url,
        worker_secret=worker_secret
    )

    try:
        monitor.monitor_network()
    except KeyboardInterrupt:
        print("\n⏹️  Daemon stopped by user")
        return True

    return True


def start_monitor():
    """Start the WiFi failover monitor"""
    print_section("Starting WiFi Failover Monitor")

    config = Config()

    # Validate configuration
    hotspot = config.get_hotspot_ssid()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not all([hotspot, worker_url, worker_secret]):
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return False

    print(f"Hotspot: {hotspot}")
    print(f"Worker: {worker_url}")
    print()

    # Start monitor
    monitor = WiFiFailoverMonitor(
        monitored_networks=[],
        hotspot_ssid=hotspot,
        worker_url=worker_url,
        worker_secret=worker_secret
    )
    monitor.monitor_network()
    return True


def show_status():
    """Show current configuration and status"""
    print_section("WiFi Failover Utility - Status")

    config = Config()

    print("Configuration:")
    print(f"  Hotspot: {config.get_hotspot_ssid()}")
    print(f"  Worker: {config.get_worker_url()}")

    print("\nLogs:")
    log_file = Path("/tmp/wifi-failover/monitor.log")
    if log_file.exists():
        print(f"  {log_file}")
        print("\n  Latest entries:")
        with open(log_file) as f:
            lines = f.readlines()[-10:]
            for line in lines:
                print(f"  {line.rstrip()}")
    else:
        print("  No logs found. Monitor hasn't been started yet.")




def main():
    """Main CLI entry point"""
    parser = argparse.ArgumentParser(
        description="WiFi Failover Utility - Automatic failover to Android hotspot"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # Setup command
    subparsers.add_parser("setup", help="Interactive setup wizard")

    # Daemon command
    subparsers.add_parser("daemon", help="Start daemon (kills existing, runs in background)")

    # Auto-start commands
    subparsers.add_parser("enable-autostart", help="Enable auto-start on login")
    subparsers.add_parser("disable-autostart", help="Disable auto-start on login")

    # Start command
    subparsers.add_parser("start", help="Start the monitor (foreground)")

    # Status command
    subparsers.add_parser("status", help="Show configuration and status")

    args = parser.parse_args()

    # Check if configuration exists
    config = Config()
    config_exists = (
        config.get_hotspot_ssid() and
        config.get_worker_url() and
        config.get_worker_secret()
    )

    # Commands that require config
    requires_config = args.command in ("daemon", "start", "status")

    # If config missing and user tries to run daemon/start/status, prompt for setup
    if requires_config and not config_exists:
        print("\n⚠️  Configuration not found.\n")
        response = input("Run setup wizard now? (y/n): ").strip().lower()
        if response == 'y':
            setup_interactive()
            return
        else:
            print("\nRun 'wifi-failover setup' when ready to configure.\n")
            return

    if args.command == "setup":
        setup_interactive()
    elif args.command == "daemon":
        start_daemon_launchd()
    elif args.command == "enable-autostart":
        setup_launchd_autostart()
    elif args.command == "disable-autostart":
        disable_launchd_autostart()
    elif args.command == "start":
        start_monitor()
    elif args.command == "status":
        show_status()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
