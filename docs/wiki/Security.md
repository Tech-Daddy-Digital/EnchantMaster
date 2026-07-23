# Security

## Threat model

Enchant Master’s web UI is an **operator power tool** with **no authentication**. Anyone who can reach the HTTP port can attempt forge/inventory actions (subject to IP filters).

Use it only when:

- Bound to localhost / private LAN, **or**  
- Behind a reverse proxy with your own auth (Basic Auth, SSO, VPN), **or**  
- On a host where firewall rules limit who can connect.

## Built-in mitigations

1. **Does not auto-start** — requires OP/console `web start`.  
2. **IP access control** (default on) — whitelist OP IPs, optional LAN/CIDR.  
3. **Audit log** — who forged what, from which IP.  
4. **Server-side permission checks** on forge/modify.

## Recommendations

| Environment | Recommendation |
|-------------|----------------|
| Local testing | `host = "127.0.0.1"` |
| LAN SMP | Access control on; only ops run start |
| Public VPS | Proxy + TLS + auth; bind 127.0.0.1; never open raw port to the world |
| Shared hosting | Confirm you can firewall the web port |

## Audit log location

```text
logs/enchantmaster-audit.log
```

Also mirrored as `[AUDIT]` lines in the main server log.

## What not to do

- Do not advertise the web port publicly without a proxy and auth.  
- Do not enable `trustProxyHeaders` unless **only** your reverse proxy can connect to the bind address.  
- Do not run with `accessControl = false` on untrusted networks.
