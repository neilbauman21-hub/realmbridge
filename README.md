# RealmBridge

**Play on Bedrock Minecraft Realms from Java Edition.** Invite codes, one-click
join, and your Fabric mods (Baritone, Litematica, Sodium, ...) — on your
friends' Bedrock Realm.

Java and Bedrock normally can't share a Realm. RealmBridge glues them together:
a patched [ViaProxy](https://github.com/ViaVersion/ViaProxy)/[ViaBedrock](https://github.com/RaphiMC/ViaBedrock)
translates the protocol live, a companion plugin makes it feel vanilla
(instant item pickups, smooth movement, auto realm wake/reconnect), and a
Fabric mod wraps everything behind one button in your Multiplayer screen.

## Install (2 minutes)

1. Install [Fabric](https://fabricmc.net/use/installer/) for Minecraft 26.1.x
   and put [fabric-api](https://modrinth.com/mod/fabric-api) +
   **`realmbridge-<version>.jar`** (from [Releases](../../releases/latest))
   into your `mods/` folder.
2. Launch the game → **Multiplayer → Bedrock Realms** (top-right button).
3. **Sign in with Microsoft** (one time — a code + browser button appear;
   use the account that owns/joins the Bedrock realm).
4. Paste a **realm invite code** → your realm appears → **click it**.

The mod downloads the bridge (~45 MB, one time) to `~/.bedrock-realm-bridge`,
signs it in with your account automatically, wakes the realm, and connects you.
Java 17+ is required (the Minecraft launcher's bundled Java works).

## Good to know

- **First join after the realm slept** can time out once while the realm
  server boots — just click again.
- The bridge keeps running while you play; `/realmbridge stop` shuts it down.
- Client-side mods work normally. Server-dependent mods can't (there is no
  Java server). Movement cheats will rubber-band — Bedrock realms are
  server-authoritative.
- Lighting is approximate (uniform bright): light translation isn't
  implemented upstream yet.
- Terminal-only alternative (macOS/Linux): grab
  `bedrock-realm-bridge.tar.gz` from Releases —
  `./bedrock-realm play <invite-code>`.

## Repo layout / building

| dir | what | artifact |
|-----|------|----------|
| `mod/` | Fabric mod: UI, Microsoft sign-in, invite codes, bridge bootstrap, auto-connect | `realmbridge-<v>.jar` |
| `proxy/` | ViaProxy patch set + ViaProxyPlus plugin (documented in `jarpatches/src` + git history) | `ViaProxy-patched.jar`, `ViaProxyPlus.jar` |
| `cli/` | Python CLI + portable bundle | `bedrock-realm-bridge.tar.gz` |

```bash
./build-all.sh   # JDK 17+, gradle, python3 → everything lands in dist/
```

## Credits & license

Built on [ViaProxy](https://github.com/ViaVersion/ViaProxy),
[ViaBedrock](https://github.com/RaphiMC/ViaBedrock) and
[MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) by RK_01/RaphiMC &
contributors. Several fixes from this repo are headed upstream. GPLv3.
