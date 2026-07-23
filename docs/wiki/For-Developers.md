# For Developers

## Repository layout

```text
src/main/          Mainline NeoForge 26.x sources
src/client21/      Client UI for ~1.21.x-style GuiGraphics
src/client26/      Client UI for 26.x GuiGraphicsExtractor APIs
ports/             Per-version Forge/NeoForge projects
dist/matrix/       Built multi-version jars
docs/wiki/         Wiki source
docs/media/        Screenshots for README and stores
```

## Build mainline

```bash
./gradlew build
```

## Build a port

```bash
export GRADLE_OPTS=-Xmx8G
cd ports/neoforge-1.21.1 && ./gradlew jar
cd ports/forge-1.20.1 && ./gradlew build
```

## Releases

1. Bump `mod_version` in root + port `gradle.properties`.  
2. Update `CHANGELOG.md`.  
3. Build matrix jars into `dist/matrix/`.  
4. Tag `v1.0.5` and push.  
5. GitHub Actions (`.github/workflows/release.yml`) attaches `dist/matrix/*` on tag push, **or** create a Release manually and upload jars.

## Coding notes

- Prefer **optional** network payloads for client features.  
- Inventory modify should **patch** existing stacks (preserve count / nested data).  
- Always return lore/customName in inventory JSON when present (see 1.0.5).
