#!/usr/bin/env bash
# Build ViaProxyPlus against the installed ViaProxy jar and install it into
# ViaProxy's plugins directory.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VIAPROXY_JAR="$HOME/.bedrock-realm-bridge/ViaProxy.jar"
PLUGINS_DIR="$HOME/.bedrock-realm-bridge/plugins"
OUT="$HERE/out"

[[ -f "$VIAPROXY_JAR" ]] || { echo "ViaProxy.jar not found at $VIAPROXY_JAR"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none --release 17 -cp "$VIAPROXY_JAR" \
  -d "$OUT" $(find "$HERE/src" -name '*.java')
cp "$HERE/viaproxy.yml" "$OUT/"
mkdir -p "$PLUGINS_DIR"
jar -cf "$PLUGINS_DIR/ViaProxyPlus.jar" -C "$OUT" .
echo "Installed: $PLUGINS_DIR/ViaProxyPlus.jar"
