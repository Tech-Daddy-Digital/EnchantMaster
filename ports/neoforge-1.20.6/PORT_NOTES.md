# Enchant Master - NeoForge 1.20.6 port

## Target
- Minecraft **1.20.6**
- NeoForge **20.6.139**
- Java **21**
- Package `dev.enchantmaster`, modid `enchantmaster`, version `1.0.4`

## Status
**BUILD GREEN** — jar: `dist/matrix/enchantmaster-1.0.4-mc1.20.6-neoforge.jar`

## Loader / build
- ModDevGradle 2.0.78
- Item data: **Data components**
- Config: `ModConfigSpec` (early Neo via `ModLoadingContext.registerConfig`; later via `ModContainer.registerConfig` where available)
- Networking: stubbed (server + web primary)
- Sources adapted from working Forge **1.20.1** NBT port

## Build
```bash
export GRADLE_OPTS=-Xmx8G
./gradlew jar
```
