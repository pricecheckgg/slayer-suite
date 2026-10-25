#!/bin/sh
# Regenerates tools/runelite-task-names.txt from the RuneLite client jar in the Gradle cache.
# Usage: JAVA_HOME=/path/to/jdk tools/dump-core-task-names.sh
set -e
JAR=$(ls -t "$HOME"/.gradle/caches/modules-2/files-2.1/net.runelite/client/*/*/client-*.jar | head -1)
JAVAP="${JAVA_HOME:-/usr}/bin/javap"
OUT="$(dirname "$0")/runelite-task-names.txt"
"$JAVAP" -p -constants -cp "$JAR" net.runelite.client.plugins.slayer.Task | grep -E "public static final net.runelite.client.plugins.slayer.Task " | sed -E 's/.*Task ([A-Z_0-9]+);/\1/' > /tmp/slayer-suite-enums.txt
"$JAVAP" -p -c -constants -cp "$JAR" net.runelite.client.plugins.slayer.Task | grep -E "^\s+[0-9]+: ldc.*String " | sed -E "s/.*String //; s/\\\\'/'/g" > /tmp/slayer-suite-ldc.txt
{
  echo "# Task names and NPC target names as the core RuneLite Slayer plugin spells them (net.runelite.client.plugins.slayer.Task)."
  echo "# Columns: ENUM|Display name|extra NPC names that count for the task. Regenerate with tools/dump-core-task-names.sh."
  awk 'NR==FNR { enums[$1]=1; next } { if ($0 in enums) { if (line != "") print line; line=$0 } else if (line != "") line=line "|" $0 } END { if (line != "") print line }' /tmp/slayer-suite-enums.txt /tmp/slayer-suite-ldc.txt
} > "$OUT"
rm -f /tmp/slayer-suite-enums.txt /tmp/slayer-suite-ldc.txt
echo "wrote $OUT"
