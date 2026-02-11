from setuptools import setup, find_packages
from setuptools.command.install import install as _install

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()


class PostInstallCommand(_install):
    """Custom install command that runs setup wizard after installation"""

    def run(self):
        _install.run(self)

        # Run setup wizard after installation
        import sys

        # Only run if interactive terminal (not in CI/automation)
        if hasattr(sys.stdin, 'isatty') and sys.stdin.isatty():
            try:
                print("\n" + "="*80)
                print("WiFi Failover Utility - Setup Wizard")
                print("="*80 + "\n")
                print("Running interactive setup...\n")

                from wifi_failover.cli import setup_interactive
                success = setup_interactive()

                if success:
                    print("\n✅ Setup complete! Configuration saved.")
                    print("\nNext steps:")
                    print("  1. Install the Android app")
                    print("  2. The daemon will auto-start on login")
                    print("\nRun 'wifi-failover status' to check configuration\n")
                else:
                    print("\n⚠️  Setup cancelled. You can run it later with:")
                    print("  wifi-failover setup\n")

            except KeyboardInterrupt:
                print("\n\n⏹️  Setup cancelled. You can run it later with:")
                print("  wifi-failover setup\n")
            except Exception as e:
                print(f"\n⚠️  Setup wizard error: {e}")
                print("You can run setup later with: wifi-failover setup\n")
        else:
            # Non-interactive (CI/automation) - just skip
            print("Non-interactive install detected. Skipping setup wizard.")
            print("Run 'wifi-failover setup' to configure.\n")


setup(
    name="wifi-failover-utility",
    version="0.3.0",
    author="Dhruv Anand",
    description="Automatic WiFi failover to Android hotspot with native app and lock/sleep detection",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/dhruv-anand-aintech/wifi-failover-utility",
    packages=find_packages(),
    classifiers=[
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
        "Operating System :: MacOS",
        "Topic :: System :: Networking",
    ],
    python_requires=">=3.8",
    install_requires=[
        "requests>=2.28.0",
        "psutil>=5.9.0",
    ],
    entry_points={
        "console_scripts": [
            "wifi-failover=wifi_failover.cli:main",
        ],
    },
    cmdclass={
        "install": PostInstallCommand,
    },
)
