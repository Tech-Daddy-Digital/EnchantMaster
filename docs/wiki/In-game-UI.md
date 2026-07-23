# In-game UI

## Requirements

- Enchant Master installed on the **server** and the **operator’s client**.  
- Operator permission.  
- Command: `/enchantmaster open`.

If the client does not have the mod, the command is not offered (or fails safely). Network payloads are optional so other players are unaffected.

## Wizard (modern NeoForge builds)

Typical multi-step flow:

1. **Choose item** — searchable list with item icons.  
2. **Enchantments** — available vs selected; override limits toggle.  
3. **Attributes** — optional modifiers.  
4. **Appearance** — name, color, flavor text.  
5. **Confirm / give** — target player where supported.

Vanilla-style widgets (no resource pack required).

## Screenshots

- `docs/media/ingame-01-item-select.png`  
- `docs/media/ingame-02-forge-overview.png`  
- `docs/media/ingame-03-enchantments.png`  
- `docs/media/ingame-04-attributes.png`  

## Port notes

Forge 1.16.5 / 1.20.1 and some early NeoForge ports prioritize **server + web**. In-game UI may be limited or stubbed — use the web UI on those versions.
