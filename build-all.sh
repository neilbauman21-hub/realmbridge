#!/usr/bin/env bash
# Build every RealmBridge artifact into dist/:
#   realmbridge-<v>.jar          Fabric mod (the in-game UI)
#   ViaProxy-patched.jar         patched protocol bridge
#   ViaProxyPlus.jar             QoL plugin
#   bedrock-realm-bridge.tar.gz  portable CLI bundle (macOS/Linux)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$HERE/dist"
rm -rf "$DIST" && mkdir -p "$DIST"

echo "==> proxy: patched ViaProxy jar"
"$HERE/proxy/build-patched-jar.sh"
cp "$HOME/.bedrock-realm-bridge/ViaProxy.jar" "$DIST/ViaProxy-patched.jar"

echo "==> proxy: ViaProxyPlus plugin"
"$HERE/proxy/build.sh"
cp "$HOME/.bedrock-realm-bridge/plugins/ViaProxyPlus.jar" "$DIST/"

echo "==> cli: portable bundle"
"$HERE/cli/make-dist.sh" > /dev/null
cp "$HERE"/cli/dist/bedrock-realm-bridge-*.tar.gz "$DIST/bedrock-realm-bridge.tar.gz"

echo "==> mod: fabric jar"
(cd "$HERE/mod" && gradle build --no-daemon -q)
cp "$HERE"/mod/build/libs/realmbridge-*.jar "$DIST/"

echo; echo "Artifacts:"; ls -lh "$DIST"
