#!/usr/bin/env python3
"""
Interactive TUI for reordering macOS WiFi network priorities.
"""

import subprocess
import sys
from typing import List

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Vertical
from textual.widgets import Footer, Header, ListItem, ListView, Static


class NetworkListItem(ListItem):
    """A list item that displays a WiFi network name."""

    def __init__(self, network_name: str) -> None:
        super().__init__()
        self.network_name = network_name
        self.label = Static(f"  {network_name}", classes="network-label")

    def compose(self) -> ComposeResult:
        yield self.label


class WiFiReorderApp(App):
    """TUI app for reordering WiFi network priorities."""

    CSS = """
    Screen {
        background: $surface;
    }

    #header-text {
        width: 100%;
        content-align: center middle;
        padding: 1;
        background: $primary;
        color: $text;
    }

    #instructions {
        width: 100%;
        padding: 1;
        background: $panel;
        color: $text;
        border: solid $primary;
    }

    ListView {
        height: 1fr;
        border: solid $accent;
        margin: 1;
    }

    ListItem {
        padding: 0 1;
    }

    ListItem:hover {
        background: $accent 30%;
    }

    ListItem.-active {
        background: $accent;
        color: $text;
    }

    .network-label {
        width: 100%;
    }

    #status {
        width: 100%;
        height: 3;
        padding: 1;
        background: $panel;
        color: $warning;
    }
    """

    BINDINGS = [
        Binding("ctrl+up,k", "move_up", "Move Up", show=True),
        Binding("ctrl+down,j", "move_down", "Move Down", show=True),
        Binding("s", "save", "Save & Exit", show=True),
        Binding("q", "quit", "Quit", show=True),
    ]

    def __init__(self, networks: List[str]):
        super().__init__()
        self.networks = networks.copy()
        self.original_networks = networks.copy()
        self.interface = "en0"  # Default WiFi interface

    def compose(self) -> ComposeResult:
        """Create child widgets."""
        yield Header()
        yield Static(
            "🛜  WiFi Network Priority Manager",
            id="header-text"
        )
        yield Static(
            "📋 Drag networks to change priority (higher = preferred)\n"
            "   Use ↑↓ or k/j to select, Ctrl+↑↓ to move, 's' to save, 'q' to quit",
            id="instructions"
        )

        list_view = ListView(id="network-list")
        list_view.border_title = "WiFi Networks (Priority Order)"
        yield list_view

        yield Static("", id="status")
        yield Footer()

    def on_mount(self) -> None:
        """Populate the list when app starts."""
        list_view = self.query_one("#network-list", ListView)

        for network in self.networks:
            list_view.append(NetworkListItem(network))

        self.update_status()

    def update_status(self) -> None:
        """Update the status message."""
        status = self.query_one("#status", Static)

        if self.networks != self.original_networks:
            status.update("⚠️  Changes not saved! Press 's' to save or 'q' to quit without saving.")
        else:
            status.update("✅ No changes made.")

    def action_move_up(self) -> None:
        """Move the selected network up in priority."""
        list_view = self.query_one("#network-list", ListView)

        if list_view.index is None or list_view.index == 0:
            return

        idx = list_view.index

        # Swap in networks list
        self.networks[idx], self.networks[idx - 1] = (
            self.networks[idx - 1],
            self.networks[idx]
        )

        # Rebuild list view
        self._rebuild_list()

        # Move selection up
        list_view.index = idx - 1
        self.update_status()

    def action_move_down(self) -> None:
        """Move the selected network down in priority."""
        list_view = self.query_one("#network-list", ListView)

        if list_view.index is None or list_view.index >= len(self.networks) - 1:
            return

        idx = list_view.index

        # Swap in networks list
        self.networks[idx], self.networks[idx + 1] = (
            self.networks[idx + 1],
            self.networks[idx]
        )

        # Rebuild list view
        self._rebuild_list()

        # Move selection down
        list_view.index = idx + 1
        self.update_status()

    def _rebuild_list(self) -> None:
        """Rebuild the list view with current network order."""
        list_view = self.query_one("#network-list", ListView)
        list_view.clear()

        for network in self.networks:
            list_view.append(NetworkListItem(network))

    def action_save(self) -> None:
        """Save the new network order and exit."""
        if self.networks == self.original_networks:
            self.exit(message="No changes to save.")
            return

        try:
            # Apply the new priority order
            self._apply_network_priority()
            self.exit(message="✅ WiFi network priorities updated successfully!")
        except Exception as e:
            self.exit(message=f"❌ Error saving priorities: {e}")

    def _apply_network_priority(self) -> None:
        """Apply the new network priority order using networksetup."""
        # Remove all networks
        for network in self.original_networks:
            subprocess.run(
                ["sudo", "networksetup", "-removepreferredwirelessnetwork",
                 self.interface, network],
                check=False,  # Don't fail if network doesn't exist
                capture_output=True
            )

        # Add networks in reverse order (last added = highest priority)
        for network in reversed(self.networks):
            result = subprocess.run(
                ["sudo", "networksetup", "-addpreferredwirelessnetworkatindex",
                 self.interface, network, "0", "WPA2"],
                capture_output=True,
                text=True
            )

            if result.returncode != 0:
                raise Exception(f"Failed to add network '{network}': {result.stderr}")


def get_preferred_networks(interface: str = "en0") -> List[str]:
    """Get the list of preferred WiFi networks in priority order."""
    result = subprocess.run(
        ["networksetup", "-listpreferredwirelessnetworks", interface],
        capture_output=True,
        text=True,
        check=True
    )

    lines = result.stdout.strip().split("\n")

    # Skip the header line
    if lines[0].startswith("Preferred networks"):
        lines = lines[1:]

    # Strip leading/trailing whitespace and tabs from each network name
    networks = [line.strip() for line in lines if line.strip()]

    return networks


def main():
    """Main entry point."""
    try:
        # Get current network priority list
        networks = get_preferred_networks()

        if not networks:
            print("❌ No preferred WiFi networks found.")
            print("Connect to some WiFi networks first, then try again.")
            sys.exit(1)

        # Run the TUI app
        app = WiFiReorderApp(networks)
        result = app.run()

        # Print result message
        if result:
            print(result)

    except subprocess.CalledProcessError as e:
        print(f"❌ Error getting WiFi networks: {e}")
        print("Make sure you're running on macOS with WiFi enabled.")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n\n👋 Cancelled.")
        sys.exit(0)
    except Exception as e:
        print(f"❌ Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
