# Enchant Master - NeoForge 1.20.2 port

## Target
- Minecraft **1.20.2**
- NeoForge **20.2.93**
- Java **17**
- Package `dev.enchantmaster`, modid `enchantmaster`, version `1.0.4`

## Status
**BUILD GREEN** — jar: `dist/matrix/enchantmaster-1.0.4-mc1.20.2-neoforge.jar`

## Loader / build
- NeoGradle userdev 7.0.116 (ModDevGradle 2.x lacks moddev-bundle for 20.2)
- Item data: **Classic NBT**
- Config: `ModConfigSpec` (early Neo via `ModLoadingContext.registerConfig`; later via `ModContainer.registerConfig` where available)
- Networking: stubbed (server + web primary)
- Sources adapted from working Forge **1.20.1** NBT port

## Build
```bash
export GRADLE_OPTS=-Xmx8G
./gradlew jar
```
