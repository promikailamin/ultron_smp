#!/usr/bin/env bash
# Build the Economy Paper plugin.
#
# The plugin uses Paper's Mojang-mapped server API for the inventory, chat and
# scoreboard code that was ported from the Fabric economy mod. Paper ships the
# server classes inside its paperclip bootstrapper, so this script initialises
# a throw-away Paper server once to extract versions/<mc>/paper-<mc>.jar and
# compiles against that jar plus the libraries it downloads.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${PAPER_BUILD_DIR:-$REPO_ROOT/.agent-work/tmp/paper-build}"
TOOLS_DIR="$REPO_ROOT/.agent-work/tools"
PAPER_VERSION="${PAPER_VERSION:-26.3}"
PAPER_BUILD="${PAPER_BUILD:-255}"
PAPER_SHA256="${PAPER_SHA256:-a7116085a8ba88cf00771d5e61a2925007942092650811da7327dc23243f0cec}"
PAPER_JAR="${PAPER_JAR:-$TOOLS_DIR/paper-$PAPER_VERSION-$PAPER_BUILD.jar}"
PATCHED_JAR="$WORK_DIR/versions/$PAPER_VERSION/paper-$PAPER_VERSION.jar"
OUTPUT_JAR="${OUTPUT_JAR:-$REPO_ROOT/paper/plugins/Economy-1.0.0.jar}"

mkdir -p "$TOOLS_DIR" "$WORK_DIR" "$(dirname "$OUTPUT_JAR")"

if [ ! -f "$PAPER_JAR" ]; then
    echo "Downloading Paper $PAPER_VERSION build $PAPER_BUILD..."
    curl -fsSL --retry 3 --max-time 300 \
        -o "$PAPER_JAR.tmp" \
        "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
    mv "$PAPER_JAR.tmp" "$PAPER_JAR"
fi

actual_sha="$(sha256sum "$PAPER_JAR" | awk '{print $1}')"
if [ "$actual_sha" != "$PAPER_SHA256" ]; then
    echo "ERROR: Paper jar checksum mismatch ($actual_sha)." >&2
    exit 1
fi

if [ ! -f "$PATCHED_JAR" ]; then
    echo "Initialising Paper to extract the patched server jar..."
    printf 'eula=true\n' > "$WORK_DIR/eula.txt"
    printf 'server-port=25599\nonline-mode=false\nlevel-name=build-world\nmax-players=1\nview-distance=2\n' > "$WORK_DIR/server.properties"
    (
        cd "$WORK_DIR"
        timeout 120 java -Xms256M -Xmx512M -jar "$PAPER_JAR" --nogui > paper-init.log 2>&1 || true
    )
fi

if [ ! -f "$PATCHED_JAR" ]; then
    echo "ERROR: Paper did not produce $PATCHED_JAR." >&2
    tail -50 "$WORK_DIR/paper-init.log" >&2 || true
    exit 1
fi

CLASSPATH="$PATCHED_JAR"
while IFS= read -r jar; do
    CLASSPATH="$CLASSPATH:$jar"
done < <(find "$WORK_DIR/libraries" -name '*.jar' 2>/dev/null | sort)

BUILD_CLASSES="$SCRIPT_DIR/build/classes"
rm -rf "$SCRIPT_DIR/build"
mkdir -p "$BUILD_CLASSES"

mapfile -t SOURCES < <(find "$SCRIPT_DIR/src/main/java" -name '*.java' | sort)
javac -encoding UTF-8 -source 25 -target 25 -Xlint:-options \
    -cp "$CLASSPATH" \
    -d "$BUILD_CLASSES" \
    "${SOURCES[@]}"

cp "$SCRIPT_DIR/src/main/resources/plugin.yml" "$BUILD_CLASSES/"
cp "$SCRIPT_DIR/src/main/resources/prices.tsv" "$BUILD_CLASSES/"

(
    cd "$BUILD_CLASSES"
    jar --create --file "$OUTPUT_JAR" .
)

echo "Built $OUTPUT_JAR"
sha256sum "$OUTPUT_JAR"
