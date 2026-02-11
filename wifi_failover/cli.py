"""Interactive CLI for WiFi Failover Utility setup"""

import argparse
import subprocess
import psutil
import time
import os
from pathlib import Path
from .config import Config, get_available_networks, get_current_network
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


def get_network_selection() -> list:
    """Interactive network selection"""
    print_section("Step 1: Select Networks to Monitor")

    available = get_available_networks()
    current = get_current_network()

    if not available:
        print("⚠️  Could not detect any networks. Please enter manually.\n")
        user_input = input("Enter network names to monitor (comma-separated): ")
        return [n.strip() for n in user_input.split(',') if n.strip()]

    print(f"Available networks on this Mac:")
    for i, network in enumerate(available, 1):
        marker = " ← Currently connected" if network == current else ""
        print(f"  {i}. {network}{marker}")

    print("\nEnter network numbers to monitor (e.g., '1,2' or '1'):")
    user_input = input("> ").strip()

    selected = []
    try:
        indices = [int(x.strip()) - 1 for x in user_input.split(',')]
        for idx in indices:
            if 0 <= idx < len(available):
                selected.append(available[idx])
    except ValueError:
        print("Invalid input. Using all available networks.")
        selected = available

    print(f"\n✓ Selected networks: {selected}")
    return selected


def get_hotspot_ssid() -> str:
    """Get phone's hotspot SSID"""
    print_section("Step 2: Phone Hotspot Name")
    print("Enter your phone's hotspot SSID (the name that appears in WiFi networks):")
    print("(Example: 'Dhruv's iPhone')\n")

    ssid = input("> ").strip()
    if not ssid:
        print("❌ Hotspot name cannot be empty")
        return get_hotspot_ssid()

    print(f"\n✓ Hotspot SSID: {ssid}")
    return ssid


def get_cloudflare_credentials() -> tuple:
    """Get Cloudflare Worker credentials"""
    print_section("Step 3: Cloudflare Worker Credentials")
    print("Enter your Cloudflare Worker URL and secret.")
    print("If you don't have one deployed yet, see: https://github.com/yourusername/wifi-failover-utility/blob/main/CLOUDFLARE_SETUP.md\n")

    worker_url = input("Worker URL (e.g., https://wifi-failover.youraccount.workers.dev): ").strip()
    if not worker_url.startswith("https://"):
        print("❌ Worker URL must start with https://")
        return get_cloudflare_credentials()

    worker_secret = input("Worker Secret: ").strip()
    if not worker_secret:
        print("❌ Worker secret cannot be empty")
        return get_cloudflare_credentials()

    print(f"\n✓ Worker URL: {worker_url}")
    print(f"✓ Worker Secret: {'*' * (len(worker_secret) - 4)}{worker_secret[-4:]}")
    return worker_url, worker_secret


def save_hotspot_password(hotspot_ssid: str):
    """Save hotspot password to Keychain"""
    print_section("Step 4: Hotspot Password")
    print("The daemon needs your hotspot password to auto-connect on failover.")
    print("This will be stored securely in your Mac's Keychain.\n")

    response = input(f"Save '{hotspot_ssid}' password to Keychain? (y/n): ").strip().lower()
    if response != 'y':
        print("⚠️  Skipped. You'll need to manually add it to Keychain later.")
        return

    password = input(f"Enter '{hotspot_ssid}' WiFi password: ").strip()
    if not password:
        print("❌ Password cannot be empty")
        return save_hotspot_password(hotspot_ssid)

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

    # Step 1: Select networks
    networks = get_network_selection()
    if not networks:
        print("❌ No networks selected. Exiting.")
        return False

    # Step 2: Hotspot SSID
    hotspot_ssid = get_hotspot_ssid()

    # Step 3: Cloudflare credentials
    worker_url, worker_secret = get_cloudflare_credentials()

    # Step 4: Save hotspot password
    save_hotspot_password(hotspot_ssid)

    # Save configuration
    print_section("Saving Configuration")
    config = Config()
    config.set_monitored_networks(networks)
    config.set_hotspot_ssid(hotspot_ssid)
    config.set_worker_url(worker_url)
    config.set_worker_secret(worker_secret)
    print(f"✓ Configuration saved to: {config.config_file}")

    # Ask user if they want to start daemon now
    print_section("Start Daemon")
    response = input("Start the WiFi failover daemon now? (y/n): ").strip().lower()

    if response == 'y':
        print("\nInstalling auto-start on login...")
        setup_launchd_autostart()
        print("\nStarting daemon in background...")
        start_daemon_background()
        return True
    else:
        print_section("Setup Complete! ✓")
        print("\nTo start the daemon later, run:")
        print("  wifi-failover daemon")
        print("\nTo enable auto-start on login, run:")
        print("  wifi-failover enable-autostart")
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

    plist_source = Path(__file__).parent.parent / "launchd" / "com.wifi-failover.monitor.plist"
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


def start_daemon_background():
    """Start WiFi failover monitor as a background daemon"""
    print_section("Starting WiFi Failover Daemon")

    # Kill any existing daemons
    killed = kill_existing_daemons()
    if killed > 0:
        print(f"✓ Stopped {killed} existing daemon(s)")

    config = Config()

    # Validate configuration
    networks = config.get_monitored_networks()
    hotspot = config.get_hotspot_ssid()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not all([networks, hotspot, worker_url, worker_secret]):
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return False

    print(f"✓ Configuration valid")
    print(f"  Networks: {networks}")
    print(f"  Hotspot: {hotspot}")
    print()

    # Start daemon in background using subprocess
    try:
        # Use nohup to ensure process survives terminal close
        log_file = Path.home() / ".wifi-failover-logs" / "daemon.log"
        log_file.parent.mkdir(exist_ok=True, parents=True)

        with open(log_file, 'a') as f:
            f.write(f"\n{'='*80}\n")
            f.write(f"Daemon started at {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"{'='*80}\n")

        # Start the monitor in a subprocess
        monitor = WiFiFailoverMonitor(
            monitored_networks=networks,
            hotspot_ssid=hotspot,
            worker_url=worker_url,
            worker_secret=worker_secret
        )

        # Run in background
        import threading
        thread = threading.Thread(target=monitor.monitor_network, daemon=False)
        thread.start()

        print(f"✅ Daemon started successfully!")
        print(f"   Log file: {log_file}")
        print(f"   PID: {os.getpid()}")
        print()
        print("Run 'wifi-failover status' to check daemon status")

        # Keep the daemon running
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\n⏹️  Daemon stopped by user")
            return True

    except Exception as e:
        print(f"❌ Error starting daemon: {e}")
        return False


def start_monitor():
    """Start the WiFi failover monitor"""
    print_section("Starting WiFi Failover Monitor")

    config = Config()

    # Validate configuration
    networks = config.get_monitored_networks()
    hotspot = config.get_hotspot_ssid()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not all([networks, hotspot, worker_url, worker_secret]):
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return False

    print(f"Networks to monitor: {networks}")
    print(f"Hotspot: {hotspot}")
    print(f"Worker: {worker_url}")
    print()

    # Start monitor
    monitor = WiFiFailoverMonitor(
        monitored_networks=networks,
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
    print(f"  Networks: {config.get_monitored_networks()}")
    print(f"  Hotspot: {config.get_hotspot_ssid()}")
    print(f"  Worker: {config.get_worker_url()}")

    print("\nCurrent state:")
    current_network = get_current_network()
    print(f"  Connected to: {current_network}")

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

    # Check if configuration exists - if not and command requires it, run setup first
    config = Config()
    config_exists = (
        config.get_monitored_networks() and
        config.get_hotspot_ssid() and
        config.get_worker_url() and
        config.get_worker_secret()
    )

    requires_config = args.command in ("daemon", "start", "status", None)

    if requires_config and not config_exists and args.command != "setup":
        print("\n⚠️  WiFi Failover Utility - First Run Setup\n")
        print("No configuration found. Running setup wizard...\n")
        time.sleep(1)
        if setup_interactive():
            print("\n✅ Configuration saved! You can now run commands.\n")
        else:
            print("\n❌ Setup cancelled. Run 'wifi-failover setup' to configure later.\n")
            return

    if args.command == "setup":
        setup_interactive()
    elif args.command == "daemon":
        start_daemon_background()
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
