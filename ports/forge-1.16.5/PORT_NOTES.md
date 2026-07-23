# Enchant Master — Forge 1.16.5 Port

**Version:** 1.0.4  
**Loader:** Minecraft Forge `1.16.5-36.2.42`  
**Mappings:** ForgeGradle `official` channel for 1.16.5 (MCP-style names in IDE/compile classpath: `ServerPlayerEntity`, `CompoundNBT`, `ResourceLocation` under `net.minecraft.util`, etc.)  
**Java:** toolchain 8 with a full JDK 8 (`tools.jar` required for FG5 MCP setup; system `jre8-openjdk` alone is insufficient)

## Scope

Server + embedded web UI is the supported feature set on this port.

| Feature | Status |
|--------|--------|
| `/enchantmaster web start\|stop\|status` | Yes |
| Embedded HTTP UI + static assets | Yes |
| APIs: health, stats, items, enchantments, attributes, players, forge | Yes |
| Inventory read/modify (online top-level slots + offline playerdata) | Yes (limited) |
| Item forging via NBT (enchants, name, lore, attributes) | Yes |
| Offline playerdata under `world/playerdata` | Yes |
| In-game client GUI / networking | **Not ported** |
| Nested containers / bundles / Sophisticated Backpacks | **Not ported** |
| Data component model (1.20.5+) | N/A — uses 1.16 NBT |

## API differences vs modern NeoForge ports

- **Enchantments:** `EnchantmentHelper.setEnchantments` / `EnchantedBookItem.addEnchantment` + NBT tags `Enchantments` / `StoredEnchantments` (not DataComponents).
- **Display name / lore:** `ItemStack#setHoverName`, `display.Lore` as JSON component strings.
- **Attributes:** `ItemStack#addAttributeModifier` / NBT `AttributeModifiers`. Operations: `ADDITION`, `MULTIPLY_BASE`, `MULTIPLY_TOTAL` (web still accepts modern names).
- **Registries:** `ForgeRegistries.ITEMS`, `ENCHANTMENTS`, `ATTRIBUTES`.
- **ResourceLocation:** `new ResourceLocation(ns, path)` / `ResourceLocation.tryParse` (no `ResourceLocation.parse`).
- **Config:** `ForgeConfigSpec` + `ModConfig.Type.SERVER` → `config/enchantmaster-server.toml`.
- **Events:** `FMLServerStartingEvent` / `FMLServerStoppingEvent`, `RegisterCommandsEvent`, classic `@Mod` + `FMLJavaModLoadingContext`.
- **Commands:** `CommandSourceStack#sendSuccess(Component, boolean)` (no supplier overload).
- **Text:** `new TextComponent(...)` / `new TranslatableComponent(...)`.
- **Relevant attributes from enchantments:** limited — 1.16 enchantments do not expose modern attribute-effect components; falls back to item default modifiers / full attribute list.

## Build

```bash
cd ports/forge-1.16.5
export GRADLE_OPTS=-Xmx6G
# Prefer Java 17 for Gradle 7.5 if Java 21 fails FG5
./gradlew build
```

Output jar (also copied by release process):

- `build/libs/enchantmaster-1.0.4-mc1.16.5-forge.jar`
- Dist: `/storage/mctesting/enchantMaster/dist/matrix/enchantmaster-1.0.4-mc1.16.5-forge.jar`

## Runtime

1. Install jar on a Forge 1.16.5 dedicated server (or singleplayer).
2. Start server once to generate config.
3. As OP: `/enchantmaster web start`
4. Open the printed public URL (default bind `0.0.0.0:25570`).
5. Forge items to **online** players via the web UI.

Client-only players do **not** need the mod for the web UI path.
