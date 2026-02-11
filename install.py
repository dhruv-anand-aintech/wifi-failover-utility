#!/usr/bin/env python3
"""
WiFi Failover Utility - Remote Setup Script

Run with: uv run https://raw.githubusercontent.com/dhruv-anand-aintech/wifi-failover-utility/main/install.py
"""

# /// script
# requires-python = ">=3.8"
# dependencies = [
#     "requests>=2.28.0",
#     "psutil>=5.9.0",
# ]
# ///

import subprocess
import sys


def main():
    """Download and run WiFi Failover setup"""

    print("\n" + "="*80)
    print("WiFi Failover Utility - Setup & Installation")
    print("="*80 + "\n")

    print("Installing WiFi Failover utility from GitHub...\n")

    # Install the package from git
    try:
        subprocess.run(
            [
                sys.executable, "-m", "pip", "install", "-q",
                "git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git"
            ],
            check=True
        )
    except subprocess.CalledProcessError as e:
        print(f"❌ Error installing package: {e}")
        print("\nTry installing manually:")
        print("  pip install git+https://github.com/dhruv-anand-aintech/wifi-failover-utility.git")
        sys.exit(1)

    print("✅ Installation successful!\n")

    # Now run setup
    try:
        from wifi_failover.cli import setup_interactive, setup_launchd_autostart, start_daemon_background

        print("\n" + "="*80)
        print("WiFi Failover Utility - Interactive Setup")
        print("="*80 + "\n")

        if not setup_interactive():
            print("\n❌ Setup cancelled.")
            sys.exit(1)

        # Ask to start daemon
        print("\n" + "="*80)
        print("Start Daemon")
        print("="*80 + "\n")

        response = input("Start the WiFi failover daemon now? (y/n): ").strip().lower()
        if response == 'y':
            print("\nEnabling auto-start on login...\n")
            try:
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

    except Exception as e:
        print(f"❌ Error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
