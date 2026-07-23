# Enchant Master — Forge 1.20.1 port

## Target
- Minecraft **1.20.1**
- Forge **1.20.1-47.4.22**
- Java **17**
- Package `dev.enchantmaster`, modid `enchantmaster`, version `1.0.4`

## Loader / build
- ForgeGradle 6 (`net.minecraftforge.gradle` `[6.0,6.2)`)
- Official Mojang mappings
- `META-INF/mods.toml` with `modLoader=javafml`, Forge + Minecraft deps
- `displayTest=IGNORE_SERVER_VERSION` so dedicated servers can run without client jar

## API choices (vs NeoForge 1.21.1 template)

| Area | NeoForge 1.21.1 | This port (Forge 1.20.1) |
|------|-----------------|---------------------------|
| Loader API | `net.neoforged.*` | `net.minecraftforge.*` |
| Mod entry | `@Mod` + `ModContainer` ctor | `@Mod` + `FMLJavaModLoadingContext` |
| Config | `ModConfigSpec` | `ForgeConfigSpec` |
| Game bus | `NeoForge.EVENT_BUS` | `MinecraftForge.EVENT_BUS` |
| Item data | Data components | Classic **NBT** (`Enchantments`, `display.Name`/`Lore`, `AttributeModifiers`) |
| Enchant registry | Datapack holders / `Registries.ENCHANTMENT` | `BuiltInRegistries.ENCHANTMENT` |
| IDs | `Identifier` (transformed) | `ResourceLocation` |
| Profiles | `GameProfile.name()` | `GameProfile.getName()` |
| Offline stacks | `ItemStack.parse` / `save(registryAccess)` | `ItemStack.of` / `save(CompoundTag)` |
| Networking | Custom payload codec API | `SimpleChannel` (optional client) |
| Attribute ops | `ADD_VALUE` / multiplied | Mapped to `ADDITION` / `MULTIPLY_*`; API still exposes modern names |
| Equipment slots | `EquipmentSlotGroup` | `EquipmentSlot` + NBT `"Slot"` / omit for any |

## Feature status
- **Server + web UI**: full (HTTP, access control, catalog, forge, inventory online/offline, audit log, commands)
- **In-game client forge screen**: network channel registered; GUI classes not shipped in this first jar (web is primary). `/enchantmaster open` only appears when the client has negotiated the channel.

## Build / jar
```bash
export GRADLE_OPTS=-Xmx8G
./gradlew build
# jar: build/libs/enchantmaster-1.0.4-mc1.20.1-forge.jar
```
