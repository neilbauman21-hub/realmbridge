# bedrock-realm-bridge

Join a friend's **Bedrock** Minecraft **Realm** from **Java Edition** on a
**macOS Apple Silicon** machine, using just the realm invite code.

## Why this exists

Java and Bedrock use incompatible network protocols, and modern Bedrock Realms
use NetherNet (a WebRTC transport) with no joinable IP address. The heavy lifting
— speaking NetherNet + Bedrock and translating it to the Java protocol — is done
by [ViaProxy](https://github.com/ViaVersion/ViaProxy) (with its ViaBedrock
addon). There is no Bedrock client on macOS, so you also can't accept the realm
invite the normal way.

This tool fills the two gaps ViaProxy leaves for a Mac user:

1. Signs in to your Microsoft/Xbox account and **accepts the realm invite code**
   over the Bedrock Realms API, so your account becomes a member of the realm.
2. **Bootstraps Java + ViaProxy** on Apple Silicon and launches it.

You then point Minecraft Java Edition at `127.0.0.1:25568`.

## Install

```bash
cd ~/bedrock-realm-bridge
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
chmod +x bedrock-realm
```

## Use

One command does everything:

```bash
./bedrock-realm play <realm-invite-code>
```

(You can pass a bare code like `AbCdEfGhIjK` or a `https://realms.gg/AbCdEfGhIjK`
link.) It will:

1. Open a Microsoft sign-in prompt (device code — visit the URL, type the code).
2. Accept the invite so your account joins the realm.
3. Download Temurin JDK 21 and the latest ViaProxy if they aren't cached.
4. Launch ViaProxy.

In the ViaProxy window: add the **same** Microsoft account, click **Realms**,
pick your friend's realm, set the client version to your Java version, click
**Start**. Then in Minecraft Java → Multiplayer → Direct Connect →
`127.0.0.1:25568`.

Once you've joined a realm, you stay a member — later you can skip the code:

```bash
./bedrock-realm play
```

## Other commands

| Command | What it does |
|---|---|
| `login` | Sign in only (caches your token). |
| `accept <code>` | Accept a realm code without launching ViaProxy. |
| `realms` | List realms your account owns or has joined. |
| `setup` | Download Java + ViaProxy without launching. |

## Notes / limits

- **Everyone joining still needs a legit account and to be invited.** This does
  not bypass realm membership, ownership, or Minecraft/Xbox auth — it uses them.
- ViaProxy is a live protocol translator; some Bedrock-only mechanics won't map
  perfectly to a Java client. Vanilla survival play works well; heavily
  Bedrock-specific content may glitch.
- Cached tokens live in `~/.bedrock-realm-bridge/` (chmod 600). Delete that
  folder to fully sign out.
- If joins start failing with 403/426 after a Minecraft update, bump
  `CLIENT_VERSION` in `realmbridge/realms.py` to the current Bedrock version.

## How it fits together

```
Minecraft Java  ──TCP──▶  ViaProxy (ViaBedrock)  ──NetherNet──▶  Bedrock Realm
127.0.0.1:25568          translates Java⇄Bedrock       (member via invite code)
```

Credit: all protocol translation is [ViaProxy](https://github.com/ViaVersion/ViaProxy)
and [ViaBedrock](https://github.com/RaphiMC/ViaBedrock) by RaphiMC / the
ViaVersion team. This tool is just glue around them.
