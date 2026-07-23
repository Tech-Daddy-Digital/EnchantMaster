# Changelog

All notable changes to **Enchant Master** are documented here.

Format roughly follows [Keep a Changelog](https://keepachangelog.com/).

## [1.0.5] — 2026-07-23

### Fixed
- Inventory Modify no longer wipes item **flavor text / lore** on Apply.
- Inventory API now returns `customName` and `lore[]` so Appearance fields prefill correctly.
- Per-line lore styles are preserved when style controls are left unchanged.

### Ports
- Rebuilt Forge/NeoForge jars for 1.16.5, 1.20.x, and 1.21.x families as **1.0.5**.
- **Paper** server plugins for 26.1.2, 1.21.x, and 1.20.1–1.20.6 (`…-paper.jar`).
- Optional **Fabric client** for Paper in-game UI (`…-fabric-client.jar`, 26.1.2 primary).
- Paper **1.16.5 deferred** (Forge-only special line).

## [1.0.4] — 2026-07-23

### Added
- Multi-version matrix builds (NeoForge 1.21.11 / 26.x green path).
- Real ports for NeoForge 1.20.2–1.21.8 families and Forge 1.16.5 / 1.20.1.
- Audit log (`logs/enchantmaster-audit.log`) with OP ↔ IP correlation.
- Web IP access control (player whitelist, same-LAN, configured subnets).
- `/enchantmaster open` only available when client has the mod.

### Changed
- Server-only deploy is the default multiplayer model (optional client for in-game UI).

## [1.0.3] — earlier

### Added
- Audit logging for forge / inventory modify / web start-stop.

## [1.0.2] — earlier

### Added
- Config schema upgrades that preserve existing user keys.
- `publicUrl` and improved access control options.

## [1.0.0] — initial

### Added
- Web UI (Forge + Inventory Modify).
- OP-controlled embedded HTTP server.
- In-game forge wizard (client optional).
- Registry-driven items/enchants/attributes for multi-mod packs.
