# Troubleshooting

## Web UI will not open

1. Confirm `/enchantmaster web status` shows running.  
2. Check `web.host` / `web.port` and firewall.  
3. If access control is on, run `web start` from the same machine/IP you browse from (or add your subnet).  
4. Look for `WEB_DENIED` in the audit log.

## “Missing ModLoader” / invalid mod file

- Wrong loader (Forge jar on NeoForge or vice versa).  
- Corrupted download — re-download from Releases.

## Players kicked when joining

Should not happen for missing Enchant Master channels (optional). If it does:

- Confirm you are on 1.0.2+ optional channel registration.  
- Check other mods’ networking.

## Inventory lore wiped after edit

Upgrade to **1.0.5+**. Older versions cleared Appearance lore on select and overwrote on Apply.

## In-game `/enchantmaster open` does nothing

- Install the mod on the **client**.  
- Ensure you are OP.  
- On some older ports, only web UI is fully supported.

## Offline player inventory empty

- Confirm `playerdata` exists for that UUID.  
- Some ports only support top-level inventory (not nested backpacks).

## Build fails (developers)

- Use the Java version required by that NeoForge/Forge line.  
- Forge 1.16.5 needs a full **JDK 8** with `tools.jar` for FG5.  
- Increase Gradle heap for NeoForm (`GRADLE_OPTS=-Xmx8G`).
