from setuptools import setup, find_packages
from setuptools.command.install import install as _install

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()


class PostInstallCommand(_install):
    """Custom install command that runs setup wizard after installation"""

    def run(self):
        _install.run(self)
        print("\n" + "="*80)
        print("WiFi Failover Utility - Setup Wizard")
        print("="*80 + "\n")
        print("Running interactive setup...\n")

        # Import after installation so all dependencies are available
        from wifi_failover.cli import setup_interactive

        try:
            setup_interactive()
        except Exception as e:
            print(f"\n⚠️  Setup wizard failed: {e}")
            print("You can run setup later with: wifi-failover setup\n")


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
