# Media assets (real captures)

All UI screenshots were taken from a live **NeoForge 26.1.2** session under **Xvfb** (virtual X11), not mockups.

## Banner
| File | Source |
|------|--------|
| `banner-enchant-master.jpg` | Marketing banner for store headers |

## Web UI (live server `runServer` + Playwright)
| File | Description |
|------|-------------|
| `web-01-forge-overview.png` | Forge tab — Stormcaller preview, Sharpness V, Unbreaking III |
| `web-02-inventory-modify.png` | Inventory Modify — offline player slots from real playerdata |
| `web-03-forge-gallery.png` | Compact gallery crop of Forge tab |

## In-game UI (Xvfb client, `/enchantmaster open`)
| File | Description |
|------|-------------|
| `ingame-01-item-select.png` | Step 1/5 — filter “diamond sword” |
| `ingame-03-enchantments.png` | Step 2/5 — sword enchant list (10 available) |
| `ingame-04-attributes.png` | Step 3/5 — Attack Damage / Attack Speed |
| `ingame-05-appearance.png` | Step 4/5 — Name & flavor fields |
| `ingame-02-forge-overview.png` | Step 5/5 — Preview & Give (diamond sword) |

## How these were captured
```bash
# Background Xvfb :93, dedicated server + web, then:
./gradlew runClient -PmediaQuickPlay="New World"
# xdotool + import for in-game; Playwright against http://127.0.0.1:25710 for web
```
