#!/usr/bin/env bash
# Build a portable tarball (macOS/Linux, arm64/x64) containing:
#   - the currently installed (patched) ViaProxy.jar
#   - the ViaProxyPlus plugin
#   - the realmbridge CLI (sign in, accept realm codes, wake realms, launch)
# Recipient needs python3; Java is auto-downloaded if missing.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$HOME/.bedrock-realm-bridge"
OUT="$HERE/dist"
NAME="bedrock-realm-bridge"

[[ -f "$CACHE/ViaProxy.jar" ]] || { echo "No ViaProxy.jar in $CACHE — run setup first"; exit 1; }

rm -rf "$OUT/$NAME" && mkdir -p "$OUT/$NAME/plugins"
cp "$CACHE/ViaProxy.jar" "$OUT/$NAME/"
[[ -f "$CACHE/plugins/ViaProxyPlus.jar" ]] && cp "$CACHE/plugins/ViaProxyPlus.jar" "$OUT/$NAME/plugins/"
cp -R "$HERE/realmbridge" "$OUT/$NAME/realmbridge"
rm -rf "$OUT/$NAME/realmbridge/__pycache__"
find "$OUT/$NAME" -name ".DS_Store" -delete
cp "$HERE/requirements.txt" "$HERE/bedrock-realm" "$OUT/$NAME/"
chmod +x "$OUT/$NAME/bedrock-realm"

cat > "$OUT/$NAME/README.txt" <<'EOF'
bedrock-realm-bridge — join a Bedrock Realm from Minecraft Java Edition
========================================================================

Requirements: python3. (Java 17+ is used if installed; otherwise it is
downloaded automatically on first run.)

Quick start:
  1.  ./bedrock-realm play <realm-invite-code>
      - Sign in with YOUR Microsoft account when prompted (device code:
        open the link, type the code).
      - The realm code is accepted onto your account automatically.
      - ViaProxy (the translator) opens.
  2.  In the ViaProxy window, "Accounts" tab: add your Microsoft account
      as a *Bedrock* account (not "Microsoft account" = Java type!) and
      select it. One-time step. Also select it in the General tab's
      "Minecraft Account" dropdown.
  3.  In the terminal where you started it, type:  realm <realm name>
      (e.g. "realm Penta"). One-time step; remembered afterwards.
  4.  General tab -> Server Version: Bedrock -> Start.
  5.  Minecraft Java Edition -> Multiplayer -> Direct Connection ->
      127.0.0.1:25568

Next time: just ./bedrock-realm play  (no code needed), Start, connect.
The realm address refreshes automatically on every join, and sleeping
realms are woken up for you.

Other commands: login, logout, accept <code>, realms, wake [name], setup.

Notes: this bundle includes a patched ViaProxy build with crash fixes and
smoothness patches (github ViaVersion/ViaProxy + RaphiMC/ViaBedrock, GPLv3).
You must be invited to the realm; everything uses your real account.
EOF

cd "$OUT" && tar -czf "$NAME-$(date +%Y%m%d).tar.gz" "$NAME"
echo "Built: $OUT/$NAME-$(date +%Y%m%d).tar.gz"
tar -tzf "$OUT/$NAME-$(date +%Y%m%d).tar.gz" | head -15
