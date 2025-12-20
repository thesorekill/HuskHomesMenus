# HuskHomesMenus

**HuskHomesMenus** is a lightweight companion plugin for **HuskHomes** that enhances teleport requests with clean, intuitive confirmation menus. It replaces chat-heavy workflows with clear GUI interactions while preserving the familiar HuskHomes command experience.

Designed for modern Paper servers and proxy networks, HuskHomesMenus integrates seamlessly with HuskHomes and supports cross-server teleport requests when used in a shared database environment.

## Overview

HuskHomesMenus does **not replace** HuskHomes. Instead, it wraps and extends HuskHomes teleport request commands with player-friendly menus, request toggles, and configurable behavior.

Whether you run a single survival server or a multi-server proxy network, HuskHomesMenus improves clarity, reduces misclicks, and gives players control over how teleport requests are handled.

## Features

- ⭐ **GUI-based teleport confirmations** — Teleport requests are confirmed through clean, readable menus instead of cluttered chat messages.
- ⭐ **HuskHomes-native command wrappers** — Fully compatible with HuskHomes commands (`/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`) — no learning curve.
- ⭐ **Cross-server ready** — Supports proxy environments (Velocity or BungeeCord) when HuskHomes is configured with MySQL/MariaDB.
- ⭐ **Player skins & context awareness** — Displays requester skins and request context in menus, including across servers.
- ⭐ **Player-controlled toggles** — Allow players to opt out of requests, disable menus, or automatically accept teleport requests.
- ⭐ **Lightweight & focused** — No bloated features — HuskHomesMenus does one thing well and stays out of the way.

## Requirements

- Java 21
- Paper (or compatible forks)
- HuskHomes (required dependency)
- (Optional) Velocity or BungeeCord for proxy support
- (Optional) PlaceholderAPI

## Installation

1. Install and configure **HuskHomes**.
2. Place `HuskHomesMenus.jar` into your server’s `plugins/` directory.
3. Restart the server.
4. (Optional) Adjust menu and request behavior in `config.yml`.

## Commands

HuskHomesMenus provides GUI-enhanced wrappers and player preference toggles for HuskHomes teleport requests.

| Command | Usage | Description | Permission |
|--------|---------|------------|------------|
| `/tpa` | `/tpa <player>` | Send a teleport request to another player | `huskhomesmenus.tpa` |
| `/tpahere` | `/tpahere <player>` | Ask a player to teleport to you | `huskhomesmenus.tpahere` |
| `/tpaccept` | `/tpaccept [player]` | Accept the most recent or a specific teleport request | `huskhomesmenus.tpaccept` |
| `/tpdeny` | `/tpdeny [player]` | Deny the most recent or a specific teleport request | `huskhomesmenus.tpdeny` |
| `/tpatoggle` | `/tpatoggle` | Toggle receiving `/tpa` requests | `huskhomesmenus.tpatoggle` |
| `/tpaheretoggle` | `/tpaheretoggle` | Toggle receiving `/tpahere` requests | `huskhomesmenus.tpaheretoggle` |
| `/tpauto` | `/tpauto` | Toggle automatic acceptance of teleport requests | `huskhomesmenus.tpauto` |
| `/tpmenu` | `/tpmenu` | Toggle the teleport request confirmation menu | `huskhomesmenus.tpmenu` |

## Permissions

All permissions default to **false** unless otherwise noted and are intended for use with permission plugins such as **LuckPerms**.

| Permission | Description | Default |
|-----------|------------|---------|
| `huskhomesmenus.tpa` | Allows sending `/tpa` requests | false |
| `huskhomesmenus.tpahere` | Allows sending `/tpahere` requests | false |
| `huskhomesmenus.tpaccept` | Allows accepting teleport requests | false |
| `huskhomesmenus.tpdeny` | Allows denying teleport requests | false |
| `huskhomesmenus.tpatoggle` | Allows toggling `/tpa` request reception | false |
| `huskhomesmenus.tpaheretoggle` | Allows toggling `/tpahere` request reception | false |
| `huskhomesmenus.tpauto` | Allows toggling automatic acceptance of teleport requests | false |
| `huskhomesmenus.tpmenu` | Allows toggling the teleport request GUI menu | false |
| `huskhomesmenus.*` | Grants all HuskHomesMenus permissions | op |

> **Note:** By default, players must be explicitly granted permissions to send **and accept** teleport requests.

## Configuration

HuskHomesMenus includes a simple configuration file allowing you to control:

- Teleport confirmation menu behavior
- Automatic acceptance and denial logic
- Proxy support and cross-server messaging
- Cache behavior for remote player data

All options include safe defaults and can be adjusted without restarting the server.

## Planned Features

The following features are planned or under consideration for future releases:

- Expanded menu customization (icons, text, colors)
- Additional request context (server, world, distance)
- Visual request timeout indicators
- Improved proxy synchronization and caching
- Optional sound and particle feedback

Planned features are subject to change and will be implemented with a focus on performance, clarity, and network compatibility.

## License

HuskHomesMenus is licensed under the **Apache License, Version 2.0**.  
See the `LICENSE` file for full license details.

## Contributing

Bug reports, suggestions, and pull requests are welcome.  
Please keep contributions focused and aligned with the plugin’s lightweight design goals.

## Credits

- Built as an extension for **HuskHomes**
- Developed for use on the **Chumbucket Network**

© 2025 Chumbucket Network. Licensed under the Apache-2.0 License.
