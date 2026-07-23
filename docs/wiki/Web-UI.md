# Web UI

The web UI is the primary admin interface for most multiplayer servers.

## Starting

```text
/enchantmaster web start
```

Default listen: host/port from config (often `0.0.0.0:25570`). Status line shows online item/enchant counts when the server is ready.

## Tabs

### Forge

1. **Item** — search registry items; filter by mod namespace.  
2. **Enchantments** — multi-line picker with flavor text; override toggle for illegal combos / over-level.  
3. **Attributes** — relevant modifiers for the item (+ enchant-granted where supported).  
4. **Appearance** — custom name, colors, bold/italic, multi-line flavor text.  
5. **Live preview** — Minecraft-style tooltip.  
6. **Give** — choose an online player and forge.

### Inventory Modify

1. Pick a player (online or offline).  
2. Select a stack path (inventory, armor, ender, nested when supported).  
3. Edit enchants / attributes / name / lore.  
4. **Apply to inventory** patches the existing stack (preserves count and, on modern ports, nested container data).

**1.0.5+:** existing lore and custom names are loaded into Appearance so Apply does not wipe flavor text.

## Screenshots

- `docs/media/web-01-forge-overview.png`  
- `docs/media/web-02-inventory-modify.png`  

## Access control

When `accessControl = true` (default):

- Only whitelisted player IPs (from `web start`), optional same-LAN, configured subnets, and localhost may load the UI.  
- Denied requests are audited as `WEB_DENIED`.

There is **no password**. Treat the port as an admin-only service.
