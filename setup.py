from setuptools import setup, find_packages

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()


setup(
    name="wifi-failover-utility",
    version="0.5.0",
    author="Dhruv Anand",
    description="Automatic WiFi failover to Android hotspot with native app and lock/sleep detection",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/dhruv-anand-aintech/wifi-failover-utility",
    packages=find_packages(),
    package_data={
        "wifi_failover": ["*.plist"],
    },
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
        "textual>=0.47.0",
    ],
    entry_points={
        "console_scripts": [
            "wifi-failover=wifi_failover.cli:main",
            "wifi-failover-monitor=wifi_failover.monitor:run_monitor",
        ],
    },
)
