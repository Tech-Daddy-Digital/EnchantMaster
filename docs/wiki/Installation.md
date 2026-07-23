# Installation

## 1. Choose the correct jar

Enchant Master ships **separate jars** per Minecraft version and loader.

| Your server | Use |
|-------------|-----|
| NeoForge 26.1 / 26.2 | `enchantmaster-1.0.5.jar` or `…-mc26.1.2-neoforge.jar` |
| NeoForge 1.21.x | `enchantmaster-1.0.5-mc1.21.x-neoforge.jar` |
| NeoForge 1.20.2 / 1.20.4 / 1.20.6 | matching `…-neoforge.jar` |
| Forge 1.20.1 | `enchantmaster-1.0.5-mc1.20.1-forge.jar` |
| Forge 1.16.5 | `enchantmaster-1.0.5-mc1.16.5-forge.jar` |

Do **not** put a NeoForge jar on Forge (or the reverse).

Official multi-version builds: GitHub **Releases** assets (and eventually Modrinth/CurseForge files).

## 2. Server install

1. Install the matching Forge or NeoForge server.
2. Copy the jar into `mods/`.
3. Start the server.
4. Confirm the log contains Enchant Master ready / config loaded messages.
5. Config appears at `config/enchantmaster-server.toml` (or world serverconfig on some loaders).

## 3. Start the web UI

From the server console or as an OP in-game:

```text
/enchantmaster web start
```

Open the URL printed in chat/console (respects `publicUrl` if set).

Stop with:

```text
/enchantmaster web stop
```

## 4. Optional client install (ops only)

Install the **same version** jar on an operator’s client only if you want `/enchantmaster open`.

Normal players should **not** need the jar.

## 5. Firewall / reverse proxy

- Default bind: see config `web.host` / `web.port` (commonly `0.0.0.0:25570`).
- For public hosts: bind `127.0.0.1`, put Nginx/Caddy in front, set `publicUrl` to the HTTPS URL, and read [Security](Security.md).

## Singleplayer / integrated server

Works on integrated servers (host is OP). Prefer binding web to `127.0.0.1` if you do not need LAN access.


## Paper servers

1. Install **Paper** for your Minecraft version.
2. Put `enchantmaster-*-paper.jar` in the server `plugins/` folder (not `mods/`).
3. Restart, then as OP/console: `/enchantmaster web start`.
4. Optional in-game UI: install the matching **Fabric** client jar + Fabric API on the **player client** only.

**1.16.5 Paper is not published** — use the Forge 1.16.5 jar on Forge.
