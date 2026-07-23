# Versions

## How versioning works

- **Mod version** (e.g. `1.0.5`) is the feature release.  
- **Jar classifiers** encode Minecraft + loader, e.g. `mc1.21.1-neoforge`.

Always match **Minecraft + loader**, not only the mod version number.

## 1.0.5 availability (summary)

| MC | Loader | Artifact pattern |
|----|--------|------------------|
| 26.1.2 | NeoForge | `enchantmaster-1.0.5.jar` / `…-mc26.1.2-neoforge.jar` |
| 1.21.8 | NeoForge | `…-mc1.21.8-neoforge.jar` |
| 1.21.5 | NeoForge | `…-mc1.21.5-neoforge.jar` |
| 1.21.4 | NeoForge | `…-mc1.21.4-neoforge.jar` |
| 1.21.3 | NeoForge | `…-mc1.21.3-neoforge.jar` |
| 1.21.1 | NeoForge | `…-mc1.21.1-neoforge.jar` |
| 1.21 | NeoForge | `…-mc1.21-neoforge.jar` |
| 1.20.6 | NeoForge | `…-mc1.20.6-neoforge.jar` |
| 1.20.4 | NeoForge | `…-mc1.20.4-neoforge.jar` |
| 1.20.2 | NeoForge | `…-mc1.20.2-neoforge.jar` |
| 1.20.1 | Forge | `…-mc1.20.1-forge.jar` |
| 1.16.5 | Forge | `…-mc1.16.5-forge.jar` |

## Unavailable by design

| MC | Reason |
|----|--------|
| 1.16.5 NeoForge | NeoForge does not exist |
| 1.20.0 / 1.20.1 NeoForge | NeoForge starts at 1.20.2 → use Forge 1.20.1 |
| Some minor 1.21.x | Use nearest tested family jar if no dedicated build |

Detailed test status: [version-ports-report.md](Versions.md).
