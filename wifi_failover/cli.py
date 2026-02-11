"""Interactive CLI for WiFi Failover Utility setup"""

import argparse
import requests
from pathlib import Path
from .config import Config, get_available_networks, get_current_network
from .tasker_instructions import save_tasker_instructions, get_tasker_setup_guide
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

    # Generate Tasker instructions
    print_section("Tasker Setup Instructions")
    print("Generating Tasker setup guide for your phone...\n")

    output_path = Path.home() / "Desktop" / "TASKER_SETUP.txt"
    save_tasker_instructions(worker_url, worker_secret, str(output_path))
    print(f"\n📱 Open this file on your phone: {output_path}")
    print("   Then follow the step-by-step instructions to configure Tasker.")

    # Summary
    print_section("Setup Complete! 🎉")
    print("Next steps:")
    print(f"  1. Copy TASKER_SETUP.txt to your phone")
    print(f"  2. Follow the instructions to set up Tasker")
    print(f"  3. Install the daemon:")
    print(f"     sudo wifi-failover install-daemon")
    print(f"  4. Start monitoring:")
    print(f"     wifi-failover status")

    return True


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


def show_tasker_guide():
    """Show Tasker setup guide"""
    config = Config()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not worker_url or not worker_secret:
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return

    print(get_tasker_setup_guide(worker_url, worker_secret))


def test_failover():
    """Test the failover system without actual network failure"""
    config = Config()
    worker_url = config.get_worker_url()
    worker_secret = config.get_worker_secret()

    if not worker_url or not worker_secret:
        print("❌ Configuration incomplete. Run 'wifi-failover setup' first.")
        return

    print_section("Testing WiFi Failover System")
    print()
    print("Step 1: Sending failover command to Worker...")

    try:
        response = requests.post(
            f"{worker_url}/api/command/enable",
            json={"secret": worker_secret},
            timeout=10
        )
        if response.status_code == 200:
            print("✅ Failover command sent to Worker successfully")
            print()
            print("Step 2: Check your Android app debug log...")
            print("   - Open the WiFi Failover app")
            print("   - Look at the Debug Log at the bottom")
            print("   - You should see: 'Poll: enabled=true'")
            print()
            print("Step 3: App should automatically enable hotspot...")
            print("   - If Device Admin is enabled, hotspot should turn on")
            print("   - You'll see a notification: 'Hotspot enabled - connect your computer'")
            print()
            print("Step 4: Daemon will connect to hotspot (requires password in Keychain)")
            print()
            print(f"Worker: {worker_url}")
            print(f"Hotspot SSID: {config.get_hotspot_ssid()}")
        else:
            print(f"❌ Failed to send command: {response.status_code}")
    except Exception as e:
        print(f"❌ Error: {e}")


def main():
    """Main CLI entry point"""
    parser = argparse.ArgumentParser(
        description="WiFi Failover Utility - Automatic failover to Android hotspot"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # Setup command
    subparsers.add_parser("setup", help="Interactive setup wizard")

    # Start command
    subparsers.add_parser("start", help="Start the monitor (foreground)")

    # Status command
    subparsers.add_parser("status", help="Show configuration and status")

    # Tasker guide command
    subparsers.add_parser("tasker-guide", help="Show Tasker setup instructions")

    # Test failover command
    subparsers.add_parser("test-failover", help="Test failover without network failure")

    args = parser.parse_args()

    if args.command == "setup":
        setup_interactive()
    elif args.command == "start":
        start_monitor()
    elif args.command == "status":
        show_status()
    elif args.command == "tasker-guide":
        show_tasker_guide()
    elif args.command == "test-failover":
        test_failover()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
