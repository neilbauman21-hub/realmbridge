# ViaProxyPlus

"Vanilla+" quality-of-life plugin for playing **Bedrock Realms** through
**ViaProxy/ViaBedrock** with a Java client. Companion to
[bedrock-realm-bridge](../bedrock-realm-bridge/).

## Features (v0.1.0)

- **Inventory auto-resync every 10s** — works around ViaBedrock's experimental
  inventory tracking ([ViaBedrock#276](https://github.com/RaphiMC/ViaBedrock/issues/276))
  where item pickups don't show up client-side. Pushes ViaBedrock's tracked
  (server-authoritative) inventory to the Java client. Skips players with a
  container UI open so it never clobbers a chest mid-interaction.
- **`resync` console command** — type `resync` in the ViaProxy console for an
  immediate resync of all connected players.
- **Warning-spam filter** — mutes known-harmless ViaBedrock warnings
  ("Missing waterlogged block state", "Invalid layer 2 block state",
  "Received packet ... outside PLAY state") after 5 occurrences each, so real
  errors stay readable.

## Build & install

```bash
./build.sh
```

Compiles against `~/.bedrock-realm-bridge/ViaProxy.jar` and installs to
`~/.bedrock-realm-bridge/plugins/ViaProxyPlus.jar`. ViaProxy picks it up on
next launch (`Loaded plugin 'ViaProxyPlus'` in the log).

## Notes

- Internal ViaBedrock APIs (`InventoryTracker`, `PacketFactory`) — a ViaProxy
  update can break the build; rerun `./build.sh` against the new jar and fix
  what the compiler flags.
- Backlog ideas: auto re-resolve stale NetherNet realm IDs on backend connect
  failure; in-game chat command (`!resync`); position-desync damping.
