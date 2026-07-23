# Enchant Master 1.0.5

Admin forge toolkit for Minecraft servers — **web UI** + optional **in-game UI**.

## Highlights

- **Inventory lore fix:** editing a stack no longer wipes flavor text; Appearance prefills name/lore.
- Multi-version jars: Forge **1.16.5 / 1.20.1**, NeoForge **1.20.2+ / 1.21.x / 26.x**.

## Install

1. Download **one** jar that matches your Minecraft version **and** loader.
2. Place it in the **server** `mods/` folder (client jar only needed for `/enchantmaster open`).
3. `/enchantmaster web start` as OP or console.
4. Open the URL shown in chat/console.

## Security

No web password. Use trusted networks or a reverse proxy. See the wiki **Security** page.

## Files in this release

Attach all `enchantmaster-1.0.5*.jar` from `dist/matrix/`.

| Jar pattern | Loader |
|-------------|--------|
| `…-mc1.16.5-forge.jar` | Forge 1.16.5 |
| `…-mc1.20.1-forge.jar` | Forge 1.20.1 |
| `…-mc1.20.x-neoforge.jar` | NeoForge 1.20.x |
| `…-mc1.21.x-neoforge.jar` | NeoForge 1.21.x |
| `enchantmaster-1.0.5.jar` / `…-mc26…` | NeoForge 26.x |

Full notes: [CHANGELOG.md](../CHANGELOG.md).
