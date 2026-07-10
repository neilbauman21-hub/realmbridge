# RealmBridge

Join **Bedrock Minecraft Realms from Java Edition** — from inside the game.

```
/realmbridge login          one-time Microsoft sign-in (device code shown in chat)
/realmbridge code <invite>  accept a Bedrock realm invite code
/realmbridge play [name]    wake the realm, start the bridge, then connect to 127.0.0.1:25568
/realmbridge stop           stop the bridge
```

Requires the [bedrock-realm-bridge](../bedrock-realm-bridge) install
(`~/.bedrock-realm-bridge` with its patched ViaProxy + ViaProxyPlus plugin and a
Bedrock account added once). The mod handles Realms auth + invite codes itself
(bundled MinecraftAuth) and drives ViaProxy headless.

Fabric, Minecraft 26.1.x, GPLv3.
