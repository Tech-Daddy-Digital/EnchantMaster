# Commands

All commands require **operator** permission (or server console), unless noted.

| Command | Description |
|---------|-------------|
| `/enchantmaster web start` | Start the embedded web server. With access control, registers the OP’s connection IP. |
| `/enchantmaster web stop` | Stop the web server and clear the player IP whitelist. |
| `/enchantmaster web status` | Show whether the UI is running, bind address, public URL, access-control summary. |
| `/enchantmaster open` | Open the **in-game** forge UI. Requires Enchant Master on the **client**. Hidden/unavailable if the client lacks the mod. |

## Console vs player

| Source | Web start | Open UI |
|--------|-----------|---------|
| Server console | Yes (no player IP whitelist entry unless configured) | N/A |
| OP in-game | Yes + IP whitelist | Yes if client has mod |

## Tips

- Running `web start` while already running **adds** another OP’s IP without restarting the listener.
- Logging out removes that player’s IP from the whitelist when access control is enabled.
