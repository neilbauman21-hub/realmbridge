# RealmBridge

**Play on Bedrock Minecraft Realms from Java Edition** — invite codes, one-click
join, Fabric mods (Baritone, Litematica, ...) and all.

Java and Bedrock can't normally share a Realm. RealmBridge glues them together:
a patched [ViaProxy](https://github.com/ViaVersion/ViaProxy)/[ViaBedrock](https://github.com/RaphiMC/ViaBedrock)
translates the protocol, a plugin keeps the connection seamless, and a Fabric
mod puts the whole thing behind a button in your Multiplayer screen.

## The pieces

| dir | what | artifact |
|-----|------|----------|
| `mod/` | Fabric mod (MC 26.1.x): "Bedrock Realms" button, Microsoft sign-in, invite codes, auto-connect | `realmbridge-<v>.jar` |
| `proxy/` | ViaProxy patch set + ViaProxyPlus plugin: crash fixes, item-pickup emulation, movement smoothing, realm auto-refresh/auto-wake | `ViaProxy-patched.jar`, `ViaProxyPlus.jar` |
| `cli/` | Python CLI + portable bundle for terminal users / Linux friends | `bedrock-realm-bridge.tar.gz` |

## Quick start (mod, recommended)

1. Install Fabric for Minecraft 26.1.x with [fabric-api](https://modrinth.com/mod/fabric-api),
   drop `realmbridge-<v>.jar` in `mods/`.
2. Unpack `bedrock-realm-bridge.tar.gz` and run `./bedrock-realm setup` once
   (installs the patched ViaProxy + plugin to `~/.bedrock-realm-bridge`), then add
   your Microsoft account in the ViaProxy window as a **Bedrock** account (one time).
3. In Minecraft: **Multiplayer → Bedrock Realms** → sign in → paste an invite
   code or click your realm. That's it.

## Quick start (terminal only)

```bash
tar -xzf bedrock-realm-bridge.tar.gz && cd bedrock-realm-bridge
./bedrock-realm play <realm-invite-code>
```

## Build everything

```bash
./build-all.sh   # needs JDK 17+, gradle, python3
```

## Notes

- Patched sources for ViaProxy/ViaBedrock live in `proxy/jarpatches/src` — each
  change is documented in the git history; several fixes are being upstreamed.
- GPLv3 (contains ViaBedrock-derived sources).
