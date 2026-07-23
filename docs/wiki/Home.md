# Enchant Master Wiki

Admin toolkit for forging and editing enchanted items on Minecraft servers.

**Current version:** 1.0.5 · **License:** MIT

## Pages

| Page | Topic |
|------|--------|
| [Installation](Installation.md) | Server setup, jar selection, first run |
| [Commands](Commands.md) | OP / console command reference |
| [Web UI](Web-UI.md) | Forge tab, Inventory Modify, access |
| [In-game UI](In-game-UI.md) | Client requirements and wizard steps |
| [Configuration](Configuration.md) | `enchantmaster-server.toml` |
| [Security](Security.md) | Trusted networks, proxies, audit log |
| [Versions](Versions.md) | Minecraft / Forge / NeoForge matrix |
| [Troubleshooting](Troubleshooting.md) | Common failures |
| [For Developers](For-Developers.md) | Building ports and releases |

## What it does

Enchant Master lets **operators** create custom items (enchants, attributes, name, lore) and **edit items already in player inventories**, using:

1. A **web UI** served by an embedded HTTP server (OP start/stop), and/or  
2. An **in-game UI** if the OP also installs the mod on their client.

Players **do not** need the mod to join a server that has it.

## Quick links

- [README](../../README.md)
- [Changelog](../../CHANGELOG.md)
- [Screenshots](../media/README.md)

## Publishing this wiki to GitHub

These files live in `docs/wiki/` in the main repository. To use them as a **GitHub Wiki**:

```bash
# After creating the empty wiki on GitHub (enable Wiki in repo settings)
git clone https://github.com/<you>/EnchantMaster.wiki.git
cp docs/wiki/*.md EnchantMaster.wiki/
cd EnchantMaster.wiki
git add .
git commit -m "Import Enchant Master wiki"
git push
```

Rename `Home.md` stays as the wiki landing page. Adjust image links if needed (wiki cannot always resolve `../media/` — copy media into the wiki repo or use absolute URLs from Releases/raw GitHub).
