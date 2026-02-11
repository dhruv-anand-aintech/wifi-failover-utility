#!/usr/bin/env python3
"""
WiFi Failover Utility - Interactive Setup Script

Run with: uv run wifi_failover_setup.py
or:       python wifi_failover_setup.py
"""

# /// script
# requires-python = ">=3.8"
# dependencies = [
#     "requests>=2.28.0",
#     "psutil>=5.9.0",
# ]
# ///

import sys
import subprocess
from pathlib import Path


def main():
    """Run WiFi Failover setup"""

    print("\n" + "="*80)
    print("WiFi Failover Utility - Setup")
    print("="*80 + "\n")

    # Import from local wifi_failover package
    try:
        from wifi_failover.cli import setup_interactive, start_daemon_background
    except ImportError:
        print("❌ Error: Could not import wifi_failover module")
        print("\nMake sure you're running this in the wifi-failover-utility directory:")
        print("  cd wifi-failover-utility")
        print("  uv run wifi_failover_setup.py")
        sys.exit(1)

    # Run setup wizard
    print("Step 1: Interactive Configuration\n")
    if not setup_interactive():
        print("\n❌ Setup cancelled.")
        sys.exit(1)

    # Ask if user wants to start daemon
    print("\n" + "="*80)
    print("Step 2: Start Daemon")
    print("="*80 + "\n")

    response = input("Start the WiFi failover daemon now? (y/n): ").strip().lower()
    if response == 'y':
        print("\nEnabling auto-start on login...\n")

        try:
            from wifi_failover.cli import setup_launchd_autostart
            setup_launchd_autostart()
        except Exception as e:
            print(f"⚠️  Could not enable auto-start: {e}")

        print("\nStarting daemon in background...\n")
        try:
            start_daemon_background()
        except KeyboardInterrupt:
            print("\n\n⏹️  Daemon startup cancelled.")
            sys.exit(0)
    else:
        print("\n✅ Configuration saved!")
        print("\nTo start the daemon later, run:")
        print("  wifi-failover daemon")
        print("\nOr run in foreground:")
        print("  wifi-failover start")
        sys.exit(0)


if __name__ == "__main__":
    main()
