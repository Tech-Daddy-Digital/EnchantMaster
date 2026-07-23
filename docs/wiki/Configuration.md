# Configuration

Primary file (NeoForge modern):

```text
config/enchantmaster-server.toml
```

Some Forge ports use world `serverconfig` paths depending on loader conventions.

## `[web]` options

| Key | Type | Description |
|-----|------|-------------|
| `host` | string | Bind address |
| `port` | int | Bind port |
| `publicUrl` | string | Shown to ops; empty derives `http://host:port` |
| `accessControl` | bool | Restrict by IP |
| `allowPlayerLan` | bool | Allow private /24 of each whitelisted player |
| `allowedSubnets` | string | Comma-separated CIDRs always allowed |
| `allowLocalhost` | bool | Allow 127.0.0.1 / ::1 |
| `trustProxyHeaders` | bool | Use `X-Forwarded-For` / `X-Real-IP` |
| `maxOverrideLevel` | int | Max enchant level when override is enabled |

## Upgrades

Config merges by key: **existing values kept**, new keys get defaults. Safe across 1.0.x upgrades.

## Example (reverse proxy)

```toml
[web]
host = "127.0.0.1"
port = 25570
publicUrl = "https://mc.example.com/enchantmaster"
accessControl = true
allowedSubnets = "10.0.0.0/8"
trustProxyHeaders = true
allowLocalhost = true
maxOverrideLevel = 255
```
