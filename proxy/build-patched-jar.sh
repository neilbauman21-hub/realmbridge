#!/usr/bin/env bash
# Rebuild the patched ViaProxy.jar from the clean base jar + jarpatches/src,
# and install it to ~/.bedrock-realm-bridge/ViaProxy.jar.
#
# The result is fully reproducible from this repo: checkout any tag, run this.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_JAR="$HERE/jars/ViaProxy-3.4.13-snapshot-b1927-base.jar"
TARGET="$HOME/.bedrock-realm-bridge/ViaProxy.jar"
OUT="$HERE/jarpatches/out"

[[ -f "$BASE_JAR" ]] || { echo "base jar missing: $BASE_JAR"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none --release 17 -cp "$BASE_JAR" -d "$OUT" \
  $(find "$HERE/jarpatches/src" -name '*.java')

cp "$BASE_JAR" "$TARGET"
(cd "$OUT" && jar -uf "$TARGET" $(find . -name '*.class'))
echo "Patched jar installed: $TARGET"
echo "Patched classes:"
(cd "$OUT" && find . -name '*.class' | sed 's|^\./|  |')
