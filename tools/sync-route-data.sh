#!/usr/bin/env bash
# Refreshes the routing data (walkable-tile map and transport tables) from the Shortest Path
# plugin, https://github.com/Skretzo/shortest-path (BSD 2-Clause, see THIRD-PARTY-NOTICES.md),
# at a pinned commit. Pass a commit hash to move the pin. The transport parser reads columns by
# header name, so a changed table layout shows up as a failing TransportTableTest, not as a
# silently wrong route.
set -euo pipefail
COMMIT="${1:-$(cat "$(dirname "$0")/../src/main/resources/com/slayersuite/route/SOURCE_COMMIT")}"
BASE="https://raw.githubusercontent.com/Skretzo/shortest-path/$COMMIT/src/main/resources"
DEST="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/com/slayersuite/route"
curl -fsSL "$BASE/collision-map.zip" -o "$DEST/collision-map.zip"
for f in transports agility_shortcuts; do
	curl -fsSL "$BASE/transports/$f.tsv" -o "$DEST/transports/$f.tsv"
	head -1 "$DEST/transports/$f.tsv" | grep -q "Origin" || { echo "$f.tsv: unexpected header"; exit 1; }
done
echo "$COMMIT" > "$DEST/SOURCE_COMMIT"
echo "routing data at $COMMIT"
