#!/usr/bin/env bash
# Build the Ultron Network Optimizer against the server jar that is already
# checked into this repository. This keeps the build reproducible without
# downloading Gradle/Loom dependencies.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MOD_DIR="$ROOT_DIR/network-optimizer"
SRC_DIR="$MOD_DIR/src/main/java"
RES_DIR="$MOD_DIR/src/main/resources"
BUILD_DIR="$ROOT_DIR/.agent-work/tmp/ultron-network-optimizer-build"
CLASSES_DIR="$BUILD_DIR/classes"
MANIFEST="$BUILD_DIR/MANIFEST.MF"
OUTPUT_JAR="$ROOT_DIR/mods/ultron-network-optimizer-1.0.0.jar"

SERVER_JAR="$ROOT_DIR/versions/26.3/server-26.3.jar"

if [ ! -f "$SERVER_JAR" ]; then
    echo "ERROR: missing build dependency: $SERVER_JAR" >&2
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR" "$(dirname -- "$OUTPUT_JAR")"

# The server jar references its bundled libraries only through annotations in
# places, but javac still needs them on the classpath to resolve signatures.
LIBRARY_CP="$(find "$ROOT_DIR/libraries" -name '*.jar' -print | sort | paste -sd: -)"
CLASSPATH="$SERVER_JAR:$LIBRARY_CP"

find "$SRC_DIR" -name '*.java' -print | sort >"$BUILD_DIR/sources.txt"

javac \
    --release 25 \
    -encoding UTF-8 \
    -cp "$CLASSPATH" \
    -d "$CLASSES_DIR" \
    @"$BUILD_DIR/sources.txt"

cp -a "$RES_DIR/." "$CLASSES_DIR/"

cat >"$MANIFEST" <<'EOF'
Manifest-Version: 1.0
Fabric-Mapping-Namespace: official
Fabric-Minecraft-Version: 26.3
Fabric-Loader-Version: 0.20.1
EOF

jar --create --file "$OUTPUT_JAR" --manifest "$MANIFEST" -C "$CLASSES_DIR" .

echo "Built $OUTPUT_JAR"
