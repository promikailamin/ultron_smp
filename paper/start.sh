#!/usr/bin/env bash
# Start the Paper 26.3 server with the Economy plugin.
#
# Paper runs from this directory. The world, economy data and player files are
# symlinked to the repository root so the existing backups and the workflow's
# save step keep working unchanged.
#
# Optional environment variables:
#   PAPER_VERSION        Minecraft/Paper version (default: 26.3)
#   PAPER_BUILD          Paper build number (default: 255)
#   PAPER_SHA256         SHA-256 of the Paper jar
#   PAPER_JAVA_OPTS      JVM options (default: -Xms1G -Xmx2G)
set -euo pipefail

PAPER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$PAPER_DIR/.." && pwd)"
TOOLS_DIR="$REPO_ROOT/.agent-work/tools"
PAPER_VERSION="${PAPER_VERSION:-26.3}"
PAPER_BUILD="${PAPER_BUILD:-255}"
PAPER_SHA256="${PAPER_SHA256:-a7116085a8ba88cf00771d5e61a2925007942092650811da7327dc23243f0cec}"
PAPER_JAR="$PAPER_DIR/paper.jar"
PLUGIN_JAR="$PAPER_DIR/plugins/Economy-1.0.0.jar"

mkdir -p "$TOOLS_DIR" "$PAPER_DIR/plugins" "$PAPER_DIR/config"

if [ ! -f "$PAPER_JAR" ]; then
    if [ -f "$TOOLS_DIR/paper-$PAPER_VERSION-$PAPER_BUILD.jar" ]; then
        cp "$TOOLS_DIR/paper-$PAPER_VERSION-$PAPER_BUILD.jar" "$PAPER_JAR"
    else
        echo "Downloading Paper $PAPER_VERSION build $PAPER_BUILD..."
        curl -fsSL --retry 3 --max-time 300 \
            -o "$PAPER_JAR.tmp" \
            "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
        mv "$PAPER_JAR.tmp" "$PAPER_JAR"
    fi
fi

if [ ! -f "$PLUGIN_JAR" ]; then
    echo "Economy plugin jar is missing; building it..."
    PAPER_BUILD_DIR="${PAPER_BUILD_DIR:-$REPO_ROOT/.agent-work/tmp/paper-build}" \
    PAPER_JAR="$TOOLS_DIR/paper-$PAPER_VERSION-$PAPER_BUILD.jar" \
        bash "$REPO_ROOT/paper-plugin/build.sh"
fi

link_from_root() {
    local name="$1"
    local target="$REPO_ROOT/$name"
    local link="$PAPER_DIR/$name"
    if [ -e "$target" ] && [ ! -e "$link" ]; then
        ln -s "../$name" "$link"
        echo "Linked paper/$name -> ../$name"
    fi
}

# Keep the existing world, economy data and player lists as the single source
# of truth so the workflow's git snapshots continue to capture all changes.
link_from_root world
link_from_root world_nether
link_from_root world_the_end
link_from_root ops.json
link_from_root whitelist.json
link_from_root banned-players.json
link_from_root banned-ips.json
link_from_root usercache.json

if [ -d "$REPO_ROOT/config/economy" ] && [ ! -e "$PAPER_DIR/config/economy" ]; then
    ln -s "../../config/economy" "$PAPER_DIR/config/economy"
    echo "Linked paper/config/economy -> ../../config/economy"
fi

cd "$PAPER_DIR"
exec java ${PAPER_JAVA_OPTS:--Xms1G -Xmx2G} -jar "$PAPER_JAR" --nogui "$@"
