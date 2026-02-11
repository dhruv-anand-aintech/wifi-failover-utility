from setuptools import setup, find_packages
from setuptools.command.install import install as _install

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()


class PostInstallCommand(_install):
    """Custom install command that runs setup wizard after installation"""

    def run(self):
        _install.run(self)

        # Only run setup on actual pip install, not during wheel builds
        # Check if we're being run as part of a pip install (not a build)
        import os
        if "pip" in os.environ.get("_", ""):
            try:
                print("\n" + "="*80)
                print("WiFi Failover Utility - Setup Wizard")
                print("="*80 + "\n")
                print("Running interactive setup...\n")

                from wifi_failover.cli import setup_interactive
                setup_interactive()
            except Exception:
                # If setup fails, just warn but don't crash install
                print("\n⚠️  Setup wizard couldn't launch automatically.")
                print("You can run it later with: wifi-failover setup\n")


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
