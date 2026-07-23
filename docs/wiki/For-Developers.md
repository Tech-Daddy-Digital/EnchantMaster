# For Developers

Full multi-version **testing and release strategy** for agents and maintainers lives in the repo root:

→ **[AGENTS.md](https://github.com/Tech-Daddy-Digital/EnchantMaster/blob/main/AGENTS.md)** (also `AGENTS.md` in the clone)

## Repository layout

```text
src/main/          Mainline NeoForge 26.x sources
src/client21/      Client UI for ~1.21.x-style GuiGraphics
src/client26/      Client UI for 26.x GuiGraphicsExtractor APIs
ports/             Per-version Forge/NeoForge projects
dist/matrix/       Built multi-version jars
docs/wiki/         Wiki source
docs/media/        Screenshots for README and stores
AGENTS.md          Testing, ports, and release playbook
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

## Smoke test (minimum, every jar)

1. Server loads the mod (`runServer` or dedicated).  
2. `/enchantmaster web start` (console or RCON).  
3. `GET /api/health` (and catalog/players) return OK.  
4. Inventory Modify must **not** wipe lore/custom name (1.0.5+).  
5. Optional: Xvfb client boot for ports that ship in-game UI.

Details, version matrix, and Java/tooling notes: see **AGENTS.md**.

## Releases

1. Bump `mod_version` in root + **every published** port `gradle.properties`.  
2. Update `CHANGELOG.md`.  
3. Rebuild matrix jars into `dist/matrix/` with classifiers (`mc…-neoforge` / `mc…-forge`).  
4. Smoke mainline + representative ports (ideally every jar).  
5. Tag `vX.Y.Z` and push.  
6. GitHub Actions (`.github/workflows/release.yml`) attaches `dist/matrix/*` on tag push **from the commit** (jars must already be in git), **or** upload jars on a manual Release.

## Coding notes

- Prefer **optional** network payloads for client features.  
- Inventory modify should **patch** existing stacks (preserve count / nested data).  
- Always return lore/customName in inventory JSON when present (see 1.0.5).  
- Older lines need **real ports** under `ports/` — the root tree only stays green on modern NeoForge (26.x / 1.21.11-class APIs).
