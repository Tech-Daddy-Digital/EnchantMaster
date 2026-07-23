# AGENTS.md — Enchant Master

Guidance for AI agents and humans working in this repo. **Read this before changing ports, running tests, or cutting a release.**

Public repo: [Tech-Daddy-Digital/EnchantMaster](https://github.com/Tech-Daddy-Digital/EnchantMaster)

---

## What this mod is

**Enchant Master** is a **server admin toolkit** for Minecraft:

- **Forge** custom items (enchants, attributes, name, lore) and **give** to players
- **Inventory Modify** (online + offline playerdata), including lore/custom name persistence
- **Web UI** (embedded HTTP; **OP-started only**, **no login**)
- **Optional client** for in-game forge wizard (`/enchantmaster open`)
- Server-only deploy is the default: normal players need nothing

Mod id: `enchantmaster` · Package: `dev.enchantmaster` · License: MIT

---

## Repo layout

```text
src/main/java/          Mainline sources (NeoForge 26.x + modern APIs)
src/client26/           Client UI for 26.x (GuiGraphicsExtractor-style)
src/client21/           Client UI for ~1.21.x GuiGraphics
ports/                  Independent Gradle projects per MC + loader family
dist/matrix/            Release jars (committed when releasing)
docs/wiki/              Wiki source (push with push-wiki.sh)
docs/media/             Banner, logo, real Xvfb/web screenshots
docs/store/             CurseForge / Modrinth drafts — **gitignored, local only**
.github/workflows/      Tag release attaches dist/matrix jars
gradle.properties       Mainline mod_version + NeoForge 26.x pins
AGENTS.md               This file
```

**Do not commit:** `run/`, `runs/`, `**/build/`, `docs/store/`, internal matrix logs, or old `1.0.4` jars.  
Local tooling under `scripts/` may exist for matrix automation; it is **gitignored** (not part of the public mod). Prefer documenting commands here so agents can work without those scripts.

---

## Version model

| Concept | Meaning |
|--------|---------|
| **Mod version** | Feature release, e.g. `1.0.5` — bump in root + every port `gradle.properties` |
| **Jar classifier** | Minecraft + loader, e.g. `mc1.21.1-neoforge`, `mc1.20.1-forge` |
| **Mainline** | Root project: NeoForge **26.1.x** (default in `gradle.properties`) |
| **Port** | `ports/<loader>-<mc>/` with its own Gradle wrapper and sources |

**Always match Minecraft version + loader** (Forge vs NeoForge). Wrong loader = mod will not load.

### Supported product lines (current 1.0.5)

| MC | Loader | Where | Java | Item data model |
|----|--------|-------|------|-----------------|
| **26.1.2** (primary) | NeoForge | root `src/` | **25** | Data components |
| 1.21.8, 1.21.5, 1.21.4, 1.21.3, 1.21.1, 1.21 | NeoForge | `ports/neoforge-1.21.*` | **21** | Data components / Holder registries |
| 1.20.6 | NeoForge | `ports/neoforge-1.20.6` | **21** | Data components |
| 1.20.4, 1.20.2 | NeoForge | `ports/neoforge-1.20.*` | **17** | Classic **NBT** |
| 1.20.1 | **Forge** | `ports/forge-1.20.1` | **17** | Classic NBT |
| 1.16.5 | **Forge** | `ports/forge-1.16.5` | **8** (full JDK 8) | Classic NBT |
| **26.1.2** (primary Paper) | **Paper** plugin | `ports/paper-26.1.2` | **25** | Bukkit/Paper API |
| 1.21–1.21.11 | **Paper** | `ports/paper-1.21*` | **21** | Bukkit/Paper API |
| 1.20.1–1.20.6 | **Paper** | `ports/paper-1.20*` | **17–21** | Bukkit/Paper API |
| 26.1.2 | **Fabric client** (optional) | `ports/fabric-client-26.1.2` | **25** | In-game UI for Paper servers |

### Unavailable by design

| Target | Why |
|--------|-----|
| 1.16.5 NeoForge | NeoForge does not exist |
| **1.16.5 Paper / Fabric** | **Deferred.** 1.16.5 stays a **special Forge-only** line. Future Paper 1.16.5 is its own family (not a clone of paper-26.1.2). Document in matrix reports. |
| 1.20.0 / 1.20.1 NeoForge | NeoForge starts at **1.20.2** → use Forge 1.20.1 |
| 26.0.x NeoForge | First 26.x NeoForge line is **26.1.0** |
| Some minor 1.21.x without a port dir | Prefer nearest dedicated jar or add a real port — do **not** claim support without a build |

### Port families (how work is organized)

1. **Mainline matrix (root project)** — NeoForge **1.21.11** and **26.x** share the main source tree. Root `build.gradle` can override `-Pminecraft_version`, `-Pneo_version`, `-Pmatrix_jar_classifier`, `-Pmatrix_run_dir` and switches:
   - `client26` vs `client21` from NeoForge major
   - `Identifier` vs `ResourceLocation` rewrite for pre-21.11 / non-26 lines
   - Java toolchain 25 for 26.x, else 21
2. **NeoForge 1.21.0–1.21.10 ports** — separate MDKs under `ports/neoforge-1.21.*`. Many share transforms (Identifier→ResourceLocation, registry Holder modes) and **hand overrides** for inventory/network/client.
3. **NeoForge 1.20.2–1.20.6** — NBT era (1.20.2/4) vs components (1.20.6); early Neo may need `META-INF/mods.toml` **and** `neoforge.mods.toml`.
4. **Forge 1.20.1 / 1.16.5** — fully separate APIs (`net.minecraftforge.*`, FG6 / FG5). Read each `ports/*/PORT_NOTES.md`.
5. **Paper plugins** — `ports/paper-<mc>/` (server-only install). Web UI + forge + inventory. Optional **Fabric client** (`ports/fabric-client-<mc>/`) for `/enchantmaster open` via plugin messaging. No Fabric required on the server.
6. **Paper matrix harness** — `tools/paper-matrix/paper_matrix_test.py` (Fill download → boot → RCON web start → HTTP + forge + offline inv).

When adding a new MC minor: **clone the nearest working port family**, adjust `gradle.properties` versions, fix compile errors, then run the **smoke loop** below. Do not paper over API breaks by skipping runtime tests.

---

## Build strategy

### Mainline (primary development)

```bash
# Java 25 toolchain required for NeoForge 26
./gradlew build
# jar → build/libs/enchantmaster-<mod_version>.jar
# also copy/rename into dist/matrix/ for releases, e.g.:
#   enchantmaster-1.0.5.jar
#   enchantmaster-1.0.5-mc26.1.2-neoforge.jar
```

Isolated run dirs (avoid stomping default `run/`):

```bash
./gradlew runServer -Pmatrix_run_dir=runs/matrix-26.1.2
./gradlew runClient -Pmatrix_run_dir=runs/matrix-26.1.2
```

Optional media capture (live screenshots only — **no mockups** for store assets):

```bash
# Xvfb + client quick-play example
./gradlew runClient -PmediaQuickPlay="New World"
```

### Root multi-version NeoForge experiment (historical matrix)

Root can retarget NeoForge with Gradle properties (used by the old matrix runner):

```bash
./gradlew jar \
  -Pminecraft_version=26.1.2 \
  -Pminecraft_version_range='[26.1.2]' \
  -Pneo_version=26.1.2.84 \
  -Pmatrix_jar_classifier=mc26.1.2-neoforge \
  -Pmatrix_run_dir=runs/matrix-26.1.2
```

**Reality check:** only **1.21.11 + 26.x** stayed green on the **shared mainline tree**. Older NeoForge lines need **real ports** under `ports/` (build fails or runtime crashes if forced through root alone).

### Ports

```bash
export GRADLE_OPTS=-Xmx8G
cd ports/neoforge-1.21.1 && ./gradlew jar
cd ports/forge-1.20.1 && ./gradlew build
# Copy resulting jar to repo root:
#   dist/matrix/enchantmaster-<mod_version>-mc<ver>-<loader>.jar
```

Naming convention for release assets:

```text
enchantmaster-1.0.5.jar                              # mainline default (26.1.2)
enchantmaster-1.0.5-mc26.1.2-neoforge.jar
enchantmaster-1.0.5-mc1.21.1-neoforge.jar
enchantmaster-1.0.5-mc1.20.1-forge.jar
enchantmaster-1.0.5-mc1.16.5-forge.jar
```

Keep **only current mod version** jars under `dist/matrix/` for publish (gitignore still drops `*1.0.4*`).

---

## Testing strategy (required for “done”)

Do **not** ship a jar that only compiled. Every version that claims support should pass the smoke loop appropriate to its feature set.

### Smoke loop (server + web) — **minimum bar for all ports**

1. **Build** succeeds (`./gradlew jar` or `build`).
2. **`runServer`** (or equivalent dedicated run) loads the mod — log contains ready / config loaded (e.g. “Enchant Master ready”).
3. Start web UI as console/OP:
   - `/enchantmaster web start`
   - Prefer **RCON** when automating so the process is non-interactive.
4. **HTTP checks** (port from config; default often `25570`, sometimes overridden e.g. `25710`):
   - `GET /api/health` → ok / `serverReady`
   - `GET /api/items` or catalog endpoint → non-empty when registries ready
   - `GET /api/players` → 200
   - `GET /` (index) → 200 HTML
5. **Functional forge** (when safe on a disposable world): forge a simple item (e.g. diamond sword + Sharpness) and confirm give/inventory path works.
6. **Inventory Modify lore** (regression for **1.0.5+**):
   - Item with custom name + multi-line lore
   - Modify via API/UI → **lore and name must not wipe**
   - Inventory JSON must return `customName` and `lore[]` for prefill

### Client smoke (mainline + ports that ship in-game UI)

1. **Xvfb** (headless display) + `runClient` (or packaged client with the mod).
2. Mod reaches main menu / joins a world without crash.
3. If networking is implemented: OP with client mod can `/enchantmaster open` and walk the wizard.
4. **Server-only join:** client **without** the mod can still join a server that has the mod.

### Mainline NeoForge 26.x / 1.21.11 matrix (full)

Historically validated stages per MC+NeoForge pair:

| Stage | Pass criteria |
|-------|----------------|
| **Build** | `jar`/`build` succeeds; artifact copied to `dist/matrix/` |
| **Server/Web** | Dedicated server + RCON web start + HTTP APIs |
| **Client (Xvfb)** | Client boots with mod under virtual display |

Green path established for: **1.21.11**, **26.1.0**, **26.1.1**, **26.1.2**, **26.2.0** (on mainline tree). Older lines failed mainline backport → **ports/**.

### Automation notes

- Prefer isolated `runs/matrix-<ver>/` game directories.
- Free ports before re-runs (`25565`/`25566` game, RCON, web `25570`/`25710`).
- EULA must be accepted in the run dir.
- For Forge 1.16.5: full **JDK 8** (`tools.jar`); JRE-only toolchains fail FG5 MCP setup.
- Early NeoForge FML often requires `modLoader` / `loaderVersion` in mods.toml — “Missing ModLoader” at runtime means fix metadata, not Java code.
- Do **not** invent store screenshots; capture from live Xvfb client or live web (Playwright against started web UI).

### Regression priorities

1. Inventory Modify **lore / customName** persistence and API prefill  
2. Web starts **only** via OP/console; access control + audit still fire  
3. Catalog is **registry-driven** (works with other mods present)  
4. Offline playerdata edit still works after API changes  
5. Port-specific: NBT vs data components, `ResourceLocation` vs `Identifier`, Holder registries  

---

## Release strategy

### When to cut a release

- Feature or fix is complete on **mainline** first.
- Bump `mod_version` everywhere that ships a jar (root + all ports you publish).
- Rebuild **all** published matrix jars for that mod version (users download by MC line).
- Update `CHANGELOG.md` under `## [x.y.z]`.
- Smoke at least: **mainline 26.x** full loop + **one Forge** port + **one older NeoForge** port; ideally every jar in `dist/matrix/`.

### GitHub release

1. Commit sources + `dist/matrix/enchantmaster-<ver>*.jar` + changelog/docs.
2. Tag: `git tag -a v1.0.5 -m "Enchant Master 1.0.5"` → push tag.
3. `.github/workflows/release.yml` creates a GitHub Release from the tag and uploads `dist/matrix/enchantmaster-*.jar` **from that commit** (jars must already be in the tree; CI does not currently build the full matrix).
4. Release notes: changelog section + “download the jar matching Minecraft + loader”.

### Wiki

- Source of truth: `docs/wiki/*.md`
- After first GitHub wiki page exists: `./push-wiki.sh`
- **Do not** put meta “how to publish this wiki” sections in end-user wiki pages.

### CurseForge / Modrinth

- Drafts and checklists live in **`docs/store/`** (local only, gitignored) — keep store-specific packaging out of the public GitHub tree unless the user asks to publish them.
- Project **logo**: `docs/media/logo-enchant-master.png` (512×512).
- Banner: `docs/media/banner-enchant-master.jpg`.
- Upload **each** MC+loader jar with correct game versions; client optional, server required.
- Publishing checklist: `docs/store/publishing-checklist.md` (local).

### Branch protection

- `main` may be protected (PR required; admins can bypass). Prefer PRs for risky multi-port work.

---

## Coding rules that affect ports

- Inventory modify **patches** existing stacks (preserve count, nested NBT/components, backpacks where supported).
- Always serialize **customName + lore** into inventory API responses (1.0.5 fix).
- Client networking is **optional**; server must run without client mod.
- `/enchantmaster open` only when client negotiated the mod channel.
- Prefer reflection/`PermissionHelper` for OP checks where APIs differ.
- Web UI: no authentication by design — document security; IP filters + audit log.
- When porting: read `ports/<name>/PORT_NOTES.md` and keep it updated with feature gaps (e.g. 1.16.5 has no in-game GUI).

### API eras (cheat sheet)

| Era | IDs | Items/enchants | Notes |
|-----|-----|----------------|-------|
| 26.x / 21.11+ mainline | `Identifier` | Data components, modern networking | Primary development |
| NeoForge ~1.21.0–1.21.10 | `ResourceLocation` | Holders / getOptional patterns | Port overrides + transforms |
| NeoForge 1.20.6 | `ResourceLocation` | Data components | Java 21 |
| NeoForge 1.20.2–1.20.4 | `ResourceLocation` | **NBT** | Java 17; early mods.toml quirks |
| Forge 1.20.1 | `ResourceLocation` | **NBT**, Forge bus | Java 17 |
| Forge 1.16.5 | MCP names in places | **NBT**, ForgeRegistries | Java 8; web-only feature set |

---

## What “support all versions” means in practice

1. **Mainline** stays on current NeoForge 26.x — ship quality and new features here first.  
2. **Matrix** on mainline for 26.x (and 1.21.11 if still relevant): build + server/web + Xvfb.  
3. **Real ports** for everything that does not compile/run on mainline — one project per supported MC+loader.  
4. **Same smoke loop** on each port (server/web minimum; client if that port ships UI).  
5. **One mod_version** across the matrix for a release; many jars.  
6. **Publish** GitHub assets from `dist/matrix/`; then CF/Modrinth with correct loaders.  
7. Never mark a version “supported” in README/wiki without a jar and a smoke pass.

---

## Quick commands cheat sheet

```bash
# Mainline build + server
./gradlew build
./gradlew runServer

# Port build
export GRADLE_OPTS=-Xmx8G
cd ports/neoforge-1.21.1 && ./gradlew jar

# After web start (example)
curl -sS http://127.0.0.1:25570/api/health

# Release tag (after jars in dist/matrix/)
git tag -a v1.0.5 -m "Enchant Master 1.0.5"
git push origin v1.0.5

# Wiki
./push-wiki.sh
```

---

## User preferences (from project history)

- Prefer **real** multi-version ports over fake “one jar fits all” claims.  
- Prefer **real** screenshots (Xvfb / live web), not mockups.  
- Public GitHub should stay **mod-focused** — no CF/Modrinth draft spam, no MDK noise, no matrix log dumps.  
- Wiki is for players/admins, not for internal publish instructions.  
- When asked to work across versions: follow this testing + release strategy end-to-end.
