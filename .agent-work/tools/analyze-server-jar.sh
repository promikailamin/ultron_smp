#!/usr/bin/env bash
# Summarise a Minecraft server jar and the network packet classes inside it.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${1:-$ROOT_DIR/versions/26.3/server-26.3.jar}"

if [ ! -f "$JAR" ]; then
    echo "ERROR: server jar not found: $JAR" >&2
    exit 1
fi

listing="$(unzip -Z1 "$JAR")"
classes="$(printf '%s\n' "$listing" | grep -c '\.class$' || true)"
game_packets="$(printf '%s\n' "$listing" | grep -c '^net/minecraft/network/protocol/game/Clientbound' || true)"
serverbound_packets="$(printf '%s\n' "$listing" | grep -c '^net/minecraft/network/protocol/game/Serverbound' || true)"

echo "Server jar: $JAR"
echo "  entries:             $(printf '%s\n' "$listing" | wc -l)"
echo "  classes:             $classes"
echo "  Clientbound packets: $game_packets"
echo "  Serverbound packets: $serverbound_packets"
echo
echo "Largest game-packet classes (uncompressed class-file size):"
unzip -l "$JAR" \
    | awk '$4 ~ /^net\/minecraft\/network\/protocol\/game\/(Clientbound|Serverbound)/ {print $1, $4}' \
    | sort -nr \
    | head -25
echo
echo "Installed mods by size:"
find "$ROOT_DIR/mods" -maxdepth 1 -name '*.jar' -printf '  %10s  %p\n' | sort -nr
